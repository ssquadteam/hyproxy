package town.kibty.hyproxy.auth.model.oauth;

import java.util.UUID;

public record OAuthSession(
        String accessToken,
        String refreshToken,
        UUID profileId,
        long issuedAt,
        long accessTokenExpiresAt
) {
}
