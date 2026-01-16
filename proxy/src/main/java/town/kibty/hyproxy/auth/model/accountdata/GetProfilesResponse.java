package town.kibty.hyproxy.auth.model.accountdata;

import java.util.List;

public record GetProfilesResponse(
        List<GameProfile> profiles
) {
}
