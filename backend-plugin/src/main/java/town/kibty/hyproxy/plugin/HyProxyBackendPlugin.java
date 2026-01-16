package town.kibty.hyproxy.plugin;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.packets.auth.AuthGrant;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.Config;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.jspecify.annotations.NonNull;
import town.kibty.hyproxy.common.util.ProxyCommunicationUtil;
import town.kibty.hyproxy.common.util.RandomUtil;
import town.kibty.hyproxy.common.util.SecretMessageUtil;
import town.kibty.hyproxy.plugin.command.HyProxyBackendCommand;
import town.kibty.hyproxy.plugin.config.HyProxyBackendConfig;

import java.nio.charset.StandardCharsets;

public class HyProxyBackendPlugin extends JavaPlugin {
    private final Config<HyProxyBackendConfig> config = this.withConfig("config", HyProxyBackendConfig.CODEC);

    public HyProxyBackendPlugin(@NonNull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        HytaleServer.get().getEventBus().register(
                EventPriority.FIRST,
                PlayerSetupConnectEvent.class,
                this::onPlayerSetupConnect
        );
        CommandManager.get().register(new HyProxyBackendCommand(this));

        this.config.save();
    }

    private void onPlayerSetupConnect(PlayerSetupConnectEvent event) {
        byte[] data = event.getReferralData();
        if (data  == null) {
            event.setCancelled(true);
            event.setReason("cannot direct join hyproxy backend");
            return;
        }

        try {
            ByteBuf buf = Unpooled.copiedBuffer(data);

            byte[] secret = getProxySecret();

            SecretMessageUtil.BackendPlayerInfoMessage message = SecretMessageUtil.validateAndDecodePlayerInfoReferral(
                    buf,
                    event.getUuid(),
                    event.getUsername(),
                    this.config.get().getBackendName(),
                    secret
            );

            if (message == null) {
                event.setCancelled(true);
                event.setReason("Invalid player info message (is your proxy secret valid?)");
                return;
            }

            getLogger().at(Level.INFO).log("successfully authenticated player {} with hyproxy (remoteAddress={})", message.remoteAddress());
        } catch (Throwable throwable) {
            event.setCancelled(true);
            event.setReason("Internal error while verifying player information");
        }
    }

    private byte[] getProxySecret() {
        byte[] proxySecret = System.getenv("HYPROXY_SECRET") != null ? System.getenv("HYPROXY_SECRET").getBytes(StandardCharsets.UTF_8) : null;

        if (proxySecret == null) {
            String configProxySecret = config.get().getProxySecret();

            if (configProxySecret == null) {
                return RandomUtil.generateSecureRandomString(32).getBytes(StandardCharsets.UTF_8);
            }

            proxySecret = config.get().getProxySecret().getBytes(StandardCharsets.UTF_8);
        }

        return proxySecret;
    }

    public void sendProxyMessage(ProxyCommunicationUtil.ProxyCommunicationMessage message) {
        this.sendProxyMessage(Universe.get().getPlayers().getFirst(), message);
    }
    public void sendProxyMessage(PlayerRef playerRef, ProxyCommunicationUtil.ProxyCommunicationMessage message) {
        this.sendProxyMessage(playerRef.getPacketHandler().getChannel(), message);
    }

    public void sendProxyMessage(Channel channel, ProxyCommunicationUtil.ProxyCommunicationMessage message) {
        channel.writeAndFlush(new AuthGrant(
                null,
                ProxyCommunicationUtil.serializeMessage(message)
        ));
    }
}
