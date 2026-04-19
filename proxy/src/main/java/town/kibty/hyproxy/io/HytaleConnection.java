package town.kibty.hyproxy.io;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.common.util.ProtocolUtil;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.impl.Disconnect;
import town.kibty.hyproxy.io.proto.DisconnectType;
import town.kibty.hyproxy.io.proto.NetworkChannel;
import town.kibty.hyproxy.player.HyProxyPlayer;
import town.kibty.hyproxy.util.NettyUtil;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Getter
@ChannelHandler.Sharable
public class HytaleConnection extends ChannelInboundHandlerAdapter {
    // packet id -> channel type
    private static final Map<Integer, NetworkChannel> FORWARDED_QUIC_PACKETS = new HashMap<>();

    static {
        FORWARDED_QUIC_PACKETS.put(131, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(132, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(133, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(134, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(135, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(136, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(140, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(141, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(142, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(143, NetworkChannel.CHUNKS);
        FORWARDED_QUIC_PACKETS.put(144, NetworkChannel.CHUNKS);

        FORWARDED_QUIC_PACKETS.put(241, NetworkChannel.WORLD_MAP);
        FORWARDED_QUIC_PACKETS.put(242, NetworkChannel.WORLD_MAP);
    }

    private final Channel channel;
    private final HyProxy proxy;

    private @Nullable HytalePacketHandler packetHandler;
    @Setter
    private @Nullable HyProxyPlayer player;
    @Getter
    private boolean disconnected = false;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (this.packetHandler == null) {
            ReferenceCountUtil.release(msg);
            return;
        }

        try {
            if (msg instanceof Packet packet) {
                if (!packet.handle(this.packetHandler)) {
                    this.packetHandler.handleGeneric(packet);
                }
            } else if (msg instanceof ByteBuf buf) {
                // hacky way of forwarding special hytale packets to the right quic channel
                // todo: fix this, or go with QUIC for connecting to backends
                int packetId = buf.getIntLE(buf.readerIndex() + 4);

                NetworkChannel type = FORWARDED_QUIC_PACKETS.get(packetId);
                if (type != null) {
                    QuicStreamChannel channel = this.ensurePlayer().getQuicChannel(type);
                    if (channel == null) {
                        this.packetHandler.handleUnknown(buf);
                        return;
                    }

                    if (channel.isActive()) {
                        channel.writeAndFlush(buf.retain());
                    }

                    return;
                }

                this.packetHandler.handleUnknown(buf);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (this.packetHandler != null) {
            this.packetHandler.connected();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (this.packetHandler != null) {
            this.packetHandler.disconnected();
        }

        // only fire onDisconnect once
        if (this.hasPlayer() && this.ensurePlayer().getInboundConnection() == this) {
            this.player.onDisconnect();
        }
    }

    public String getIdentifier() {
        if (this.hasPlayer()) {
            return player.getIdentifier();
        }

        return NettyUtil.formatRemoteAddress(channel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ctx.channel().isActive()) {
            return;
        }

        if (this.hasPlayer() && player.isAuthenticated()) {
            log.error("exception caught while handling connection {}", this.getIdentifier(), cause);
        }

        this.disconnect("Internal server error!", DisconnectType.CRASH);
    }

    public void setPacketHandler(HytalePacketHandler newHandler) {
        if (this.packetHandler != null) {
            this.packetHandler.deactivated();
        }

        this.packetHandler = newHandler;
        this.packetHandler.activated();
    }

    public HyProxyPlayer ensurePlayer() {
        if (this.player == null) {
            throw new IllegalStateException("tried to ensure player but player was null");
        }

        return this.player;
    }
    public boolean hasPlayer() {
        return this.player != null;
    }

    public void disconnect(String message) {
        this.disconnect(message, DisconnectType.DISCONNECT);
    }

    public void disconnect(String message, DisconnectType type) {
        this.send(new Disconnect(message, type)).addListener(ProtocolUtil.CLOSE_ON_COMPLETE);
        this.disconnected = true;
        if (this.hasPlayer()) {
            log.info("{} got disconnected: {}", this.getIdentifier(), message);
        }
    }

    public @Nullable ChannelFuture write(Object msg) {
        if (this.channel.isActive()) {
            return this.channel.writeAndFlush(msg);
        }

        ReferenceCountUtil.release(msg);
        return null;
    }

    public ChannelFuture send(Packet packet) {
        return this.write(packet);
    }

    public void close() {
        ProtocolUtil.closeConnection(this.channel);
    }
}
