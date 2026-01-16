package town.kibty.hyproxy.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kong.unirest.core.Unirest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.auth.model.accountdata.GameProfile;
import town.kibty.hyproxy.auth.model.oauth.OAuthSession;
import town.kibty.hyproxy.auth.model.oauth.TokenResponse;
import town.kibty.hyproxy.auth.model.oauth.deviceauth.DeviceAuthResponse;
import town.kibty.hyproxy.auth.model.session.GameSessionResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor
public class HytaleOAuthServiceClient {
    public static final String OAUTH_BASE_URL = System.getProperty("hyproxy.auth.oauthBaseUrl", "https://oauth.accounts.hytale.com");
    private static final String OAUTH_SESSION_FILE = ".hyproxy-oauth-session.json";
    private static final String SERVER_CLIENT_ID = "hytale-server";
    private static final String SERVER_SCOPES = "openid offline auth:server";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private final HyProxy proxy;

    public void start() {
        if (proxy.getConfiguration().getIdentityToken() != null) {
            log.info("not bothering with oauth as we have a identity/session token pair set");
            return;
        }

        Path oauthSessionFile = Path.of(OAUTH_SESSION_FILE);
        if (this.tryLoadFromOAuthSessionFile(oauthSessionFile)) {
            return;
        }

        try {
            OAuthSession session = null;
            DeviceAuthResponse deviceAuthResponse = this.requestDeviceAuth().get(10L, TimeUnit.SECONDS);
            if (deviceAuthResponse == null) {
                log.error("failed to request device auth, shutting down");
                proxy.shutdown(true);
                return;
            }

            log.info("go to {} and enter code {}", deviceAuthResponse.verificationUri(), deviceAuthResponse.userCode());
            if (deviceAuthResponse.verificationUriComplete() != null) {
                log.info("or go directly to {}", deviceAuthResponse.verificationUriComplete());
            }
            log.info("and authorize to get your proxy online");
            log.info("expires in {} seconds", deviceAuthResponse.expiresIn());

            int pollInterval = Math.max(deviceAuthResponse.interval(), 5);
            long deadline = System.currentTimeMillis() + deviceAuthResponse.expiresIn() * 1000L;

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollInterval * 1000L);
                TokenResponse tokenResponse = this.exchangeDeviceCode(deviceAuthResponse.deviceCode()).get(10L, TimeUnit.SECONDS);
                if (tokenResponse == null) {
                    continue;
                }


                if (tokenResponse.error() != null) {
                    if ("authorization_pending".equals(tokenResponse.error())) {
                        pollInterval += 5;
                        continue;
                    }

                    if ("slow_down".equals(tokenResponse.error())) {
                        pollInterval += 5;
                        continue;
                    }

                    log.error("device auth exchange failed: {}", tokenResponse.error());
                    proxy.shutdown(true);
                    return;
                }

                UUID selectedGameProfile = this.selectGameProfile(tokenResponse);
                session = this.storeOAuthSession(oauthSessionFile, tokenResponse, selectedGameProfile);
                break;
            }

            if (session == null) {
                log.error("device auth exchange timed out, please try again");
                return;
            }

            log.info("got oauth session, creating initial game session from oauth");

            GameSessionResponse sessionResponse = this.proxy.getSessionServiceClient().createGameSession(session.accessToken(), session.profileId()).get(10L, TimeUnit.SECONDS);
            proxy.getSessionServiceClient().setGameSession(sessionResponse);
        } catch (Exception ex) {
            log.error("error while trying to authenticate", ex);
            proxy.shutdown(true);
        }
    }

    private @Nullable UUID selectGameProfile(TokenResponse tokenResponse) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        List<GameProfile> gameProfiles = proxy.getAccountDataServiceClient().getGameProfiles(tokenResponse.accessToken()).get(10L, TimeUnit.SECONDS);
        if (gameProfiles == null) {
            log.error("couldn't get game profiles");
            proxy.shutdown(true);
            throw new IllegalStateException("unreachable, we called shutdown");
        }

        if (gameProfiles.isEmpty()) {
            log.error("this account has no game profiles associated with it! you need a copy of hytale to host a hyproxy instance.");
            proxy.shutdown(true);
            throw new IllegalStateException("unreachable, we called shutdown");
        }

        if (gameProfiles.size() < 2) {
            log.info("autoselecting first profile as it was the only one available");
            return gameProfiles.getFirst().uuid();
        }

        log.info("you have {} game profiles available to select", gameProfiles.size());
        log.info("type the index of the game profile to select in the console and press enter");

        for (int i = 0; i < gameProfiles.size(); i++) {
            GameProfile gameProfile = gameProfiles.get(i);
            log.info("[{}] {} ({})", i, gameProfile.username(), gameProfile.uuid());
        }

        GameProfile selectedProfile = null;
        while (selectedProfile == null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();

            int index;
            try {
                index = Integer.parseInt(line);
            } catch (NumberFormatException ex) {
                log.error("invalid number, try again");
                continue;
            }

            if (index < 0 || index > gameProfiles.size() - 1) {
                log.error("index out of bounds, you only have {} profiles available, try again", gameProfiles.size());
                continue;
            }

            selectedProfile = gameProfiles.get(index);
        }

        return selectedProfile.uuid();
    }

    private boolean tryLoadFromOAuthSessionFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return false;
        }

        if (!Files.isRegularFile(filePath)) {
            log.warn("oauth session file wasn't a regular file, ignoring");
            return false;
        }

        try {
            OAuthSession session = GSON.fromJson(Files.readString(filePath, StandardCharsets.UTF_8), OAuthSession.class);

            if (Instant.now().getEpochSecond() > session.accessTokenExpiresAt()) {
                TokenResponse refreshedTokenResponse = this.refreshToken(session.refreshToken()).get(10L, TimeUnit.SECONDS);
                if (refreshedTokenResponse == null) {
                    log.error("failed to refresh token, ignoring oauth session");
                    return false;
                }

                log.info("refreshed oauth session");
                session = this.storeOAuthSession(filePath, refreshedTokenResponse, session.profileId());
            }

            GameSessionResponse sessionResponse = this.proxy.getSessionServiceClient().createGameSession(session.accessToken(), session.profileId())
                    .get(10L, TimeUnit.SECONDS);
            proxy.getSessionServiceClient().setGameSession(sessionResponse);
            log.info("created game session from existing oauth session");

            return true;
        } catch (Exception ex) {
            log.error("error while loading from oauth session file, ignoring", ex);
            return false;
        }
    }

    private OAuthSession storeOAuthSession(Path filePath, TokenResponse response, UUID profileId) {
        long nowSeconds = Instant.now().getEpochSecond();

        OAuthSession session = new OAuthSession(
                response.accessToken(),
                response.refreshToken(),
                profileId,
                nowSeconds,
                nowSeconds + response.expiresIn()
        );

        try {
            Files.writeString(filePath, GSON.toJson(session), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.error("failed to save oauth session", ex);
        }

        return session;
    }

    private CompletableFuture<@Nullable DeviceAuthResponse> requestDeviceAuth() {
        Charset charset = StandardCharsets.UTF_8;
        return Unirest.post(OAUTH_BASE_URL + "/oauth2/device/auth")
                .contentType("application/x-www-form-urlencoded")
                .charset(charset)
                .body(String.format(
                        "client_id=%s&scope=%s",
                        URLEncoder.encode(SERVER_CLIENT_ID, charset),
                        URLEncoder.encode(SERVER_SCOPES, charset)
                ))
                .asObjectAsync(DeviceAuthResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from oauth service while requesting device auth (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("error while requesting device auth", ex);
                    return null;
                });
    }

    public CompletableFuture<@Nullable TokenResponse> exchangeDeviceCode(String deviceCode) {
        Charset charset = StandardCharsets.UTF_8;
        return Unirest.post(OAUTH_BASE_URL + "/oauth2/token")
                .contentType("application/x-www-form-urlencoded")
                .charset(charset)
                .body(String.format(
                        "grant_type=%s&client_id=%s&device_code=%s",
                        URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", charset),
                        URLEncoder.encode(SERVER_CLIENT_ID, charset),
                        URLEncoder.encode(deviceCode, charset)
                ))
                .asObjectAsync(TokenResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess() && res.getStatus() != 400) {
                        log.error("got non success from oauth service while exchanging device code (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("error while exchanging device code", ex);
                    return null;
                });
    }

    public CompletableFuture<@Nullable TokenResponse> refreshToken(String refreshToken) {
        Charset charset = StandardCharsets.UTF_8;
        return Unirest.post(OAUTH_BASE_URL + "/oauth2/token")
                .contentType("application/x-www-form-urlencoded")
                .charset(charset)
                .body(String.format(
                        "grant_type=refresh_token&client_id=%s&refresh_token=%s",
                        URLEncoder.encode(SERVER_CLIENT_ID, charset),
                        URLEncoder.encode(refreshToken, charset)
                ))
                .asObjectAsync(TokenResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from oauth service while refreshing token (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("error while refreshing token", ex);
                    return null;
                });
    }
}
