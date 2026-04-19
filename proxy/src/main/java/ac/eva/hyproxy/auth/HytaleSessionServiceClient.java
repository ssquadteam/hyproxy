package ac.eva.hyproxy.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import kong.unirest.core.Unirest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.auth.model.session.grant.AuthGrantRequest;
import ac.eva.hyproxy.auth.model.session.grant.AuthGrantResponse;
import ac.eva.hyproxy.auth.model.session.GameSessionResponse;
import ac.eva.hyproxy.auth.model.session.JWKsResponse;
import ac.eva.hyproxy.auth.model.session.token.AuthTokenRequest;
import ac.eva.hyproxy.auth.model.session.token.AuthTokenResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RequiredArgsConstructor
public class HytaleSessionServiceClient {
    private static final String SESSIONS_BASE_URL = System.getProperty("hyproxy.auth.sessionsBaseUrl", "https://sessions.hytale.com");
    public static final String SESSIONS_ISSUER = System.getProperty("hyproxy.auth.sessionsIssuer", "https://sessions.hytale.com");
    public static final String SERVER_AUDIENCE = System.getProperty("hyproxy.auth.serverAudience", UUID.randomUUID().toString());
    private static final Gson GSON = new Gson();
    private static final ScheduledExecutorService SESSION_REFRESH_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hyproxy-session-refresh");
        t.setDaemon(true);
        return t;
    });

    private static final String JWK_KEY_TYPE = "OKP";

    private final HyProxy proxy;
    @Getter
    private JWKSet jwkSet;
    @Setter
    private @Nullable GameSessionResponse gameSession = null;
    private ScheduledFuture<?> refreshTask;

    public void start() {
        log.info("fetching JWKs from session service");
        try {
            JWKsResponse response = fetchJWKs().get(10L, TimeUnit.SECONDS);
            if (response == null) {
                proxy.shutdown(true);
                return;
            }

            List<JWK> jwks = new ArrayList<>();
            for (JWKsResponse.JWKKey key : response.keys()) {
                if (!key.kty().equals(JWK_KEY_TYPE)) {
                    log.warn("skipping session service key because of unknown key type (got={},expected={})", key.kty(), JWK_KEY_TYPE);
                    continue;
                }
                JWK jwk = JWK.parse(Map.of(
                        "kty", JWK_KEY_TYPE,
                        "crv", key.crv(),
                        "x", key.x(),
                        "kid", key.kid(),
                        "alg", "EdDSA"
                ));

                jwks.add(jwk);
            }

            if (jwks.isEmpty()) {
                log.error("failed to get any JWKs from hytale session service");
                proxy.shutdown(true);
                return;
            }

            this.jwkSet = new JWKSet(jwks);
            log.info("loaded {} JWKs", jwkSet.size());

            this.scheduleInitialRefresh();
        } catch (Exception ex) {
            log.error("error while starting up session service client", ex);
            proxy.shutdown(true);
        }
    }

    public String getIdentityToken() {
        return this.gameSession != null ? this.gameSession.identityToken() : this.proxy.getConfiguration().getIdentityToken();
    }

    private String getSessionToken() {
        return this.gameSession != null ? this.gameSession.sessionToken() : this.proxy.getConfiguration().getSessionToken();
    }

    private void scheduleInitialRefresh() {
        String identityToken = this.getIdentityToken();
        try {
            String[] parts = identityToken.split("\\.");
            if (parts.length != 3) {
                log.error("failed to parse identity token expiry (not a valid jwt), your session will NOT refresh");
                return;
            }

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject json = GSON.fromJson(payload, JsonObject.class);

            if (!json.has("exp")) {
                log.warn("your identity token seems to have no expiry which is not normal, your token will not refresh as we don't know when to refresh it");
                return;
            }

            long exp = json.get("exp").getAsLong();

            long secondsUntilExpiry = exp - Instant.now().getEpochSecond();
            if (secondsUntilExpiry > 300L) {
                this.scheduleRefresh(secondsUntilExpiry);
            }
        } catch (Exception ex) {
            log.error("failed to parse identity token expiry, your session will NOT refresh", ex);
        }
    }

    private void scheduleRefresh(long secondsUntilExpiry) {
        if (this.refreshTask != null) {
            this.refreshTask.cancel(false);
        }

        long refreshDelay = Math.max(secondsUntilExpiry - 300, 60);
        this.refreshTask = SESSION_REFRESH_SCHEDULER.schedule(this::refreshCurrentSession, refreshDelay, TimeUnit.SECONDS);
    }

    public void refreshCurrentSession() {
        GameSessionResponse response = this.refreshSession().join();
        if (response == null) {
            log.error("the proxy game session failed to refresh. your server will now lose authentication");
            return;
        }
        this.gameSession = response;

        if (response.expiresAt() != null) {
            Instant expiresAt = Instant.parse(response.expiresAt());
            long secondsUntilExpiry = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
            if (secondsUntilExpiry > 300L) {
                this.scheduleRefresh((int)secondsUntilExpiry);
            }
        }

        log.info("refreshed session successfully");
    }

    public CompletableFuture<@Nullable JWKsResponse> fetchJWKs() {
        return Unirest.get(SESSIONS_BASE_URL + "/.well-known/jwks.json")
                .accept("application/json")
                .asObjectAsync(JWKsResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from session service while fetching JWKs (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("failed to fetch JWKs from hytale session service", ex);
                    return null;
                });
    }

    public CompletableFuture<@Nullable String> requestAuthGrant(String clientIdentityToken) {
        return Unirest.post(SESSIONS_BASE_URL + "/server-join/auth-grant")
                .contentType("application/json")
                .accept("application/json")
                .header("authorization", "Bearer " + this.getSessionToken())
                .body(new AuthGrantRequest(clientIdentityToken, SERVER_AUDIENCE))
                .asObjectAsync(AuthGrantResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from session service while requesting auth grant (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody().authorizationGrant();
                })
                .exceptionally(ex -> {
                    log.error("failed to request auth grant from hytale session service", ex);
                    return null;
                });

    }

    public CompletableFuture<@Nullable String> exchangeAuthGrantForToken(String authGrant, String x509Fingerprint) {
        return Unirest.post(SESSIONS_BASE_URL + "/server-join/auth-token")
                .contentType("application/json")
                .accept("application/json")
                .header("authorization", "Bearer " + this.getSessionToken())
                .body(new AuthTokenRequest(authGrant, x509Fingerprint))
                .asObjectAsync(AuthTokenResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from session service while exchanging auth grant (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody().accessToken();
                })
                .exceptionally(ex -> {
                    log.error("failed to exchange auth grant from hytale session service", ex);
                    return null;
                });

    }

    public CompletableFuture<@Nullable GameSessionResponse> createGameSession(String oauthToken, UUID profileId) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", profileId.toString());
        return Unirest.post(SESSIONS_BASE_URL + "/game-session/new")
                .contentType("application/json")
                .accept("application/json")
                .header("authorization", "Bearer " + oauthToken)
                .body(body)
                .asObjectAsync(GameSessionResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from session service while creating game session (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("failed to create game session from hytale session service", ex);
                    return null;
                });
    }

    public CompletableFuture<@Nullable GameSessionResponse> refreshSession() {
        return Unirest.post(SESSIONS_BASE_URL + "/game-session/refresh")
                .accept("application/json")
                .header("authorization", "Bearer " + this.getSessionToken())
                .asObjectAsync(GameSessionResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        log.error("got non success from session service while refreshing session (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody();
                })
                .exceptionally(ex -> {
                    log.error("failed to refresh session from hytale session service", ex);
                    return null;
                });
    }
}
