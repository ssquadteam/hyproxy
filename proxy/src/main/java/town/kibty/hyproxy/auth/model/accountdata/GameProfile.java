package town.kibty.hyproxy.auth.model.accountdata;

import java.util.UUID;

public record GameProfile(
        UUID uuid,
        String username
) {
}
