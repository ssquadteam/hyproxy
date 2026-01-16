package town.kibty.hyproxy.io;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import town.kibty.hyproxy.player.HyProxyPlayer;
import town.kibty.hyproxy.util.NettyUtil;

@Slf4j
@RequiredArgsConstructor
@Getter
public class HytaleConnection extends ChannelInboundHandlerAdapter {
    private final Channel channel;
    private final HyProxy proxy;

    private @Nullable HytalePacketHandler packetHandler;
    @Setter
    private @Nullable HyProxyPlayer player;

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
        if (this.hasPlayer() && this.getPlayer().getInboundConnection() == this) {
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

        if (hasPlayer() && player.isAuthenticated()) {
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

    public boolean hasPlayer() {
        return this.player != null;
    }

    public void disconnect(String message) {
        this.disconnect(message, DisconnectType.DISCONNECT);
    }

    public void disconnect(String message, DisconnectType type) {
        this.send(new Disconnect(message, type)).addListener(ProtocolUtil.CLOSE_ON_COMPLETE);
        if (this.hasPlayer()) {
            log.info("{} got disconnected: {}", this.getIdentifier(), message);
        }
    }

    public ChannelFuture send(Packet packet) {
        return this.channel.writeAndFlush(packet);
    }

    public void close() {
        ProtocolUtil.closeConnection(this.channel);
    }
}
