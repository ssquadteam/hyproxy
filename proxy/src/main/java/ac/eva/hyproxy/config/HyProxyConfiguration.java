package ac.eva.hyproxy.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.io.proto.HostAddress;
import ac.eva.hyproxy.util.AddressUtil;
import ac.eva.hyproxy.common.util.RandomUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Getter
@Setter
public class HyProxyConfiguration {
    private @Nullable String sessionToken;
    private @Nullable String identityToken;
    private byte[] proxySecret;

    private String bind;
    private String publicIp;
    private boolean ipv6Support;

    private String initialBackend;
    private boolean proxyCommunicationEnabled;

    private Map<String, String> backends;
    private Map<String, List<String>> permissions;

    public InetSocketAddress getBind() {
        return AddressUtil.parseAndResolveAddress(bind);
    }

    public HostAddress getPublicIp() {
        InetSocketAddress address = AddressUtil.parseAddress(publicIp);
        return new HostAddress(address.getHostString(), (short) address.getPort());
    }

    public List<String> getProfilePermissions(UUID profileId) {
        if (!this.permissions.containsKey(profileId.toString())) {
            return Collections.emptyList();
        }

        return this.permissions.get(profileId.toString());
    }

    public boolean validate() {
        boolean valid = true;

        if (bind.isEmpty()) {
            log.error("bind is empty");
            valid = false;
        } else {
            try {
                AddressUtil.parseAddress(bind);
            } catch (IllegalArgumentException ex) {
                log.error("bind option doesn't specify a valid ip address and port", ex);
                valid = false;
            }
        }


        if (publicIp.isEmpty()) {
            log.error("public-ip is empty");
            valid = false;
        } else {
            try {
                AddressUtil.parseAddress(publicIp);
            } catch (IllegalArgumentException ex) {
                log.error("public-ip option doesn't specify a valid ip address and port", ex);
                valid = false;
            }
        }

        if (backends.isEmpty()) {
            log.warn("you don't have any backends configured");
        }

        for (Map.Entry<String, String> backendEntry : backends.entrySet()) {
            try {
                AddressUtil.parseAddress(backendEntry.getValue());
            } catch (IllegalArgumentException ex) {
                log.error("backend {} does not have a valid ip address", backendEntry.getKey(), ex);
                valid = false;
            }
        }

        if (!backends.containsKey(initialBackend)) {
            log.error("initial backend isn't a valid backend id");
            valid = false;
        }

        for (Map.Entry<String, List<String>> entry : permissions.entrySet()) {
            try {
                UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException ex) {
                log.error("permission user entry {} isn't a valid uuid", entry.getKey());
                valid = false;
            }
        }

        return valid;
    }

    public static HyProxyConfiguration load(HyProxy proxy, Path configFilePath) throws IOException {
        URL defaultConfigLocation = HyProxyConfiguration.class.getClassLoader().getResource("default-config.toml");

        if (defaultConfigLocation == null) {
            throw new RuntimeException("failed to find default config resource");
        }

        try (final CommentedFileConfig config = CommentedFileConfig.builder(configFilePath)
                .defaultData(defaultConfigLocation)
                .autosave()
                .preserveInsertionOrder()
                .sync()
                .build()
        ) {
            config.load();

            String sessionToken = System.getProperty("hyproxy.auth.sessionToken");
            if (sessionToken == null) {
                sessionToken = System.getenv("HYPROXY_SESSION_TOKEN");
            }

            String identityToken = System.getProperty("hyproxy.auth.identityToken");
            if (identityToken == null) {
                identityToken = System.getenv("HYPROXY_IDENTITY_TOKEN");
            }

            /*
            if (sessionToken == null || identityToken == null) {
                log.error("couldn't find session and/or identity token in properties or environment");
                log.error("to run a hyproxy instance, you need a hytale session token and identity token pair");
                log.info("and set either the property hyproxy.auth.sessionToken and hyproxy.auth.identityToken");
                log.error("or HYPROXY_SESSION_TOKEN and HYPROXY_IDENTITY_TOKEN.");
                proxy.shutdown(true);
                throw new IllegalStateException("unreachable, we called stop");
            }
             */

            byte[] proxySecret = System.getenv("HYPROXY_SECRET") != null ? System.getenv("HYPROXY_SECRET").getBytes(StandardCharsets.UTF_8) : null;
            if (proxySecret == null) {
                String proxySecretFile = config.get("proxy-secret-file");
                Path secretPath = proxySecretFile != null ? Path.of(proxySecretFile) : Path.of("proxy.secret");

                if (Files.exists(secretPath)) {
                    if (Files.isRegularFile(secretPath)) {
                        proxySecret = Files.readAllBytes(secretPath);
                    } else {
                        log.error("proxy secret file isn't a regular file (is it a directory?)");
                        proxy.shutdown(true);
                        throw new IllegalStateException("unreachable, we called shutdown");
                    }
                } else {
                    Files.createFile(secretPath);
                    proxySecret = RandomUtil.generateSecureRandomString(32).getBytes(StandardCharsets.UTF_8);
                    Files.write(secretPath, proxySecret);
                    log.info("a new proxy secret file has been generated as none was found. it is located at {}", secretPath);
                }
            }

            String bind = config.getOrElse("bind", "0.0.0.0:5520");
            String publicIp = config.getOrElse("public-ip", "127.0.0.1:5520");

            boolean ipv6Support = config.getOrElse("ipv6-support", true);

            String initialBackend = config.getOrElse("initial-backend", "main");
            boolean proxyCommunicationEnabled = config.getOrElse("proxy-communication", true);

            CommentedConfig backendConfig = config.get("backends");
            Map<String, String> backends = backendConfig.valueMap()
                    .entrySet()
                    .stream()
                    .map(a -> Map.entry(a.getKey(), (String) a.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            CommentedConfig permissionsConfig = config.get("permissions");
            @SuppressWarnings("unchecked") Map<String, List<String>> permissions = permissionsConfig.valueMap()
                    .entrySet()
                    .stream()
                    .map(a -> Map.entry(a.getKey(), (List<String>) a.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            return new HyProxyConfiguration(
                    sessionToken,
                    identityToken,
                    proxySecret,
                    bind,
                    publicIp,
                    ipv6Support,
                    initialBackend,
                    proxyCommunicationEnabled,
                    backends,
                    permissions
            );
        }
    }
}
