package town.kibty.hyproxy.plugin;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record PluginInfo(
        String id,
        String name,
        String version,
        String mainClass,
        @Nullable String description,
        List<String> authors
) {
}
