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
import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import ac.eva.hyproxy.io.packet.impl.ClientReferral;
import ac.eva.hyproxy.io.packet.impl.game.ServerMessage;
import ac.eva.hyproxy.io.proto.ClientDisconnectReason;
import ac.eva.hyproxy.io.proto.ClientType;
import ac.eva.hyproxy.io.proto.DisconnectType;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.permission.PlayerPermissionProvider;
import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Slf4j
@Getter
@RequiredArgsConstructor
public class HyProxyPlayer implements CommandSender {
    private static final int MAX_TRACKED_BACKEND_ENTITY_IDS = 65536;
    public static final int SEAMLESS_STAGE_CONNECTING = 0;
    public static final int SEAMLESS_STAGE_FORWARDING = 1;
    public static final int SEAMLESS_STAGE_WORLD_SETTINGS = 2;
    public static final int SEAMLESS_STAGE_WORLD_LOAD_FINISHED = 3;
    public static final int SEAMLESS_STAGE_JOIN_WORLD = 4;
    public static final int SEAMLESS_STAGE_PROMOTED = 5;
    private static final long SEAMLESS_WORLD_SETTINGS_TIMEOUT_MILLIS = 1_200L;
    private static final long SEAMLESS_JOIN_WORLD_TIMEOUT_MILLIS = 3_000L;

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
    private boolean pendingSeamlessGameplayReady = false;
    private long seamlessHandoffSequence = 0;
    private int seamlessHandoffStage = -1;
    private String seamlessHandoffStageName = "idle";
    @Setter
    private int currentClientEntityId = -1;
    private final Set<Integer> trackedBackendEntityIds = new HashSet<>();
    private @Nullable SeamlessTransferCorrection pendingSeamlessCorrection;
    private byte @Nullable [] latestViewRadiusPacket;
    private byte @Nullable [] latestPlayerOptionsPacket;
    private final Deque<Integer> rawPongIds = new ArrayDeque<>();
    private final Deque<Integer> directPongIds = new ArrayDeque<>();
    private final Deque<Integer> tickPongIds = new ArrayDeque<>();
    private final Deque<Byte> syntheticTeleportAckIds = new ArrayDeque<>();
    private byte nextSyntheticTeleportId = Byte.MIN_VALUE;

    /**
     * sends a player to another backend. this will send the client a referral with special referral data
     * that makes the proxy refer them to a different backend.
     * fires {@link PlayerSentToBackendEvent}
     * @param backend the new backend
     */
    public void sendPlayerToBackend(HyProxyBackend backend) {
        this.sendPlayerToBackend(backend, null);
    }

    public void sendPlayerToBackend(HyProxyBackend backend, @Nullable SeamlessTransferCorrection correction) {
        if (!this.seamlessSwitching && this.connectedBackend == backend) {
            log.info("{} is already connected to backend {}, ignoring switch request", this.getIdentifier(), backend.getInfo().id());
            return;
        }

        if (this.seamlessSwitching) {
            String pendingBackendId = this.pendingSeamlessBackend != null ? this.pendingSeamlessBackend.getInfo().id() : "<unknown>";
            if (this.pendingSeamlessBackend == backend) {
                this.refreshPendingSeamlessCorrection(backend, correction);
                log.debug("{} already has a seamless backend handoff to {} in progress, refreshed latest transfer state", this.getIdentifier(), pendingBackendId);
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
            this.pendingSeamlessCorrection = null;
            this.pendingSeamlessGameplayReady = false;
            this.sendPlayerToBackendWithReferral(newBackend);
            return;
        }

        HytaleConnection oldOutboundConnection = this.outboundConnection;
        this.seamlessSwitching = true;
        this.suppressNextJoinWorld = true;
        long handoffSequence = ++this.seamlessHandoffSequence;
        this.pendingOutboundConnection = null;
        this.pendingSeamlessBackend = newBackend;
        this.pendingSeamlessCorrection = correction;
        this.pendingSeamlessGameplayReady = false;
        this.seamlessHandoffStage = SEAMLESS_STAGE_CONNECTING;
        this.seamlessHandoffStageName = "connecting";
        log.info("{} is starting seamless backend handoff to {}", this.getIdentifier(), newBackend.getInfo().id());

        BackendConnector.connect(this.inboundConnection, newBackend, cause -> {
            if (this.seamlessHandoffSequence != handoffSequence
                    || !this.seamlessSwitching
                    || this.connectedBackend == newBackend
                    || this.pendingSeamlessBackend != newBackend) {
                log.warn("{} got a late seamless handoff failure for {}, ignoring it", this.getIdentifier(), newBackend.getInfo().id(), cause);
                return;
            }

            this.abortPendingSeamlessHandoff(newBackend, oldOutboundConnection, "connection failure", cause);
        });

        this.scheduleSeamlessHandoffTimeout(
                handoffSequence,
                newBackend,
                oldOutboundConnection,
                SEAMLESS_STAGE_WORLD_SETTINGS,
                "WorldSettings",
                SEAMLESS_WORLD_SETTINGS_TIMEOUT_MILLIS
        );
        this.scheduleSeamlessHandoffTimeout(
                handoffSequence,
                newBackend,
                oldOutboundConnection,
                SEAMLESS_STAGE_JOIN_WORLD,
                "JoinWorld",
                SEAMLESS_JOIN_WORLD_TIMEOUT_MILLIS
        );
    }

    private void scheduleSeamlessHandoffTimeout(
            long handoffSequence,
            HyProxyBackend newBackend,
            HytaleConnection oldOutboundConnection,
            int requiredStage,
            String requiredStageName,
            long timeoutMillis
    ) {
        this.inboundConnection.getChannel().eventLoop().schedule(() -> {
            if (this.seamlessHandoffSequence != handoffSequence
                    || !this.seamlessSwitching
                    || this.connectedBackend == newBackend
                    || this.pendingSeamlessBackend != newBackend) {
                return;
            }

            String currentStageName;
            synchronized (this) {
                if (this.seamlessHandoffStage >= requiredStage) {
                    return;
                }
                currentStageName = this.seamlessHandoffStageName;
            }

            this.abortPendingSeamlessHandoff(
                    newBackend,
                    oldOutboundConnection,
                    "timed out waiting for " + requiredStageName + " (stage=" + currentStageName + ")",
                    null
            );
        }, timeoutMillis, TimeUnit.MILLISECONDS);
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
        this.seamlessHandoffStage = SEAMLESS_STAGE_PROMOTED;
        this.seamlessHandoffStageName = "promoted";
        this.connectedBackend.registerPlayer(this);
        this.referredBackend = null;

        if (oldBackend != null) {
            oldBackend.unregisterPlayer(this);
        }

        if (oldOutboundConnection != null && oldOutboundConnection != pendingConnection) {
            this.closeReplacedOutboundConnection(oldOutboundConnection);
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
        this.pendingSeamlessCorrection = null;
        this.pendingSeamlessGameplayReady = false;
        this.seamlessHandoffStage = -1;
        this.seamlessHandoffStageName = "idle";
    }

    public void abortPendingSeamlessHandoff(
            HyProxyBackend backend,
            HytaleConnection oldOutboundConnection,
            String reason,
            @Nullable Throwable cause
    ) {
        if (!this.seamlessSwitching || this.pendingSeamlessBackend != backend) {
            return;
        }

        HytaleConnection pendingConnection = this.pendingOutboundConnection;
        this.outboundConnection = oldOutboundConnection;
        this.pendingOutboundConnection = null;
        this.pendingSeamlessBackend = null;
        this.seamlessSwitching = false;
        this.suppressNextJoinWorld = false;
        this.pendingSeamlessCorrection = null;
        this.pendingSeamlessGameplayReady = false;
        this.seamlessHandoffStage = -1;
        this.seamlessHandoffStageName = "idle";

        if (pendingConnection != null && pendingConnection != oldOutboundConnection) {
            pendingConnection.setPacketHandler(new OutboundEmptyPacketHandler());
            pendingConnection.setPlayer(null);
            pendingConnection.closeApplication();
        }

        if (cause == null) {
            log.warn("{} aborted seamless backend handoff to {} after {}", this.getIdentifier(), backend.getInfo().id(), reason);
        } else {
            log.warn("{} aborted seamless backend handoff to {} after {}", this.getIdentifier(), backend.getInfo().id(), reason, cause);
        }
    }

    public synchronized void refreshPendingSeamlessCorrection(HyProxyBackend backend, @Nullable SeamlessTransferCorrection correction) {
        if (!this.seamlessSwitching || this.pendingSeamlessBackend != backend || correction == null) {
            return;
        }

        this.pendingSeamlessCorrection = correction;
    }

    public synchronized void markSeamlessHandoffStage(
            HyProxyBackend backend,
            HytaleConnection pendingConnection,
            int stage,
            String stageName
    ) {
        if (!this.seamlessSwitching
                || this.pendingSeamlessBackend != backend
                || this.pendingOutboundConnection != pendingConnection
                || stage <= this.seamlessHandoffStage) {
            return;
        }

        this.seamlessHandoffStage = stage;
        this.seamlessHandoffStageName = stageName;
    }

    public synchronized void rememberLatestViewRadiusPacket(ByteBuf packet) {
        this.latestViewRadiusPacket = copyReadableBytes(packet);
    }

    public synchronized void rememberLatestPlayerOptionsPacket(ByteBuf packet) {
        this.latestPlayerOptionsPacket = copyReadableBytes(packet);
        log.info("{} captured PlayerOptions packet for seamless handoff replay (skin={}, bytes={})",
                this.getIdentifier(),
                playerOptionsPacketHasSkin(packet),
                packet.readableBytes());
    }

    public synchronized @Nullable ByteBuf copyLatestViewRadiusPacket() {
        return this.latestViewRadiusPacket == null ? null : Unpooled.wrappedBuffer(Arrays.copyOf(this.latestViewRadiusPacket, this.latestViewRadiusPacket.length));
    }

    public synchronized @Nullable ByteBuf copyLatestPlayerOptionsPacket() {
        return this.latestPlayerOptionsPacket == null ? null : Unpooled.wrappedBuffer(Arrays.copyOf(this.latestPlayerOptionsPacket, this.latestPlayerOptionsPacket.length));
    }

    public synchronized void rememberTrackedBackendEntityId(int entityId) {
        if (this.trackedBackendEntityIds.size() >= MAX_TRACKED_BACKEND_ENTITY_IDS) {
            return;
        }

        this.trackedBackendEntityIds.add(entityId);
    }

    public synchronized Set<Integer> trackedBackendEntityIdsForRemoval() {
        Set<Integer> entityIds = new HashSet<>(this.trackedBackendEntityIds);
        if (this.currentClientEntityId >= 0) {
            entityIds.add(this.currentClientEntityId);
        }

        return entityIds;
    }

    public synchronized void clearTrackedBackendEntityIds() {
        this.trackedBackendEntityIds.clear();
    }

    public synchronized @Nullable SeamlessTransferCorrection consumePendingSeamlessCorrection() {
        SeamlessTransferCorrection correction = this.pendingSeamlessCorrection;
        this.pendingSeamlessCorrection = null;
        return correction;
    }

    public synchronized void queuePendingSeamlessGameplayReady() {
        this.pendingSeamlessGameplayReady = true;
    }

    public synchronized boolean consumePendingSeamlessGameplayReady() {
        if (!this.pendingSeamlessGameplayReady) {
            return false;
        }

        this.pendingSeamlessGameplayReady = false;
        return true;
    }

    public synchronized byte queueSyntheticTeleportAck() {
        byte teleportId = this.nextSyntheticTeleportId++;
        if (this.nextSyntheticTeleportId >= 0) {
            this.nextSyntheticTeleportId = Byte.MIN_VALUE;
        }

        this.syntheticTeleportAckIds.addLast(teleportId);
        while (this.syntheticTeleportAckIds.size() > 8) {
            this.syntheticTeleportAckIds.removeFirst();
        }
        return teleportId;
    }

    public synchronized boolean consumeSyntheticTeleportAck(byte teleportId) {
        return this.syntheticTeleportAckIds.remove(teleportId);
    }

    private static byte[] copyReadableBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    private static boolean playerOptionsPacketHasSkin(ByteBuf packet) {
        return packet.readableBytes() >= 9 && (packet.getByte(packet.readerIndex() + 8) & 1) != 0;
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

    private void closeReplacedOutboundConnection(HytaleConnection oldOutboundConnection) {
        ChannelFuture future = oldOutboundConnection.send(new ClientDisconnect(
                ClientDisconnectReason.PLAYER_LEAVE,
                DisconnectType.DISCONNECT
        ));

        oldOutboundConnection.setPacketHandler(new OutboundEmptyPacketHandler());
        oldOutboundConnection.setPlayer(null);

        if (future != null) {
            future.addListener(ignored -> oldOutboundConnection.closeApplication());
        } else {
            oldOutboundConnection.closeApplication();
        }
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

    public record SeamlessTransferCorrection(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float roll
    ) {
    }
}
