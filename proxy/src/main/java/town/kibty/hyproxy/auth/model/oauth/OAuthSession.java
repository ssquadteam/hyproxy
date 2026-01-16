package town.kibty.hyproxy.auth.model.oauth;

import lombok.With;

import java.util.UUID;

@With
public record OAuthSession(
        String accessToken,
        String refreshToken,
        UUID profileId,
        long issuedAt,
        long accessTokenExpiresAt
) {
}
