package ac.eva.hyproxy.auth.model.session;

public record JWKsResponse(
        JWKKey[] keys
) {
    public record JWKKey(
            String kty,
            String alg,
            String use,
            String kid,
            String crv,
            String x,
            String y,
            String n,
            String e
    ) {}
}
