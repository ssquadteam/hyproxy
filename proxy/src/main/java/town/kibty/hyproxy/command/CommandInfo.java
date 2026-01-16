package town.kibty.hyproxy.command;

import org.jspecify.annotations.Nullable;

public record CommandInfo(
        String name,
        @Nullable String description
) {
}
