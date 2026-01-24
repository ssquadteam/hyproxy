package town.kibty.hyproxy.player;

import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.command.CommandSender;
import town.kibty.hyproxy.common.util.SecretMessageUtil;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.impl.ClientReferral;
import town.kibty.hyproxy.io.packet.impl.game.ServerMessage;
import town.kibty.hyproxy.io.proto.ClientType;
import town.kibty.hyproxy.io.proto.DisconnectType;
import town.kibty.hyproxy.message.Message;
import town.kibty.hyproxy.player.permission.PlayerPermissionProvider;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Getter
@RequiredArgsConstructor
public class HyProxyPlayer implements CommandSender {
    private final HyProxy proxy;
    private final HytaleConnection inboundConnection;
    @Setter
    private HytaleConnection outboundConnection;

    // player info
    @Setter
    private int protocolCrc;
    @Setter
    private int protocolBuildNumber;
    @Setter
    private String clientVersion;
    @Setter
    private UUID profileId;
    @Setter
    private String username;
    @Setter
    private String identityToken;
    @Setter
    private String language;
    @Setter
    private ClientType clientType;
    @Setter
    private @Nullable HyProxyBackend referredBackend;

    @Setter
    private boolean authenticated = false;

    private @Nullable HyProxyBackend connectedBackend;

    public Set<String> getPermissions() {
        Set<String> allPermissions = new HashSet<>();
        for (PlayerPermissionProvider provider : proxy.getPlayerPermissionProviders()) {
            allPermissions.addAll(provider.getPlayerPermissions(this));
        }

        return ImmutableSet.copyOf(allPermissions);
    }

    public void sendPlayerToBackend(HyProxyBackend backend) {
        log.info("{} is connecting to backend {}", this.getIdentifier(), backend.getInfo().id());

        byte[] referralData = SecretMessageUtil.generateReferralData(new SecretMessageUtil.BackendReferralMessage(
                this.profileId,
                backend.getInfo().id(),
                Instant.now().getEpochSecond()
        ), proxy.getConfiguration().getProxySecret());


        this.sendToPlayer(new ClientReferral(proxy.getConfiguration().getPublicIp(), referralData));
    }

    public String getIdentifier() {
        return String.format("%s (%s)", this.getUsername(), this.getProfileId());
    }

    public void sendToPlayer(Packet packet) {
        if (!inboundConnection.getChannel().isActive()) {
            throw new IllegalStateException("tried sending player packet while inbound connection isn't active");
        }

        inboundConnection.send(packet);
    }

    public void sendAsPlayer(Packet packet) {
        if (!hasActiveOutboundConnection()) {
            throw new IllegalStateException("tried sending packet as player while outbound channel isn't active");
        }

        outboundConnection.send(packet);
    }

    public void setConnectedBackend(HyProxyBackend backend) {
        if (this.connectedBackend != null) {
            throw new IllegalStateException("cannot call setConnectedBackend more then once, use sendPlayerToBackend to transfer players to other servers");
        }

        this.connectedBackend = backend;
        this.connectedBackend.registerPlayer(this);

        // cleanup so we dont have a old backend instance laying around
        this.referredBackend = null;
    }

    public void disconnect(String message) {
        this.disconnect(message, DisconnectType.DISCONNECT);
    }

    public void disconnect(String message, DisconnectType type) {
        this.inboundConnection.disconnect(message, type); // this will disconnect the outbound connection too
    }

    public void onDisconnect() {
        proxy.unregisterPlayer(this);
        if (this.connectedBackend != null) {
            this.connectedBackend.unregisterPlayer(this);
        }
    }

    public boolean hasActiveOutboundConnection() {
        return this.outboundConnection != null && this.outboundConnection.getChannel().isActive() && this.connectedBackend != null;
    }

    public boolean hasActiveInboundConnection() {
        return this.inboundConnection.getChannel().isActive();
    }

    public boolean isActive() {
        return hasActiveOutboundConnection() && hasActiveInboundConnection() && this.authenticated;
    }

    @Override
    public void sendMessage(Message message) {
        this.inboundConnection.send(new ServerMessage((byte) 0, message.getFormatted()));
    }

    @Override
    public boolean performCommand(String command) {
        return proxy.getCommandManager().performCommand(this, command);
    }

    @Override
    public boolean hasPermission(String permission) {
        return this.getPermissions().contains(permission);
    }
}
