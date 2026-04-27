package ac.eva.hyproxy.io.handler.inbound;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.BackendConnector;
import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.game.ChatMessage;
import ac.eva.hyproxy.player.HyProxyPlayer;

import java.util.Locale;

@RequiredArgsConstructor
@Slf4j
public class InboundForwardingPacketHandler implements HytalePacketHandler {
    private static final int PONG_PACKET_ID = 4;
    private static final int VIEW_RADIUS_PACKET_ID = 32;
    private static final int PLAYER_OPTIONS_PACKET_ID = 33;
    private static final int CLIENT_MOVEMENT_PACKET_ID = 108;

    private final HytaleConnection connection;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        HyProxyBackend backend = player.getReferredBackend() != null ? player.getReferredBackend() : connection.getProxy().getInitialBackend();

        BackendConnector.connect(connection, backend);
    }

    @Override
    public boolean handle(ChatMessage chatMessage) {
        String message = chatMessage.getMessage();
        if (message == null) return false;

        if (!message.startsWith("/")) {
            return false;
        }

        return connection.ensurePlayer().performCommand(message.substring(1));
    }

    @Override
    public boolean handle(ClientDisconnect disconnect) {
        log.info("{} {}ed: {}", this.connection.getIdentifier(), disconnect.getType().name().toLowerCase(Locale.ROOT), disconnect.getReason());
        return false;
    }

    @Override
    public void handleGeneric(NetworkChannel channel, Packet packet) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.sendAsPlayer(channel, packet);
    }

    @Override
    public void handleUnknown(NetworkChannel channel, ByteBuf buf) {
        HyProxyPlayer player = connection.ensurePlayer();
        int packetId = packetId(buf);

        if (channel == NetworkChannel.DEFAULT && packetId == VIEW_RADIUS_PACKET_ID) {
            player.rememberLatestViewRadiusPacket(buf);
        } else if (channel == NetworkChannel.DEFAULT && packetId == PLAYER_OPTIONS_PACKET_ID) {
            player.rememberLatestPlayerOptionsPacket(buf);
        }

        if (!player.hasActiveOutboundConnection()) return;
        if (channel == NetworkChannel.DEFAULT && packetId == PONG_PACKET_ID) {
            int pongId = pongId(buf);
            int pongType = pongType(buf);
            if (!player.consumeForwardedBackendPong(pongId, pongType)) {
                log.info("{} dropped stale Pong id {} type {}", player.getIdentifier(), pongId, pongType);
                return;
            }
        }
        if (channel == NetworkChannel.DEFAULT && packetId == CLIENT_MOVEMENT_PACKET_ID) {
            int teleportAckId = teleportAckId(buf);
            if (teleportAckId != Integer.MIN_VALUE && player.consumeSyntheticTeleportAck((byte) teleportAckId)) {
                log.info("{} stripped synthetic seamless handoff TeleportAck id {}", player.getIdentifier(), teleportAckId);
                player.getOutboundConnection().write(channel, stripTeleportAck(buf));
                return;
            }
        }

        player.getOutboundConnection().write(channel, buf.retain());
    }

    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        ProtocolUtil.closeConnection(player.getOutboundConnection().getChannel());
    }

    private static int packetId(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return -1;
        }

        return buf.getIntLE(buf.readerIndex() + 4);
    }

    private static int pongId(ByteBuf buf) {
        if (buf.readableBytes() < 13) {
            return Integer.MIN_VALUE;
        }

        return buf.getIntLE(buf.readerIndex() + 9);
    }

    private static int pongType(ByteBuf buf) {
        if (buf.readableBytes() < 26) {
            return -1;
        }

        return buf.getByte(buf.readerIndex() + 25);
    }

    private static int teleportAckId(ByteBuf buf) {
        if (buf.readableBytes() < 88) {
            return Integer.MIN_VALUE;
        }

        int payloadIndex = buf.readerIndex() + 8;
        if ((buf.getByte(payloadIndex) & 0x20) == 0) {
            return Integer.MIN_VALUE;
        }

        return buf.getByte(payloadIndex + 79);
    }

    private static ByteBuf stripTeleportAck(ByteBuf original) {
        ByteBuf stripped = original.copy();
        int payloadIndex = stripped.readerIndex() + 8;
        stripped.setByte(payloadIndex, stripped.getByte(payloadIndex) & ~0x20);
        stripped.setByte(payloadIndex + 79, 0);
        return stripped;
    }
}
