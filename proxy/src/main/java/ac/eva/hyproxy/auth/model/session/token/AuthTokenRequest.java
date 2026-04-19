package ac.eva.hyproxy.auth.model.session.token;

public record AuthTokenRequest(
        String authorizationGrant,
        String x509Fingerprint
) {
}
