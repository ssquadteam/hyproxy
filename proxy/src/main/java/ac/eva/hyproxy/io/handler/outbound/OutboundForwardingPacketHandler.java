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

@Slf4j
@RequiredArgsConstructor
public class OutboundForwardingPacketHandler implements HytalePacketHandler {
    private static final int WORLD_SETTINGS_PACKET_ID = 20;
    private static final int WORLD_LOAD_FINISHED_PACKET_ID = 22;
    private static final int REQUEST_ASSETS_PACKET_ID = 23;
    private static final int VIEW_RADIUS_PACKET_ID = 32;
    private static final int PLAYER_OPTIONS_PACKET_ID = 33;
    private static final int JOIN_WORLD_PACKET_ID = 104;
    private static final int CLIENT_READY_PACKET_ID = 105;
    private static final int DEFAULT_VIEW_RADIUS = 192;

    private final HytaleConnection connection;
    private final HyProxyBackend backend;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        if (player.getPendingOutboundConnection() == connection) {
            log.info("{} started seamless backend setup with {}", player.getIdentifier(), backend.getInfo().id());
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
        if (channel == NetworkChannel.DEFAULT && player.isSeamlessSwitching() && packetId(buf) == JOIN_WORLD_PACKET_ID) {
            log.info("{} forwarded no-fade JoinWorld during seamless backend handoff to {}", player.getIdentifier(), backend.getInfo().id());
            player.consumeSuppressNextJoinWorld();
            player.getInboundConnection().write(channel, sanitizedJoinWorld(buf));
            this.sendClientReadyForChunks();
            return;
        }

        player.getInboundConnection().write(channel, buf.retain());
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
            case WORLD_SETTINGS_PACKET_ID -> {
                log.info("{} received target WorldSettings during seamless backend setup for {}", player.getIdentifier(), backend.getInfo().id());
                this.sendViewRadius();
                this.sendRequestAssets();
            }
            case WORLD_LOAD_FINISHED_PACKET_ID -> {
                log.info("{} received target WorldLoadFinished during seamless backend setup for {}", player.getIdentifier(), backend.getInfo().id());
                this.sendPlayerOptions();
            }
            case JOIN_WORLD_PACKET_ID -> {
                log.info("{} forwarded no-fade JoinWorld and promoted seamless backend handoff to {}", player.getIdentifier(), backend.getInfo().id());
                ByteBuf joinWorld = sanitizedJoinWorld(buf);
                player.promotePendingOutboundConnection(connection, backend);
                player.consumeSuppressNextJoinWorld();
                if (player.hasActiveInboundConnection()) {
                    player.getInboundConnection().write(channel, joinWorld);
                } else {
                    joinWorld.release();
                }
                this.sendClientReadyForChunks();
            }
            default -> {
            }
        }
    }

    private void sendViewRadius() {
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

    private void sendPlayerOptions() {
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

    private static ByteBuf sanitizedJoinWorld(ByteBuf original) {
        ByteBuf joinWorld = original.copy();
        if (joinWorld.readableBytes() >= 10) {
            int payloadIndex = joinWorld.readerIndex() + 8;
            joinWorld.setByte(payloadIndex, 1); // clear stale chunks/entities before target backend packets arrive
            joinWorld.setByte(payloadIndex + 1, 0); // avoid the fade while still allowing the client world reset
        }
        return joinWorld;
    }

    private static int packetId(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return -1;
        }

        return buf.getIntLE(buf.readerIndex() + 4);
    }
}
