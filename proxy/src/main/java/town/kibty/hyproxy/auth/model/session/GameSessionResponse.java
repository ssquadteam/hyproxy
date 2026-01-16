package town.kibty.hyproxy.auth.model.session;

import org.jspecify.annotations.Nullable;

public record GameSessionResponse(
        String sessionToken,
        String identityToken,
        @Nullable String expiresAt
) {
}
