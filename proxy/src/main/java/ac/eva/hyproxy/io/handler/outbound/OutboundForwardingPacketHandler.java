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

import java.util.Arrays;
import java.util.Collection;
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
    private static final int ENTITY_UPDATES_PACKET_ID = 161;
    private static final int DEFAULT_VIEW_RADIUS = 192;
    private static final int SHARD_ENTITY_ID_RANGE_SIZE = 1_000_000;
    private static final int MAX_TRACKED_ENTITY_UPDATES_PAYLOAD_SIZE = 16 * 1024 * 1024;
    private static final int SHARDTALE_HANDOFF_VERSION = 1;
    private static final int SHARDTALE_HANDOFF_PAYLOAD_BYTES = 26;
    private static final long SEAMLESS_SETUP_KICK_DELAY_MILLIS = 300L;

    private final HytaleConnection connection;
    private final HyProxyBackend backend;
    private boolean pendingSetupKickSent = false;
    private boolean pendingWorldSettingsReceived = false;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        if (player.getPendingOutboundConnection() == connection) {
            log.info("{} started seamless backend setup with {}", player.getIdentifier(), backend.getInfo().id());
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
            this.trackPotentialBackendEntityIds(player, buf);
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

        if (!proxy.getConfiguration().isProxyCommunicationEnabled()) return false;
        if (passwordResponse.getServerIdentityToken() == null) return false;

        ProxyCommunicationMessage message = ProxyCommunicationUtil.deserializeMessage(passwordResponse.getServerIdentityToken());

        if (message == null) return false;

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
            log.warn("{} pending seamless backend handoff to {} disconnected", player.getIdentifier(), backend.getInfo().id());
            player.clearPendingSeamlessHandoff(connection);
            return;
        }

        if (player.getOutboundConnection() != connection) return;
        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().close();
    }

    private boolean isPendingSeamlessHandoff(HyProxyPlayer player) {
        return player.isSeamlessSwitching() && player.getPendingOutboundConnection() == connection;
    }

    private void handlePendingSeamlessPacket(NetworkChannel channel, ByteBuf buf, HyProxyPlayer player) {
        if (channel != NetworkChannel.DEFAULT) {
            return;
        }

        switch (packetId(buf)) {
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
            case JOIN_WORLD_PACKET_ID -> {
                player.markSeamlessHandoffStage(
                        backend,
                        connection,
                        HyProxyPlayer.SEAMLESS_STAGE_JOIN_WORLD,
                        "JoinWorld"
                );
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
        ByteBuf ready = Unpooled.buffer(10);
        ready.writeIntLE(2);
        ready.writeIntLE(CLIENT_READY_PACKET_ID);
        ready.writeByte(1);
        ready.writeByte(0);
        connection.write(NetworkChannel.DEFAULT, ready);
    }

    private void sendClientReadyForGameplay() {
        ByteBuf ready = Unpooled.buffer(10);
        ready.writeIntLE(2);
        ready.writeIntLE(CLIENT_READY_PACKET_ID);
        ready.writeByte(0);
        ready.writeByte(1);
        connection.write(NetworkChannel.DEFAULT, ready);
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

    private void trackPotentialBackendEntityIds(HyProxyPlayer player, ByteBuf packet) {
        int entityIdBase = entityIdBaseForBackend(player);
        if (entityIdBase < 0) {
            return;
        }

        int entityIdMaxExclusive = entityIdBase + SHARD_ENTITY_ID_RANGE_SIZE;
        int payloadLength = payloadLength(packet);
        if (payloadLength <= 0 || packet.readableBytes() < 8 + payloadLength) {
            return;
        }

        byte[] compressedPayload = new byte[payloadLength];
        packet.getBytes(packet.readerIndex() + 8, compressedPayload);

        byte[] payload = decompressEntityUpdatesPayload(compressedPayload);
        if (payload == null) {
            return;
        }

        for (int offset = 0; offset <= payload.length - 4; offset++) {
            int candidateEntityId = littleEndianInt(payload, offset);
            if (candidateEntityId >= entityIdBase && candidateEntityId < entityIdMaxExclusive) {
                player.rememberTrackedBackendEntityId(candidateEntityId);
            }
        }
    }

    private boolean handleShardTaleHandoffMessage(HyProxy proxy, byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            if (buf.readableBytes() != SHARDTALE_HANDOFF_PAYLOAD_BYTES) {
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

            HyProxyBackend targetBackend = proxy.getBackendById(backendId);
            if (targetBackend == null) {
                return true;
            }

            HyProxyPlayer target = connection.ensurePlayer();
            target.sendPlayerToBackend(targetBackend, new HyProxyPlayer.SeamlessTransferCorrection(
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll
            ));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            buf.release();
        }
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
