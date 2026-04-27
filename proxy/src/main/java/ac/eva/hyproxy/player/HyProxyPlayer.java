package ac.eva.hyproxy.player;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.io.BackendConnector;
import ac.eva.hyproxy.common.util.SecretMessageUtil;
import ac.eva.hyproxy.event.impl.player.PlayerSentToBackendEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.handler.outbound.OutboundEmptyPacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.ClientReferral;
import ac.eva.hyproxy.io.packet.impl.game.ServerMessage;
import ac.eva.hyproxy.io.proto.ClientType;
import ac.eva.hyproxy.io.proto.DisconnectType;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.permission.PlayerPermissionProvider;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
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
    @Setter
    private @Nullable HytaleConnection pendingOutboundConnection;

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
    private @Nullable HyProxyBackend pendingSeamlessBackend;
    private boolean seamlessSwitching = false;
    private boolean suppressNextJoinWorld = false;
    private final Deque<Integer> rawPongIds = new ArrayDeque<>();
    private final Deque<Integer> directPongIds = new ArrayDeque<>();
    private final Deque<Integer> tickPongIds = new ArrayDeque<>();

    /**
     * sends a player to another backend. this will send the client a referral with special referral data
     * that makes the proxy refer them to a different backend.
     * fires {@link PlayerSentToBackendEvent}
     * @param backend the new backend
     */
    public void sendPlayerToBackend(HyProxyBackend backend) {
        if (!this.seamlessSwitching && this.connectedBackend == backend) {
            log.info("{} is already connected to backend {}, ignoring switch request", this.getIdentifier(), backend.getInfo().id());
            return;
        }

        if (this.seamlessSwitching) {
            String pendingBackendId = this.pendingSeamlessBackend != null ? this.pendingSeamlessBackend.getInfo().id() : "<unknown>";
            if (this.pendingSeamlessBackend == backend) {
                log.info("{} already has a seamless backend handoff to {} in progress, ignoring duplicate switch request", this.getIdentifier(), pendingBackendId);
            } else {
                log.warn("{} ignoring backend switch to {} while seamless handoff to {} is in progress", this.getIdentifier(), backend.getInfo().id(), pendingBackendId);
            }
            return;
        }

        PlayerSentToBackendEvent event = this.proxy.getEventBus().fire(new PlayerSentToBackendEvent(
                this,
                this.connectedBackend,
                backend,
                false
        ));

        if (event.isCanceled()) {
            return;
        }

        HyProxyBackend newBackend = event.getNewBackend();
        log.info("{} is connecting to backend {}", this.getIdentifier(), newBackend.getInfo().id());

        if (!this.hasActiveOutboundConnection()) {
            this.sendPlayerToBackendWithReferral(newBackend);
            return;
        }

        HytaleConnection oldOutboundConnection = this.outboundConnection;
        this.seamlessSwitching = true;
        this.suppressNextJoinWorld = true;
        this.pendingOutboundConnection = null;
        this.pendingSeamlessBackend = newBackend;
        log.info("{} is starting seamless backend handoff to {}", this.getIdentifier(), newBackend.getInfo().id());

        BackendConnector.connect(this.inboundConnection, newBackend, cause -> {
            if (!this.seamlessSwitching || this.connectedBackend == newBackend || this.pendingSeamlessBackend != newBackend) {
                log.warn("{} got a late seamless handoff failure for {}, ignoring it", this.getIdentifier(), newBackend.getInfo().id(), cause);
                return;
            }

            log.warn("{} failed seamless backend handoff to {}, falling back to referral", this.getIdentifier(), newBackend.getInfo().id(), cause);
            this.seamlessSwitching = false;
            this.suppressNextJoinWorld = false;
            this.pendingOutboundConnection = null;
            this.pendingSeamlessBackend = null;
            this.outboundConnection = oldOutboundConnection;
            this.sendPlayerToBackendWithReferral(newBackend);
        });
    }

    private void sendPlayerToBackendWithReferral(HyProxyBackend newBackend) {
        byte[] referralData = SecretMessageUtil.generateReferralData(new SecretMessageUtil.BackendReferralMessage(
                this.profileId,
                newBackend.getInfo().id(),
                Instant.now().getEpochSecond()
        ), proxy.getConfiguration().getProxySecret());

        if (this.outboundConnection != null) {
            this.outboundConnection.setPacketHandler(new OutboundEmptyPacketHandler());
        }

        this.sendToPlayer(new ClientReferral(proxy.getConfiguration().getPublicIp(), referralData));

        if (this.outboundConnection != null) {
            this.outboundConnection.disconnect("player sent to another backend");
        }
    }

    public String getIdentifier() {
        return String.format("%s (%s)", this.getUsername(), this.getProfileId());
    }

    /**
     * sends the player's inbound connection a packet using the default network channel
     * @param packet the packet to send
     */
    public void sendToPlayer(Packet packet) {
        this.sendToPlayer(NetworkChannel.DEFAULT, packet);
    }

    /**
     * sends the player's inbound connection a packet using the specified network channel
     * @param channel the channel to send to
     * @param packet the packet to send
     */
    public void sendToPlayer(NetworkChannel channel, Packet packet) {
        if (!hasActiveInboundConnection()) {
            throw new IllegalStateException("tried sending player packet while inbound connection isn't active");
        }

        inboundConnection.send(channel, packet);
    }

    /**
     * sends the player's outbound connection a packet using the default network channel
     * @param packet the packet to send
     */
    public void sendAsPlayer(Packet packet) {
       this.sendAsPlayer(NetworkChannel.DEFAULT, packet);
    }

    /**
     * sends the player's outbound connection a packet using the specified network channel
     * @param channel the channel to send to
     * @param packet the packet to send
     */
    public void sendAsPlayer(NetworkChannel channel, Packet packet) {
        if (!hasActiveOutboundConnection()) {
            throw new IllegalStateException("tried sending packet as player while outbound channel isn't active");
        }

        outboundConnection.send(channel, packet);
    }

    /**
     * internal: please don't call this!
     */
    public void setConnectedBackend(HyProxyBackend backend) {
        if (this.connectedBackend != null) {
            throw new IllegalStateException("cannot call setConnectedBackend more then once, use sendPlayerToBackend to transfer players to other servers");
        }

        this.connectedBackend = backend;
        this.connectedBackend.registerPlayer(this);

        // cleanup so we dont have a old backend instance laying around
        this.referredBackend = null;
    }

    /**
     * internal: please don't call this!
     */
    public void promotePendingOutboundConnection(HytaleConnection pendingConnection, HyProxyBackend backend) {
        if (this.pendingOutboundConnection != pendingConnection) {
            throw new IllegalStateException("tried to promote an outbound connection that is not pending");
        }

        HytaleConnection oldOutboundConnection = this.outboundConnection;
        HyProxyBackend oldBackend = this.connectedBackend;

        this.outboundConnection = pendingConnection;
        this.pendingOutboundConnection = null;
        this.pendingSeamlessBackend = null;
        this.connectedBackend = backend;
        this.clearForwardedBackendPings();
        this.connectedBackend.registerPlayer(this);
        this.referredBackend = null;

        if (oldBackend != null) {
            oldBackend.unregisterPlayer(this);
        }

        if (oldOutboundConnection != null && oldOutboundConnection != pendingConnection) {
            oldOutboundConnection.setPacketHandler(new OutboundEmptyPacketHandler());
            oldOutboundConnection.setPlayer(null);
            oldOutboundConnection.close();
        }
    }

    /**
     * internal: please don't call this!
     */
    public void clearPendingSeamlessHandoff(HytaleConnection pendingConnection) {
        if (this.pendingOutboundConnection != pendingConnection) {
            return;
        }

        this.pendingOutboundConnection = null;
        this.pendingSeamlessBackend = null;
        this.seamlessSwitching = false;
        this.suppressNextJoinWorld = false;
    }

    public synchronized void recordForwardedBackendPing(int id) {
        this.enqueuePongId(this.rawPongIds, id);
        this.enqueuePongId(this.directPongIds, id);
        this.enqueuePongId(this.tickPongIds, id);
    }

    public synchronized boolean consumeForwardedBackendPong(int id, int type) {
        Deque<Integer> pongIds = this.pongIdsForType(type);
        if (pongIds == null || pongIds.isEmpty()) {
            return false;
        }

        Integer expectedId = pongIds.peekFirst();
        if (expectedId == null || expectedId != id) {
            return false;
        }

        pongIds.removeFirst();
        return true;
    }

    private synchronized void clearForwardedBackendPings() {
        this.rawPongIds.clear();
        this.directPongIds.clear();
        this.tickPongIds.clear();
    }

    private void enqueuePongId(Deque<Integer> pongIds, int id) {
        pongIds.addLast(id);
        while (pongIds.size() > 64) {
            pongIds.removeFirst();
        }
    }

    private @Nullable Deque<Integer> pongIdsForType(int type) {
        return switch (type) {
            case 0 -> this.rawPongIds;
            case 1 -> this.directPongIds;
            case 2 -> this.tickPongIds;
            default -> null;
        };
    }

    public boolean isSeamlessSwitching() {
        return this.seamlessSwitching;
    }

    public boolean consumeSuppressNextJoinWorld() {
        if (!this.suppressNextJoinWorld) {
            return false;
        }

        this.suppressNextJoinWorld = false;
        this.seamlessSwitching = false;
        this.pendingSeamlessBackend = null;
        return true;
    }

    /**
     * disconnects the player with the disconnect type being a normal disconnect
     * @param message the disconnect message
     */
    public void disconnect(String message) {
        this.disconnect(message, DisconnectType.DISCONNECT);
    }

    /**
     * disconnects the player with the given disconnect type
     * @param message the disconnect message
     * @param type the disconnect type
     */
    public void disconnect(String message, DisconnectType type) {
        this.inboundConnection.disconnect(message, type); // this will disconnect the outbound connection too
    }

    /**
     * internal: please don't call this!
     */
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

    /**
     * performs a proxy command as the player
     * <br /><br />
     * note: this will <b>not</b> execute backend commands
     * @param command the command to perform
     * @return if the command was found (on the proxy) or not
     */
    @Override
    public boolean performCommand(String command) {
        return proxy.getCommandManager().performCommand(this, command);
    }

    /**
     * @return all the player's permissions
     */
    public Set<String> getPermissions() {
        Set<String> allPermissions = new HashSet<>();
        for (PlayerPermissionProvider provider : proxy.getPlayerPermissionProviders()) {
            allPermissions.addAll(provider.getPlayerPermissions(this));
        }

        return ImmutableSet.copyOf(allPermissions);
    }

    /**
     * checks if the player has a permission
     * @param permission the permission to check for
     * @return if the player has the permission or not
     */
    @Override
    public boolean hasPermission(String permission) {
        for (PlayerPermissionProvider provider : proxy.getPlayerPermissionProviders()) {
            if (provider.hasPermission(this, permission)) return true;
        }

        return false;
    }
}
