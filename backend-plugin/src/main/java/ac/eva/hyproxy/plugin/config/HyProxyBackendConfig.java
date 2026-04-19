package ac.eva.hyproxy.plugin.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import ac.eva.hyproxy.common.util.RandomUtil;

public class HyProxyBackendConfig {
    private String proxySecret = RandomUtil.generateSecureRandomString(32);
    private String backendName = "main";

    public static final BuilderCodec<HyProxyBackendConfig> CODEC = BuilderCodec.builder(HyProxyBackendConfig.class, HyProxyBackendConfig::new)
            .append(
                    new KeyedCodec<>("ProxySecret", Codec.STRING),
                    (config, str) -> config.proxySecret = str,
                    (config) -> config.proxySecret
            ).add()
            .append(
                    new KeyedCodec<>("BackendName", Codec.STRING),
                    (config, str) -> config.backendName = str,
                    (config) -> config.backendName
            ).add()
            .build();

    public String getProxySecret() {
        return proxySecret;
    }

    public String getBackendName() {
        return backendName;
    }
}
