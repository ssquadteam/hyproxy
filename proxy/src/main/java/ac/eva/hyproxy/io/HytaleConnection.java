package ac.eva.hyproxy.io;

import ac.eva.hyproxy.io.packet.impl.ServerDisconnect;
import ac.eva.hyproxy.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.DisconnectType;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.player.HyProxyPlayer;
import ac.eva.hyproxy.util.NettyUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@Getter
@ChannelHandler.Sharable
public class HytaleConnection extends ChannelInboundHandlerAdapter {
    private final QuicChannel channel;
    private final HyProxy proxy;
    private final Map<NetworkChannel, QuicStreamChannel> streams = new ConcurrentHashMap<>();
    private final Map<Long, NetworkChannel> channelsByStreamId = new ConcurrentHashMap<>();

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
            QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
            long streamId = streamChannel.streamId();
            NetworkChannel networkChannel = this.channelsByStreamId.getOrDefault(streamId, NetworkChannel.DEFAULT);

            if (msg instanceof Packet packet) {
                if (!packet.handle(this.packetHandler)) {
                    this.packetHandler.handleGeneric(networkChannel, packet);
                }
            } else if (msg instanceof ByteBuf buf) {
                this.packetHandler.handleUnknown(networkChannel, buf);
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
        ChannelFuture future = this.send(new ServerDisconnect(Message.raw(message).getFormatted(), type));
        this.disconnected = true;
        if (future != null) {
            future.addListener(ProtocolUtil.CLOSE_ON_COMPLETE);
        } else {
            ProtocolUtil.closeConnection(this.channel);
        }
        if (this.hasPlayer()) {
            log.info("{} got disconnected: {}", this.getIdentifier(), message);
        }
    }

    public void setQuicStream(NetworkChannel channel, QuicStreamChannel stream) {
        this.streams.put(channel, stream);
        this.channelsByStreamId.put(stream.streamId(), channel);
    }

    public @Nullable ChannelFuture write(Object msg) {
        return this.write(NetworkChannel.DEFAULT, msg);
    }

    public @Nullable ChannelFuture write(NetworkChannel channel, Object msg) {
        QuicStreamChannel stream = this.streams.get(channel);

        if (stream != null && stream.isActive()) {
            return stream.writeAndFlush(msg);
        }

        ReferenceCountUtil.release(msg);
        return null;
    }

    public @Nullable ChannelFuture send(NetworkChannel channel, Packet packet) {
        return this.write(channel, packet);
    }

    public @Nullable ChannelFuture send(Packet packet) {
        return this.write(packet);
    }

    public void close() {
        ProtocolUtil.closeConnection(this.channel);
    }

    public void closeApplication() {
        ProtocolUtil.closeApplicationConnection(this.channel);
    }
}
