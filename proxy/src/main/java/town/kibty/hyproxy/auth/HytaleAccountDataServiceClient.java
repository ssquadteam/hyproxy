package town.kibty.hyproxy.auth;

import kong.unirest.core.Unirest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.auth.model.accountdata.GameProfile;
import town.kibty.hyproxy.auth.model.accountdata.GetProfilesResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
@Slf4j
public class HytaleAccountDataServiceClient {
    private static final String ACCOUNT_DATA_BASE_URL = System.getProperty("hyproxy.auth.account", "https://account-data.hytale.com");

    public CompletableFuture<@Nullable List<GameProfile>> getGameProfiles(String oauthToken) {
        return Unirest.get(ACCOUNT_DATA_BASE_URL + "/my-account/get-profiles")
                .accept("application/json")
                .header("authorization", "Bearer " + oauthToken)
                .asObjectAsync(GetProfilesResponse.class)
                .thenApply(res -> {
                    if (!res.isSuccess()) {
                        System.out.println(res.getParsingError());
                        log.error("got non success from account data service while getting game profiles (status={})", res.getStatus());
                        return null;
                    }

                    return res.getBody().profiles();
                })
                .exceptionally(ex -> {
                    log.error("error while getting game profiles from account data service", ex);
                    return null;
                });
    }
}
