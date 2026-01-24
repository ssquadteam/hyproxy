package town.kibty.hyproxy.plugin;

import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import town.kibty.hyproxy.HyProxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class HyProxyPluginManager {
    private static final Gson GSON = new Gson();
    private static final String PLUGIN_INFO_RESOURCE_PATH = "hyproxy-plugin.json";
    private final HyProxy proxy;

    public void loadPlugins(Path directory) throws IOException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory, p -> p.toFile().isFile() && p.toString().endsWith(".jar"))) {
            for (Path path : stream) {
                try {
                    URLClassLoader classLoader = new URLClassLoader(new URL[]{path.toUri().toURL()}, HyProxy.class.getClassLoader());
                    InputStream pluginInfoStream = classLoader.getResourceAsStream(PLUGIN_INFO_RESOURCE_PATH);

                    if (pluginInfoStream == null) {
                        log.warn("couldn't find hyproxy plugin manifest in {}", path.getFileName());
                        continue;
                    }

                    PluginInfo info = GSON.fromJson(new InputStreamReader(pluginInfoStream, StandardCharsets.UTF_8), PluginInfo.class);
                    log.info("loading plugin {}", info.id());

                    Class<? extends HyProxyPlugin> mainClass = (Class<? extends HyProxyPlugin>) Class.forName(info.mainClass(), true, classLoader);
                    Object main = mainClass.getConstructor().newInstance();

                    Method loadMethod = mainClass.getDeclaredMethod("load", HyProxy.class);
                    loadMethod.invoke(main, this.proxy);
                } catch (Throwable t) {
                    log.error("failed to load plugin {}", path.getFileName(), t);
                }
            }
        }
    }
}
