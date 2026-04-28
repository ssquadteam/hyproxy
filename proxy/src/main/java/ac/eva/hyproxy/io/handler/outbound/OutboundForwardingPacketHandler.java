package ac.eva.hyproxy.io.handler.outbound;

import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.common.communication.ProxyCommunicationMessage;
import ac.eva.hyproxy.common.util.ProxyCommunicationUtil;
import ac.eva.hyproxy.common.util.VarIntUtil;
import ac.eva.hyproxy.event.impl.proxy.ProxyCommunicationMessageEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.auth.AuthGrant;
import ac.eva.hyproxy.io.packet.impl.setup.ServerInfo;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.HyProxyPlayer;
import com.github.luben.zstd.Zstd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class OutboundForwardingPacketHandler implements HytalePacketHandler {
    private static final int PING_PACKET_ID = 3;
    private static final int WORLD_SETTINGS_PACKET_ID = 20;
    private static final int WORLD_LOAD_FINISHED_PACKET_ID = 22;
    private static final int REQUEST_ASSETS_PACKET_ID = 23;
    private static final int VIEW_RADIUS_PACKET_ID = 32;
    private static final int PLAYER_OPTIONS_PACKET_ID = 33;
    private static final int SET_CLIENT_ID_PACKET_ID = 100;
    private static final int JOIN_WORLD_PACKET_ID = 104;
    private static final int CLIENT_READY_PACKET_ID = 105;
    private static final int CLIENT_TELEPORT_PACKET_ID = 109;
    private static final int SET_CHUNK_PACKET_ID = 131;
    private static final int SET_CHUNK_HEIGHTMAP_PACKET_ID = 132;
    private static final int SET_CHUNK_TINTMAP_PACKET_ID = 133;
    private static final int SET_CHUNK_ENVIRONMENTS_PACKET_ID = 134;
    private static final int UNLOAD_CHUNK_PACKET_ID = 135;
    private static final int ENTITY_UPDATES_PACKET_ID = 161;
    private static final int DEFAULT_VIEW_RADIUS = 192;
    private static final int SHARD_ENTITY_ID_RANGE_SIZE = 1_000_000;
    private static final int MAX_TRACKED_ENTITY_UPDATES_PAYLOAD_SIZE = 16 * 1024 * 1024;
    private static final int MAX_PROTOCOL_ARRAY_COUNT = 4_096_000;
    private static final int SHARDTALE_HANDOFF_VERSION = 1;
    private static final int SHARDTALE_HANDOFF_PAYLOAD_BYTES = 26;
    private static final int SHARDTALE_PREWARM_PAYLOAD_BYTES = 27;
    private static final int SHARDTALE_PREWARM_MODE = 1;
    private static final boolean SEAMLESS_PREWARM_ENABLED = false;
    private static final long SEAMLESS_SETUP_KICK_DELAY_MILLIS = 300L;

    private final HytaleConnection connection;
    private final HyProxyBackend backend;
    private boolean pendingSetupKickSent = false;
    private boolean pendingWorldSettingsReceived = false;
    private boolean pendingPrewarmChunkForwardLogged = false;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        if (player.getPendingOutboundConnection() == connection) {
            log.info("{} started seamless backend {} with {}",
                    player.getIdentifier(),
                    player.isSeamlessPrewarming() ? "prewarm" : "setup",
                    backend.getInfo().id());
            player.markSeamlessHandoffStage(
                    backend,
                    connection,
                    HyProxyPlayer.SEAMLESS_STAGE_FORWARDING,
                    "forwarding"
            );
            this.scheduleSeamlessSetupKick(player);
            return;
        }

        player.setConnectedBackend(backend);
    }

    @Override
    public void handleGeneric(NetworkChannel channel, Packet packet) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (this.isPendingSeamlessHandoff(player)) return;
        if (!player.hasActiveInboundConnection()) return;
        player.sendToPlayer(channel, packet);
    }

    @Override
    public void handleUnknown(NetworkChannel channel, ByteBuf buf) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (this.isPendingSeamlessHandoff(player)) {
            this.handlePendingSeamlessPacket(channel, buf, player);
            return;
        }

        if (!player.hasActiveInboundConnection()) return;

        int packetId = packetId(buf);
        if (channel == NetworkChannel.DEFAULT && packetId == PING_PACKET_ID) {
            player.recordForwardedBackendPing(pingId(buf));
        } else if (channel == NetworkChannel.DEFAULT && packetId == SET_CLIENT_ID_PACKET_ID) {
            int clientEntityId = clientEntityId(buf);
            if (clientEntityId != Integer.MIN_VALUE) {
                if (player.getCurrentClientEntityId() != clientEntityId) {
                    log.info("{} tracked client entity id {} from backend {}", player.getIdentifier(), clientEntityId, backend.getInfo().id());
                }
                player.setCurrentClientEntityId(clientEntityId);
            }
        }
        if (channel == NetworkChannel.DEFAULT && packetId == ENTITY_UPDATES_PACKET_ID) {
            this.trackBackendEntityIdsFromEntityUpdates(player, buf);
        }

        if (channel == NetworkChannel.DEFAULT && player.isSeamlessSwitching() && packetId == JOIN_WORLD_PACKET_ID) {
            if (player.getCurrentClientEntityId() < 0) {
                log.warn("{} has no tracked client entity id, forwarding clear/no-fade JoinWorld during seamless backend handoff to {}",
                        player.getIdentifier(),
                        backend.getInfo().id());
                player.consumeSuppressNextJoinWorld();
                player.clearTrackedBackendEntityIds();
                player.consumePendingSeamlessCorrection();
                player.queuePendingSeamlessGameplayReady();
                player.getInboundConnection().write(channel, sanitizedJoinWorld(buf, true));
                this.sendClientReadyForChunks();
                return;
            }

            log.info("{} suppressed JoinWorld during seamless backend handoff to {}", player.getIdentifier(), backend.getInfo().id());
            Set<Integer> staleEntityIds = player.trackedBackendEntityIdsForRemoval();
            player.consumeSuppressNextJoinWorld();
            this.sendStaleEntityRemoval(player, staleEntityIds);
            player.clearTrackedBackendEntityIds();
            player.queuePendingSeamlessGameplayReady();
            this.sendClientReadyForChunks();
            return;
        }

        player.getInboundConnection().write(channel, buf.retain());
        if (channel == NetworkChannel.DEFAULT && packetId == SET_CLIENT_ID_PACKET_ID) {
            this.finishSeamlessClientActivation(player);
        }
    }

    // this should never happen in normal conditions, but we make it happen in the backend plugin to have a way for the backend
    // to communicate with the proxy.
    // the backend sends a AuthGrant packet in play/setup state, with the server identity token set to hyProxy.<base64_payload>
    // which we then decode into our own, or fire an event for plugins to listen to for unknown ones
    @Override
    public boolean handle(AuthGrant passwordResponse) {
        HyProxy proxy = connection.getProxy();
        HyProxyPlayer player = connection.ensurePlayer();

        if (!proxy.getConfiguration().isProxyCommunicationEnabled()) return false;
        if (passwordResponse.getServerIdentityToken() == null) return false;

        ProxyCommunicationMessage message = ProxyCommunicationUtil.deserializeMessage(passwordResponse.getServerIdentityToken());

        if (message == null) return false;
        if (this.isPendingSeamlessHandoff(player)) {
            log.debug("{} suppressed proxy communication from hidden backend {}", player.getIdentifier(), backend.getInfo().id());
            return true;
        }

        // todo: move this somewhere else
        switch (message) {
            case ProxyCommunicationMessage.SendToBackend sendToBackend -> {
                HyProxyPlayer target = proxy.getPlayerByProfileId(sendToBackend.targetId());
                HyProxyPlayer sender = proxy.getPlayerByProfileId(sendToBackend.senderId());

                if (target == null) {
                    if (sender != null) {
                        sender.sendMessage(Message.raw("The target isn't online on the network").color(HyProxyColors.ERROR_COLOR));
                    }

                    break;
                }

                HyProxyBackend targetBackend = proxy.getBackendById(sendToBackend.backendId());
                if (targetBackend == null) {
                    if (sender != null) {
                        sender.sendMessage(Message.raw("The specified backend id doesn't exist").color(HyProxyColors.ERROR_COLOR));
                    }

                    break;
                }

                target.sendPlayerToBackend(targetBackend);
                if (sender != null) {
                    sender.sendMessage(Message.empty()
                            .insert(Message.raw("Successfully sent ").color(HyProxyColors.SECONDARY_COLOR))
                            .insert(Message.raw(target.getUsername()).color(HyProxyColors.PRIMARY_COLOR))
                            .insert(Message.raw(" to backend ").color(HyProxyColors.SECONDARY_COLOR))
                            .insert(Message.raw(targetBackend.getInfo().id()).color(HyProxyColors.PRIMARY_COLOR))
                    );
                }
            }
            case ProxyCommunicationMessage.Unknown msg -> {
                if (this.handleShardTaleHandoffMessage(proxy, msg.data())) {
                    return true;
                }

                ProxyCommunicationMessageEvent event = proxy.getEventBus().fire(new ProxyCommunicationMessageEvent(
                        msg,
                        false
                ));

                if (event.isHandled()) {
                    return true;
                }

                log.warn("backend sent unknown proxy communication message but no plugin handled it");
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean handle(ServerInfo serverInfo) {
        HyProxyPlayer player = connection.ensurePlayer();
        if (this.isPendingSeamlessHandoff(player)) {
            return true;
        }

        String serverName = serverInfo.getServerName();
        if (serverName == null || serverName.endsWith(" (hyproxy)")) {
            return false;
        }

        player.sendToPlayer(new ServerInfo(
                serverName + " (hyproxy)",
                serverInfo.getMotd(),
                serverInfo.getFallbackServer(),
                serverInfo.getMaxPlayers()
        ));
        return true;
    }


    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (player.getPendingOutboundConnection() == connection) {
            log.warn("{} pending seamless backend setup to {} disconnected", player.getIdentifier(), backend.getInfo().id());
            player.clearPendingSeamlessHandoff(connection);
            return;
        }

        if (player.getOutboundConnection() != connection) return;
        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().close();
    }

    private boolean isPendingSeamlessHandoff(HyProxyPlayer player) {
        return (player.isSeamlessSwitching() || player.isSeamlessPrewarming()) && player.getPendingOutboundConnection() == connection;
    }

    private void handlePendingSeamlessPacket(NetworkChannel channel, ByteBuf buf, HyProxyPlayer player) {
        int packetId = packetId(buf);
        if (channel == NetworkChannel.CHUNKS) {
            if (SEAMLESS_PREWARM_ENABLED && player.isSeamlessPrewarming() && player.isSeamlessPrewarmReady() && isPrewarmChunkPacket(packetId)) {
                if (player.hasActiveInboundConnection()) {
                    if (!this.pendingPrewarmChunkForwardLogged) {
                        this.pendingPrewarmChunkForwardLogged = true;
                        log.info("{} started forwarding target chunk packets from prewarmed backend {}",
                                player.getIdentifier(),
                                backend.getInfo().id());
                    }
                    player.getInboundConnection().write(channel, buf.retain());
                }
                return;
            }

            if (packetId == UNLOAD_CHUNK_PACKET_ID) {
                return;
            }

            return;
        }

        if (channel != NetworkChannel.DEFAULT) {
            return;
        }

        switch (packetId) {
            case PING_PACKET_ID -> this.sendSyntheticPongs(pingId(buf));
            case WORLD_SETTINGS_PACKET_ID -> {
                this.pendingWorldSettingsReceived = true;
                player.markSeamlessHandoffStage(
                        backend,
                        connection,
                        HyProxyPlayer.SEAMLESS_STAGE_WORLD_SETTINGS,
                        "WorldSettings"
                );
                log.info("{} received target WorldSettings during seamless backend setup for {}", player.getIdentifier(), backend.getInfo().id());
                this.sendSeamlessSetupKick();
            }
            case WORLD_LOAD_FINISHED_PACKET_ID -> {
                player.markSeamlessHandoffStage(
                        backend,
                        connection,
                        HyProxyPlayer.SEAMLESS_STAGE_WORLD_LOAD_FINISHED,
                        "WorldLoadFinished"
                );
                log.info("{} received target WorldLoadFinished during seamless backend setup for {}", player.getIdentifier(), backend.getInfo().id());
                this.sendPlayerOptions(player);
            }
            case SET_CLIENT_ID_PACKET_ID -> {
                if (player.isSeamlessPrewarming()) {
                    player.rememberPendingSeamlessSetClientId(buf, clientEntityId(buf));
                }
            }
            case JOIN_WORLD_PACKET_ID -> {
                player.markSeamlessHandoffStage(
                        backend,
                        connection,
                        HyProxyPlayer.SEAMLESS_STAGE_JOIN_WORLD,
                        "JoinWorld"
                );
                if (player.isSeamlessPrewarming()) {
                    player.markSeamlessPrewarmReady(backend, connection);
                    log.info("{} suppressed JoinWorld and started seamless chunk prewarm from {}",
                            player.getIdentifier(),
                            backend.getInfo().id());
                    this.sendClientReadyForChunks();
                    return;
                }

                if (player.getCurrentClientEntityId() < 0) {
                    log.warn("{} has no tracked client entity id, forwarding clear/no-fade JoinWorld and promoted seamless backend handoff to {}",
                            player.getIdentifier(),
                            backend.getInfo().id());
                    ByteBuf joinWorld = sanitizedJoinWorld(buf, true);
                    player.promotePendingOutboundConnection(connection, backend);
                    player.consumeSuppressNextJoinWorld();
                    player.clearTrackedBackendEntityIds();
                    player.consumePendingSeamlessCorrection();
                    player.queuePendingSeamlessGameplayReady();
                    if (player.hasActiveInboundConnection()) {
                        player.getInboundConnection().write(channel, joinWorld);
                    } else {
                        joinWorld.release();
                    }
                } else {
                    log.info("{} suppressed JoinWorld and promoted seamless backend handoff to {}", player.getIdentifier(), backend.getInfo().id());
                    Set<Integer> staleEntityIds = player.trackedBackendEntityIdsForRemoval();
                    player.promotePendingOutboundConnection(connection, backend);
                    player.consumeSuppressNextJoinWorld();
                    this.sendStaleEntityRemoval(player, staleEntityIds);
                    player.clearTrackedBackendEntityIds();
                    player.queuePendingSeamlessGameplayReady();
                }
                this.sendClientReadyForChunks();
            }
            default -> {
            }
        }
    }

    private void scheduleSeamlessSetupKick(HyProxyPlayer player) {
        connection.getChannel().eventLoop().schedule(() -> {
            if (!this.isPendingSeamlessHandoff(player)
                    || this.pendingWorldSettingsReceived
                    || this.pendingSetupKickSent) {
                return;
            }

            log.warn("{} did not receive target WorldSettings quickly for {}; sending hidden setup kick",
                    player.getIdentifier(),
                    backend.getInfo().id());
            this.sendSeamlessSetupKick();
        }, SEAMLESS_SETUP_KICK_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sendSeamlessSetupKick() {
        if (this.pendingSetupKickSent) {
            return;
        }

        this.pendingSetupKickSent = true;
        this.sendViewRadius();
        this.sendRequestAssets();
    }

    private void sendViewRadius() {
        ByteBuf storedViewRadius = connection.ensurePlayer().copyLatestViewRadiusPacket();
        if (storedViewRadius != null) {
            connection.write(NetworkChannel.DEFAULT, storedViewRadius);
            return;
        }

        ByteBuf viewRadius = Unpooled.buffer(12);
        viewRadius.writeIntLE(4);
        viewRadius.writeIntLE(VIEW_RADIUS_PACKET_ID);
        viewRadius.writeIntLE(DEFAULT_VIEW_RADIUS);
        connection.write(NetworkChannel.DEFAULT, viewRadius);
    }

    private void sendRequestAssets() {
        byte[] compressedPayload = Zstd.compress(new byte[] { 1, 0 }, Zstd.defaultCompressionLevel());
        ByteBuf requestAssets = Unpooled.buffer(8 + compressedPayload.length);
        requestAssets.writeIntLE(compressedPayload.length);
        requestAssets.writeIntLE(REQUEST_ASSETS_PACKET_ID);
        requestAssets.writeBytes(compressedPayload);
        connection.write(NetworkChannel.DEFAULT, requestAssets);
    }

    private void sendPlayerOptions(HyProxyPlayer player) {
        ByteBuf storedPlayerOptions = player.copyLatestPlayerOptionsPacket();
        if (storedPlayerOptions != null) {
            log.info("{} replayed stored PlayerOptions to {} (skin={}, bytes={})",
                    player.getIdentifier(),
                    backend.getInfo().id(),
                    playerOptionsPacketHasSkin(storedPlayerOptions),
                    storedPlayerOptions.readableBytes());
            connection.write(NetworkChannel.DEFAULT, storedPlayerOptions);
            return;
        }

        log.warn("{} has no stored PlayerOptions packet, sending empty fallback to {}", player.getIdentifier(), backend.getInfo().id());
        ByteBuf playerOptions = Unpooled.buffer(9);
        playerOptions.writeIntLE(1);
        playerOptions.writeIntLE(PLAYER_OPTIONS_PACKET_ID);
        playerOptions.writeByte(0);
        connection.write(NetworkChannel.DEFAULT, playerOptions);
    }

    private void sendClientReadyForChunks() {
        connection.write(NetworkChannel.DEFAULT, clientReadyPacket(true, false));
    }

    private void sendClientReadyForGameplay() {
        connection.write(NetworkChannel.DEFAULT, clientReadyPacket(false, true));
    }

    private static ByteBuf clientReadyPacket(boolean chunksReady, boolean gameplayReady) {
        ByteBuf ready = Unpooled.buffer(10);
        ready.writeIntLE(2);
        ready.writeIntLE(CLIENT_READY_PACKET_ID);
        ready.writeByte(chunksReady ? 1 : 0);
        ready.writeByte(gameplayReady ? 1 : 0);
        return ready;
    }

    private void sendStaleEntityRemoval(HyProxyPlayer player, Set<Integer> staleEntityIds) {
        if (staleEntityIds.isEmpty() || !player.hasActiveInboundConnection()) {
            return;
        }

        log.info("{} removed {} stale backend entity ids before target backend entity stream", player.getIdentifier(), staleEntityIds.size());
        player.getInboundConnection().write(NetworkChannel.DEFAULT, entityRemovalPacket(staleEntityIds));
    }

    private void finishSeamlessClientActivation(HyProxyPlayer player) {
        this.sendSeamlessPositionCorrection(player);
        if (player.consumePendingSeamlessGameplayReady()) {
            log.info("{} marked target backend {} ready for gameplay after seamless handoff",
                    player.getIdentifier(),
                    backend.getInfo().id());
            this.sendClientReadyForGameplay();
        }
    }

    private void sendSeamlessPositionCorrection(HyProxyPlayer player) {
        HyProxyPlayer.SeamlessTransferCorrection correction = player.consumePendingSeamlessCorrection();
        if (correction == null || !player.hasActiveInboundConnection()) {
            return;
        }

        byte teleportId = player.queueSyntheticTeleportAck();
        log.info("{} corrected seamless handoff client transform to {},{},{} yaw={} pitch={} for backend {}",
                player.getIdentifier(),
                correction.x(),
                correction.y(),
                correction.z(),
                correction.yaw(),
                correction.pitch(),
                backend.getInfo().id());
        player.getInboundConnection().write(NetworkChannel.DEFAULT, clientTeleportPacket(teleportId, correction));
    }

    private void trackBackendEntityIdsFromEntityUpdates(HyProxyPlayer player, ByteBuf packet) {
        byte[] compressedPayload = compressedPayload(packet);
        if (compressedPayload == null) {
            return;
        }

        byte[] payload = decompressEntityUpdatesPayload(compressedPayload);
        if (payload == null) {
            return;
        }

        this.trackPotentialBackendEntityIds(player, payload);
    }

    private void trackPotentialBackendEntityIds(HyProxyPlayer player, byte[] payload) {
        int entityIdBase = entityIdBaseForBackend(player);
        if (entityIdBase < 0) {
            return;
        }

        int entityIdMaxExclusive = entityIdBase + SHARD_ENTITY_ID_RANGE_SIZE;
        for (int offset = 0; offset <= payload.length - 4; offset++) {
            int candidateEntityId = littleEndianInt(payload, offset);
            if (candidateEntityId >= entityIdBase && candidateEntityId < entityIdMaxExclusive) {
                player.rememberTrackedBackendEntityId(candidateEntityId);
            }
        }
    }

    private static boolean isPrewarmChunkPacket(int packetId) {
        return packetId == SET_CHUNK_PACKET_ID
                || packetId == SET_CHUNK_HEIGHTMAP_PACKET_ID
                || packetId == SET_CHUNK_TINTMAP_PACKET_ID
                || packetId == SET_CHUNK_ENVIRONMENTS_PACKET_ID;
    }

    private static ByteBuf entityUpdatesPacket(byte[] uncompressedPayload) {
        byte[] compressedPayload = Zstd.compress(uncompressedPayload, Zstd.defaultCompressionLevel());
        ByteBuf packet = Unpooled.buffer(8 + compressedPayload.length);
        packet.writeIntLE(compressedPayload.length);
        packet.writeIntLE(ENTITY_UPDATES_PACKET_ID);
        packet.writeBytes(compressedPayload);
        return packet;
    }

    private static byte[] serializeEntityUpdatesPayload(byte[] removedBytes, List<byte[]> updates) {
        if ((removedBytes == null || removedBytes.length == 0) && updates.isEmpty()) {
            return null;
        }

        ByteBuf payload = Unpooled.buffer();
        try {
            byte nullBits = 0;
            if (removedBytes != null) {
                nullBits = (byte)(nullBits | 1);
            }
            if (!updates.isEmpty()) {
                nullBits = (byte)(nullBits | 2);
            }

            payload.writeByte(nullBits);
            int removedOffsetSlot = payload.writerIndex();
            payload.writeIntLE(0);
            int updatesOffsetSlot = payload.writerIndex();
            payload.writeIntLE(0);
            int variableBlockStart = payload.writerIndex();
            if (removedBytes != null) {
                payload.setIntLE(removedOffsetSlot, payload.writerIndex() - variableBlockStart);
                payload.writeBytes(removedBytes);
            } else {
                payload.setIntLE(removedOffsetSlot, -1);
            }

            if (!updates.isEmpty()) {
                payload.setIntLE(updatesOffsetSlot, payload.writerIndex() - variableBlockStart);
                VarIntUtil.write(payload, updates.size());
                for (byte[] update : updates) {
                    payload.writeBytes(update);
                }
            } else {
                payload.setIntLE(updatesOffsetSlot, -1);
            }

            return readableBytes(payload);
        } finally {
            payload.release();
        }
    }

    private static byte[] serializeEntityUpdate(int networkId, byte[] removedBytes, List<ParsedComponentUpdate> updates) {
        if ((removedBytes == null || removedBytes.length == 0) && updates.isEmpty()) {
            return null;
        }

        ByteBuf payload = Unpooled.buffer();
        try {
            byte nullBits = 0;
            if (removedBytes != null) {
                nullBits = (byte)(nullBits | 1);
            }
            if (!updates.isEmpty()) {
                nullBits = (byte)(nullBits | 2);
            }

            payload.writeByte(nullBits);
            payload.writeIntLE(networkId);
            int removedOffsetSlot = payload.writerIndex();
            payload.writeIntLE(0);
            int updatesOffsetSlot = payload.writerIndex();
            payload.writeIntLE(0);
            int variableBlockStart = payload.writerIndex();
            if (removedBytes != null) {
                payload.setIntLE(removedOffsetSlot, payload.writerIndex() - variableBlockStart);
                payload.writeBytes(removedBytes);
            } else {
                payload.setIntLE(removedOffsetSlot, -1);
            }

            if (!updates.isEmpty()) {
                payload.setIntLE(updatesOffsetSlot, payload.writerIndex() - variableBlockStart);
                VarIntUtil.write(payload, updates.size());
                for (ParsedComponentUpdate update : updates) {
                    payload.writeBytes(update.bytes());
                }
            } else {
                payload.setIntLE(updatesOffsetSlot, -1);
            }

            return readableBytes(payload);
        } finally {
            payload.release();
        }
    }

    private static ParsedEntityUpdates parseEntityUpdatesPayload(byte[] payload) {
        try {
            if (!hasBytes(payload, 0, 9)) {
                return null;
            }

            int nullBits = payload[0] & 0xFF;
            byte[] removedBytes = null;
            if ((nullBits & 1) != 0) {
                int removedPosition = variableFieldPosition(payload, 0, 9, 1);
                int removedEnd = fixedArrayEnd(payload, removedPosition, 4);
                if (removedEnd < 0) {
                    return null;
                }
                removedBytes = Arrays.copyOfRange(payload, removedPosition, removedEnd);
            }

            List<ParsedEntityUpdate> updates = null;
            if ((nullBits & 2) != 0) {
                int updatesPosition = variableFieldPosition(payload, 0, 9, 5);
                VarIntRead updatesCount = readVarInt(payload, updatesPosition);
                if (updatesCount == null || updatesCount.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                    return null;
                }

                int position = updatesPosition + updatesCount.length();
                updates = new ArrayList<>(Math.min(updatesCount.value(), 1024));
                for (int i = 0; i < updatesCount.value(); i++) {
                    ParsedEntityUpdate update = parseEntityUpdate(payload, position);
                    if (update == null) {
                        return null;
                    }
                    updates.add(update);
                    position += update.originalBytes().length;
                }
            }

            return new ParsedEntityUpdates(removedBytes, updates);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ParsedEntityUpdate parseEntityUpdate(byte[] payload, int offset) {
        if (!hasBytes(payload, offset, 13)) {
            return null;
        }

        int nullBits = payload[offset] & 0xFF;
        int maxEnd = offset + 13;
        int networkId = littleEndianInt(payload, offset + 1);
        byte[] removedBytes = null;
        if ((nullBits & 1) != 0) {
            int removedPosition = variableFieldPosition(payload, offset, 13, 5);
            int removedEnd = fixedArrayEnd(payload, removedPosition, 1);
            if (removedEnd < 0) {
                return null;
            }
            removedBytes = Arrays.copyOfRange(payload, removedPosition, removedEnd);
            maxEnd = Math.max(maxEnd, removedEnd);
        }

        List<ParsedComponentUpdate> updates = null;
        if ((nullBits & 2) != 0) {
            int updatesPosition = variableFieldPosition(payload, offset, 13, 9);
            VarIntRead updatesCount = readVarInt(payload, updatesPosition);
            if (updatesCount == null || updatesCount.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                return null;
            }

            int position = updatesPosition + updatesCount.length();
            updates = new ArrayList<>(Math.min(updatesCount.value(), 64));
            for (int i = 0; i < updatesCount.value(); i++) {
                ParsedComponentUpdate update = parseComponentUpdate(payload, position);
                if (update == null) {
                    return null;
                }
                updates.add(update);
                position += update.bytes().length;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if (!hasBytes(payload, offset, maxEnd - offset)) {
            return null;
        }

        return new ParsedEntityUpdate(
                networkId,
                removedBytes,
                updates,
                Arrays.copyOfRange(payload, offset, maxEnd)
        );
    }

    private static ParsedComponentUpdate parseComponentUpdate(byte[] payload, int offset) {
        VarIntRead typeId = readVarInt(payload, offset);
        if (typeId == null) {
            return null;
        }

        int bodyOffset = offset + typeId.length();
        int bodyLength = componentUpdateBodyBytesConsumed(typeId.value(), payload, bodyOffset);
        if (bodyLength < 0 || !hasBytes(payload, bodyOffset, bodyLength)) {
            return null;
        }

        return new ParsedComponentUpdate(
                typeId.value(),
                Arrays.copyOfRange(payload, offset, bodyOffset + bodyLength)
        );
    }

    private static int componentUpdateBodyBytesConsumed(int typeId, byte[] bytes, int offset) {
        return switch (typeId) {
            case 0 -> varStringBytesConsumed(bytes, offset);
            case 1 -> fixedArrayBytesConsumed(bytes, offset, 4);
            case 2 -> {
                if (!hasBytes(bytes, offset, 4)) {
                    yield -1;
                }
                int end = varStringEnd(bytes, offset + 4);
                yield end < 0 ? -1 : end - offset;
            }
            case 3 -> modelUpdateBytesConsumed(bytes, offset);
            case 4 -> playerSkinUpdateBytesConsumed(bytes, offset);
            case 5 -> itemUpdateBytesConsumed(bytes, offset);
            case 6 -> fixedBytesConsumed(bytes, offset, 8);
            case 7 -> equipmentUpdateBytesConsumed(bytes, offset);
            case 8 -> entityStatsUpdateBytesConsumed(bytes, offset);
            case 9 -> fixedBytesConsumed(bytes, offset, 49);
            case 10 -> fixedBytesConsumed(bytes, offset, 23);
            case 11 -> entityEffectsUpdateBytesConsumed(bytes, offset);
            case 12 -> interactionsUpdateBytesConsumed(bytes, offset);
            case 13 -> fixedBytesConsumed(bytes, offset, 4);
            case 14 -> interactableUpdateBytesConsumed(bytes, offset);
            case 15, 16, 17, 23, 25 -> 0;
            case 18, 19 -> fixedBytesConsumed(bytes, offset, 4);
            case 20 -> fixedBytesConsumed(bytes, offset, 16);
            case 21 -> fixedArrayBytesConsumed(bytes, offset, 4);
            case 22 -> fixedBytesConsumed(bytes, offset, 48);
            case 24 -> activeAnimationsUpdateBytesConsumed(bytes, offset);
            default -> -1;
        };
    }

    private static int modelUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 5)) {
            return -1;
        }

        int position = offset + 5;
        if ((bytes[offset] & 1) != 0) {
            int modelLength = modelBytesConsumed(bytes, position, 0);
            if (modelLength < 0) {
                return -1;
            }
            position += modelLength;
        }
        return position - offset;
    }

    private static int playerSkinUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 1)) {
            return -1;
        }

        int position = offset + 1;
        if ((bytes[offset] & 1) != 0) {
            int skinLength = playerSkinBytesConsumed(bytes, position);
            if (skinLength < 0) {
                return -1;
            }
            position += skinLength;
        }
        return position - offset;
    }

    private static int itemUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 4)) {
            return -1;
        }

        int itemLength = itemWithAllMetadataBytesConsumed(bytes, offset + 4);
        return itemLength < 0 ? -1 : 4 + itemLength;
    }

    private static int equipmentUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 13)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 13;
        if ((nullBits & 1) != 0) {
            int position = variableFieldPosition(bytes, offset, 13, 1);
            VarIntRead count = readVarInt(bytes, position);
            if (count == null || count.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                return -1;
            }

            position += count.length();
            for (int i = 0; i < count.value(); i++) {
                position = varStringEnd(bytes, position);
                if (position < 0) {
                    return -1;
                }
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits & 2) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 13, 5));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        if ((nullBits & 4) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 13, 9));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int entityStatsUpdateBytesConsumed(byte[] bytes, int offset) {
        int position = offset;
        VarIntRead dictionaryLength = readVarInt(bytes, position);
        if (dictionaryLength == null || dictionaryLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        position += dictionaryLength.length();
        for (int i = 0; i < dictionaryLength.value(); i++) {
            if (!hasBytes(bytes, position, 4)) {
                return -1;
            }
            position += 4;

            VarIntRead arrayLength = readVarInt(bytes, position);
            if (arrayLength == null || arrayLength.value() > 64) {
                return -1;
            }
            position += arrayLength.length();

            for (int j = 0; j < arrayLength.value(); j++) {
                int statUpdateLength = entityStatUpdateBytesConsumed(bytes, position);
                if (statUpdateLength < 0) {
                    return -1;
                }
                position += statUpdateLength;
            }
        }

        return position - offset;
    }

    private static int entityStatUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 21)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 21;
        if ((nullBits & 2) != 0) {
            int position = variableFieldPosition(bytes, offset, 21, 13);
            VarIntRead dictionaryLength = readVarInt(bytes, position);
            if (dictionaryLength == null || dictionaryLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                return -1;
            }

            position += dictionaryLength.length();
            for (int i = 0; i < dictionaryLength.value(); i++) {
                position = varStringEnd(bytes, position);
                if (position < 0 || !hasBytes(bytes, position, 6)) {
                    return -1;
                }
                position += 6;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits & 4) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 21, 17));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int entityEffectsUpdateBytesConsumed(byte[] bytes, int offset) {
        VarIntRead count = readVarInt(bytes, offset);
        if (count == null || count.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        int position = offset + count.length();
        for (int i = 0; i < count.value(); i++) {
            int effectLength = entityEffectUpdateBytesConsumed(bytes, position);
            if (effectLength < 0) {
                return -1;
            }
            position += effectLength;
        }
        return position - offset;
    }

    private static int entityEffectUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 12)) {
            return -1;
        }

        int position = offset + 12;
        if ((bytes[offset] & 1) != 0) {
            position = varStringEnd(bytes, position);
            if (position < 0) {
                return -1;
            }
        }
        return position - offset;
    }

    private static int interactionsUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 9)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int position = variableFieldPosition(bytes, offset, 9, 1);
        VarIntRead dictionaryLength = readVarInt(bytes, position);
        if (dictionaryLength == null || dictionaryLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        position += dictionaryLength.length();
        long requiredPosition = position + dictionaryLength.value() * 5L;
        if (requiredPosition > bytes.length) {
            return -1;
        }
        int maxEnd = (int)requiredPosition;

        if ((nullBits & 1) != 0) {
            int tagsPosition = variableFieldPosition(bytes, offset, 9, 5);
            int tagsLength = fixedArrayBytesConsumed(bytes, tagsPosition, 1);
            if (tagsLength < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, tagsPosition + tagsLength);
        }

        return maxEnd - offset;
    }

    private static int interactableUpdateBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 1)) {
            return -1;
        }

        int position = offset + 1;
        if ((bytes[offset] & 1) != 0) {
            position = varStringEnd(bytes, position);
            if (position < 0) {
                return -1;
            }
        }
        return position - offset;
    }

    private static int activeAnimationsUpdateBytesConsumed(byte[] bytes, int offset) {
        VarIntRead count = readVarInt(bytes, offset);
        if (count == null || count.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        int position = offset + count.length();
        int bitfieldSize = (count.value() + 7) / 8;
        if (!hasBytes(bytes, position, bitfieldSize)) {
            return -1;
        }

        int bitfieldPosition = position;
        position += bitfieldSize;
        for (int i = 0; i < count.value(); i++) {
            if ((bytes[bitfieldPosition + i / 8] & (1 << (i % 8))) != 0) {
                position = varStringEnd(bytes, position);
                if (position < 0) {
                    return -1;
                }
            }
        }

        return position - offset;
    }

    private static int modelBytesConsumed(byte[] bytes, int offset, int depth) {
        if (depth > 8 || !hasBytes(bytes, offset, 99)) {
            return -1;
        }

        int nullBits0 = bytes[offset] & 0xFF;
        int nullBits1 = bytes[offset + 1] & 0xFF;
        int maxEnd = offset + 99;

        int[] stringBits = {4, 8, 16, 32, 64};
        int[] stringOffsetFields = {51, 55, 59, 63, 67};
        for (int i = 0; i < stringBits.length; i++) {
            if ((nullBits0 & stringBits[i]) != 0) {
                int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 99, stringOffsetFields[i]));
                if (end < 0) {
                    return -1;
                }
                maxEnd = Math.max(maxEnd, end);
            }
        }

        if ((nullBits0 & 128) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 71);
            int length = cameraSettingsBytesConsumed(bytes, position);
            if (length < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position + length);
        }

        if ((nullBits1 & 1) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 75);
            VarIntRead dictionaryLength = readVarInt(bytes, position);
            if (dictionaryLength == null || dictionaryLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                return -1;
            }

            position += dictionaryLength.length();
            for (int i = 0; i < dictionaryLength.value(); i++) {
                position = varStringEnd(bytes, position);
                if (position < 0) {
                    return -1;
                }
                int animationSetLength = animationSetBytesConsumed(bytes, position);
                if (animationSetLength < 0) {
                    return -1;
                }
                position += animationSetLength;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits1 & 2) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 79);
            position = repeatedStructEnd(bytes, position, OutboundForwardingPacketHandler::modelAttachmentBytesConsumed);
            if (position < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits1 & 4) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 83);
            position = repeatedStructEnd(bytes, position, OutboundForwardingPacketHandler::modelParticleBytesConsumed);
            if (position < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits1 & 8) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 87);
            position = repeatedStructEnd(bytes, position, OutboundForwardingPacketHandler::modelTrailBytesConsumed);
            if (position < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits1 & 16) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 91);
            VarIntRead dictionaryLength = readVarInt(bytes, position);
            if (dictionaryLength == null || dictionaryLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
                return -1;
            }

            position += dictionaryLength.length();
            for (int i = 0; i < dictionaryLength.value(); i++) {
                position = varStringEnd(bytes, position);
                if (position < 0) {
                    return -1;
                }

                int detailBoxArrayLength = fixedArrayBytesConsumed(bytes, position, 37);
                if (detailBoxArrayLength < 0) {
                    return -1;
                }
                position += detailBoxArrayLength;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        if ((nullBits1 & 32) != 0) {
            int position = variableFieldPosition(bytes, offset, 99, 95);
            int phobiaModelLength = modelBytesConsumed(bytes, position, depth + 1);
            if (phobiaModelLength < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position + phobiaModelLength);
        }

        return maxEnd - offset;
    }

    private static int playerSkinBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 83)) {
            return -1;
        }

        int maxEnd = offset + 83;
        for (int i = 0; i < 20; i++) {
            int bitByte = bytes[offset + i / 8] & 0xFF;
            if ((bitByte & (1 << (i % 8))) == 0) {
                continue;
            }

            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 83, 3 + i * 4));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int itemWithAllMetadataBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 30)) {
            return -1;
        }

        int maxEnd = varStringEnd(bytes, variableFieldPosition(bytes, offset, 30, 22));
        if (maxEnd < 0) {
            return -1;
        }

        if ((bytes[offset] & 1) != 0) {
            int metadataEnd = varStringEnd(bytes, variableFieldPosition(bytes, offset, 30, 26));
            if (metadataEnd < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, metadataEnd);
        }

        return maxEnd - offset;
    }

    private static int cameraSettingsBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 21)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 21;
        if ((nullBits & 2) != 0) {
            int position = variableFieldPosition(bytes, offset, 21, 13);
            int length = cameraAxisBytesConsumed(bytes, position);
            if (length < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position + length);
        }

        if ((nullBits & 4) != 0) {
            int position = variableFieldPosition(bytes, offset, 21, 17);
            int length = cameraAxisBytesConsumed(bytes, position);
            if (length < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position + length);
        }

        return maxEnd - offset;
    }

    private static int cameraAxisBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 9)) {
            return -1;
        }

        int position = offset + 9;
        if ((bytes[offset] & 2) != 0) {
            int length = fixedArrayBytesConsumed(bytes, position, 1);
            if (length < 0) {
                return -1;
            }
            position += length;
        }
        return position - offset;
    }

    private static int animationSetBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 17)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 17;
        if ((nullBits & 2) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 17, 9));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        if ((nullBits & 4) != 0) {
            int position = variableFieldPosition(bytes, offset, 17, 13);
            position = repeatedStructEnd(bytes, position, OutboundForwardingPacketHandler::animationBytesConsumed);
            if (position < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position);
        }

        return maxEnd - offset;
    }

    private static int animationBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 30)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 30;
        if ((nullBits & 1) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 30, 22));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        if ((nullBits & 2) != 0) {
            int position = variableFieldPosition(bytes, offset, 30, 26);
            int length = fixedArrayBytesConsumed(bytes, position, 4);
            if (length < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, position + length);
        }

        return maxEnd - offset;
    }

    private static int modelAttachmentBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 17)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 17;
        for (int i = 0; i < 4; i++) {
            if ((nullBits & (1 << i)) == 0) {
                continue;
            }

            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 17, 1 + i * 4));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int modelParticleBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 42)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 42;
        if ((nullBits & 8) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 42, 34));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        if ((nullBits & 16) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 42, 38));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int modelTrailBytesConsumed(byte[] bytes, int offset) {
        if (!hasBytes(bytes, offset, 35)) {
            return -1;
        }

        int nullBits = bytes[offset] & 0xFF;
        int maxEnd = offset + 35;
        if ((nullBits & 4) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 35, 27));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        if ((nullBits & 8) != 0) {
            int end = varStringEnd(bytes, variableFieldPosition(bytes, offset, 35, 31));
            if (end < 0) {
                return -1;
            }
            maxEnd = Math.max(maxEnd, end);
        }

        return maxEnd - offset;
    }

    private static int repeatedStructEnd(byte[] bytes, int offset, BytesConsumedFunction bytesConsumedFunction) {
        VarIntRead count = readVarInt(bytes, offset);
        if (count == null || count.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        int position = offset + count.length();
        for (int i = 0; i < count.value(); i++) {
            int length = bytesConsumedFunction.bytesConsumed(bytes, position);
            if (length < 0) {
                return -1;
            }
            position += length;
        }
        return position;
    }

    private static int varStringBytesConsumed(byte[] bytes, int offset) {
        int end = varStringEnd(bytes, offset);
        return end < 0 ? -1 : end - offset;
    }

    private static int varStringEnd(byte[] bytes, int offset) {
        VarIntRead stringLength = readVarInt(bytes, offset);
        if (stringLength == null || stringLength.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        long end = (long)offset + stringLength.length() + stringLength.value();
        if (end > bytes.length) {
            return -1;
        }
        return (int)end;
    }

    private static int fixedArrayBytesConsumed(byte[] bytes, int offset, int elementSize) {
        int end = fixedArrayEnd(bytes, offset, elementSize);
        return end < 0 ? -1 : end - offset;
    }

    private static int fixedArrayEnd(byte[] bytes, int offset, int elementSize) {
        VarIntRead count = readVarInt(bytes, offset);
        if (count == null || count.value() > MAX_PROTOCOL_ARRAY_COUNT) {
            return -1;
        }

        long end = (long)offset + count.length() + (long)count.value() * elementSize;
        if (end > bytes.length) {
            return -1;
        }
        return (int)end;
    }

    private static int fixedBytesConsumed(byte[] bytes, int offset, int length) {
        return hasBytes(bytes, offset, length) ? length : -1;
    }

    private static int variableFieldPosition(byte[] bytes, int structOffset, int variableBlockStart, int fieldOffsetOffset) {
        if (!hasBytes(bytes, structOffset + fieldOffsetOffset, 4)) {
            return -1;
        }

        int relativeOffset = littleEndianInt(bytes, structOffset + fieldOffsetOffset);
        if (relativeOffset < 0) {
            return -1;
        }

        long position = (long)structOffset + variableBlockStart + relativeOffset;
        if (position < structOffset + (long)variableBlockStart || position > bytes.length) {
            return -1;
        }
        return (int)position;
    }

    private static VarIntRead readVarInt(byte[] bytes, int offset) {
        if (offset < 0 || offset >= bytes.length) {
            return null;
        }

        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5 && offset + i < bytes.length; i++) {
            int current = bytes[offset + i] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                if (value < 0) {
                    return null;
                }
                return new VarIntRead(value, i + 1);
            }
            shift += 7;
        }
        return null;
    }

    private static boolean hasBytes(byte[] bytes, int offset, int length) {
        return offset >= 0 && length >= 0 && offset <= bytes.length && length <= bytes.length - offset;
    }

    private static byte[] compressedPayload(ByteBuf packet) {
        int payloadLength = payloadLength(packet);
        if (payloadLength <= 0 || packet.readableBytes() < 8 + payloadLength) {
            return null;
        }

        byte[] compressedPayload = new byte[payloadLength];
        packet.getBytes(packet.readerIndex() + 8, compressedPayload);
        return compressedPayload;
    }

    private static byte[] readableBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    @FunctionalInterface
    private interface BytesConsumedFunction {
        int bytesConsumed(byte[] bytes, int offset);
    }

    private record EntityUpdatesForwardingDecision(boolean drop, ByteBuf replacement) {
        static EntityUpdatesForwardingDecision forwardOriginal() {
            return new EntityUpdatesForwardingDecision(false, null);
        }

        static EntityUpdatesForwardingDecision dropPacket() {
            return new EntityUpdatesForwardingDecision(true, null);
        }

        static EntityUpdatesForwardingDecision replace(ByteBuf replacement) {
            return new EntityUpdatesForwardingDecision(false, replacement);
        }
    }

    private record ParsedEntityUpdates(byte[] removedBytes, List<ParsedEntityUpdate> updates) {
    }

    private record ParsedEntityUpdate(int networkId, byte[] removedBytes, List<ParsedComponentUpdate> updates, byte[] originalBytes) {
    }

    private record ParsedComponentUpdate(int typeId, byte[] bytes) {
    }

    private record VarIntRead(int value, int length) {
    }

    private boolean handleShardTaleHandoffMessage(HyProxy proxy, byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            boolean prewarm = buf.readableBytes() == SHARDTALE_PREWARM_PAYLOAD_BYTES;
            if (buf.readableBytes() != SHARDTALE_HANDOFF_PAYLOAD_BYTES && !prewarm) {
                return false;
            }

            int version = buf.readUnsignedByte();
            if (version != SHARDTALE_HANDOFF_VERSION) {
                return false;
            }

            String backendId = switch (buf.readUnsignedByte()) {
                case 0 -> "west";
                case 1 -> "east";
                default -> null;
            };

            if (backendId == null) return false;

            float x = buf.readFloatLE();
            float y = buf.readFloatLE();
            float z = buf.readFloatLE();
            float yaw = buf.readFloatLE();
            float pitch = buf.readFloatLE();
            float roll = buf.readFloatLE();
            if (prewarm && buf.readUnsignedByte() != SHARDTALE_PREWARM_MODE) {
                return false;
            }

            HyProxyBackend targetBackend = proxy.getBackendById(backendId);
            if (targetBackend == null) {
                return true;
            }

            HyProxyPlayer target = connection.ensurePlayer();
            HyProxyPlayer.SeamlessTransferCorrection correction = new HyProxyPlayer.SeamlessTransferCorrection(
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll
            );
            if (prewarm) {
                if (!SEAMLESS_PREWARM_ENABLED) {
                    log.debug("{} ignored disabled ShardTale seamless prewarm request for {}",
                            target.getIdentifier(),
                            targetBackend.getInfo().id());
                    return true;
                }

                target.requestSeamlessPrewarm(targetBackend, correction);
                return true;
            }

            if (this.promoteReadyPrewarmedSeamlessHandoff(target, targetBackend, correction)) {
                return true;
            }

            target.sendPlayerToBackend(targetBackend, correction);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            buf.release();
        }
    }

    private boolean promoteReadyPrewarmedSeamlessHandoff(
            HyProxyPlayer player,
            HyProxyBackend targetBackend,
            HyProxyPlayer.SeamlessTransferCorrection correction
    ) {
        if (!player.isSeamlessPrewarming()
                || !player.isSeamlessPrewarmReady()
                || player.getPendingSeamlessBackend() != targetBackend
                || player.getPendingOutboundConnection() == null) {
            return false;
        }

        HytaleConnection pendingConnection = player.getPendingOutboundConnection();
        Set<Integer> staleEntityIds = player.trackedBackendEntityIdsForRemoval();
        player.refreshPendingSeamlessCorrection(targetBackend, correction);
        player.convertSeamlessPrewarmToHandoff(targetBackend, correction);
        player.promotePendingOutboundConnection(pendingConnection, targetBackend);
        player.consumeSuppressNextJoinWorld();
        this.sendStaleEntityRemoval(player, staleEntityIds);
        player.clearTrackedBackendEntityIds();

        HyProxyPlayer.PendingSetClientId pendingSetClientId = player.consumePendingSeamlessSetClientId();
        if (pendingSetClientId != null) {
            if (player.hasActiveInboundConnection()) {
                if (pendingSetClientId.clientEntityId() != Integer.MIN_VALUE) {
                    player.setCurrentClientEntityId(pendingSetClientId.clientEntityId());
                }
                player.getInboundConnection().write(NetworkChannel.DEFAULT, pendingSetClientId.packet());
                this.sendSeamlessPositionCorrection(player);
                player.getOutboundConnection().write(NetworkChannel.DEFAULT, clientReadyPacket(false, true));
            } else {
                pendingSetClientId.packet().release();
            }
        } else {
            player.queuePendingSeamlessGameplayReady();
            this.sendSeamlessPositionCorrection(player);
        }

        log.info("{} promoted prewarmed seamless backend handoff to {}", player.getIdentifier(), targetBackend.getInfo().id());
        return true;
    }

    private void sendSyntheticPongs(int id) {
        connection.write(NetworkChannel.DEFAULT, syntheticPong(id, 0));
        connection.write(NetworkChannel.DEFAULT, syntheticPong(id, 1));
        connection.write(NetworkChannel.DEFAULT, syntheticPong(id, 2));
    }

    private static ByteBuf syntheticPong(int id, int type) {
        ByteBuf pong = Unpooled.buffer(28);
        pong.writeIntLE(20);
        pong.writeIntLE(4);
        pong.writeByte(0);
        pong.writeIntLE(id);
        pong.writeZero(12);
        pong.writeByte(type);
        pong.writeShortLE(0);
        return pong;
    }

    private static ByteBuf sanitizedJoinWorld(ByteBuf original, boolean clearWorld) {
        ByteBuf joinWorld = original.copy();
        if (joinWorld.readableBytes() >= 10) {
            int payloadIndex = joinWorld.readerIndex() + 8;
            joinWorld.setByte(payloadIndex, clearWorld ? 1 : 0);
            joinWorld.setByte(payloadIndex + 1, 0);
        }
        return joinWorld;
    }

    private static ByteBuf entityRemovalPacket(Collection<Integer> networkIds) {
        int[] sortedNetworkIds = networkIds.stream()
                .mapToInt(Integer::intValue)
                .distinct()
                .sorted()
                .toArray();

        ByteBuf payload = Unpooled.buffer(9 + VarIntUtil.size(sortedNetworkIds.length) + sortedNetworkIds.length * 4);
        payload.writeByte(1);
        payload.writeIntLE(0);
        payload.writeIntLE(-1);
        VarIntUtil.write(payload, sortedNetworkIds.length);
        for (int networkId : sortedNetworkIds) {
            payload.writeIntLE(networkId);
        }

        byte[] uncompressedPayload = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), uncompressedPayload);
        payload.release();

        byte[] compressedPayload = Zstd.compress(uncompressedPayload, Zstd.defaultCompressionLevel());
        ByteBuf packet = Unpooled.buffer(8 + compressedPayload.length);
        packet.writeIntLE(compressedPayload.length);
        packet.writeIntLE(ENTITY_UPDATES_PACKET_ID);
        packet.writeBytes(compressedPayload);
        return packet;
    }

    private static ByteBuf clientTeleportPacket(byte teleportId, HyProxyPlayer.SeamlessTransferCorrection correction) {
        ByteBuf packet = Unpooled.buffer(60);
        packet.writeIntLE(52);
        packet.writeIntLE(CLIENT_TELEPORT_PACKET_ID);
        packet.writeByte(1);
        packet.writeByte(teleportId);
        packet.writeByte(7);
        packet.writeDoubleLE(correction.x());
        packet.writeDoubleLE(correction.y());
        packet.writeDoubleLE(correction.z());
        packet.writeFloatLE(correction.yaw());
        packet.writeFloatLE(0.0F);
        packet.writeFloatLE(0.0F);
        packet.writeFloatLE(correction.yaw());
        packet.writeFloatLE(correction.pitch());
        packet.writeFloatLE(correction.roll());
        packet.writeByte(1);
        return packet;
    }

    private int entityIdBaseForBackend(HyProxyPlayer player) {
        return switch (backend.getInfo().id()) {
            case "west" -> SHARD_ENTITY_ID_RANGE_SIZE;
            case "east" -> SHARD_ENTITY_ID_RANGE_SIZE * 2;
            default -> {
                int currentClientEntityId = player.getCurrentClientEntityId();
                if (currentClientEntityId < SHARD_ENTITY_ID_RANGE_SIZE) {
                    yield -1;
                }

                yield (currentClientEntityId / SHARD_ENTITY_ID_RANGE_SIZE) * SHARD_ENTITY_ID_RANGE_SIZE;
            }
        };
    }

    private static byte[] decompressEntityUpdatesPayload(byte[] compressedPayload) {
        try {
            long decompressedSize = Zstd.decompressedSize(compressedPayload);
            if (!Zstd.isError(decompressedSize)
                    && decompressedSize > 0
                    && decompressedSize <= MAX_TRACKED_ENTITY_UPDATES_PAYLOAD_SIZE) {
                return Zstd.decompress(compressedPayload, (int) decompressedSize);
            }

            int outputSize = 256 * 1024;
            while (outputSize <= MAX_TRACKED_ENTITY_UPDATES_PAYLOAD_SIZE) {
                byte[] output = new byte[outputSize];
                long result = Zstd.decompressByteArray(
                        output,
                        0,
                        output.length,
                        compressedPayload,
                        0,
                        compressedPayload.length
                );
                if (!Zstd.isError(result) && result >= 0 && result <= output.length) {
                    return Arrays.copyOf(output, (int) result);
                }

                outputSize *= 2;
            }
        } catch (RuntimeException ignored) {
        }

        return null;
    }

    private static int payloadLength(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return -1;
        }

        return buf.getIntLE(buf.readerIndex());
    }

    private static int packetId(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return -1;
        }

        return buf.getIntLE(buf.readerIndex() + 4);
    }

    private static int pingId(ByteBuf buf) {
        if (buf.readableBytes() < 13) {
            return Integer.MIN_VALUE;
        }

        return buf.getIntLE(buf.readerIndex() + 9);
    }

    private static int clientEntityId(ByteBuf buf) {
        if (buf.readableBytes() < 12) {
            return Integer.MIN_VALUE;
        }

        return buf.getIntLE(buf.readerIndex() + 8);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static boolean playerOptionsPacketHasSkin(ByteBuf packet) {
        return packet.readableBytes() >= 9 && (packet.getByte(packet.readerIndex() + 8) & 1) != 0;
    }
}
