package town.kibty.hyproxy.io.handler.inbound;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.channel.OutboundChannelInitializer;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.impl.Disconnect;
import town.kibty.hyproxy.io.packet.impl.game.ChatMessage;
import town.kibty.hyproxy.player.HyProxyPlayer;

import java.util.Locale;

@RequiredArgsConstructor
@Slf4j
public class InboundForwardingPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.getPlayer();
        HyProxyBackend backend = player.getReferredBackend() != null ? player.getReferredBackend() : connection.getProxy().getInitialBackend();

        new Bootstrap()
                .group(connection.getChannel().eventLoop())
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : (KQueue.isAvailable() ? KQueueSocketChannel.class : NioSocketChannel.class))
                .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .handler(new OutboundChannelInitializer(connection.getProxy(), connection, backend))
                .connect(backend.getInfo().address());
    }

    @Override
    public boolean handle(ChatMessage chatMessage) {
        String message = chatMessage.getMessage();
        if (message == null) return false;

        if (!message.startsWith("/")) {
            return false;
        }

        return connection.getPlayer().performCommand(message.substring(1));
    }

    @Override
    public boolean handle(Disconnect disconnect) {
        log.info("{} {}ed: {}", this.connection.getIdentifier(), disconnect.getType().name().toLowerCase(Locale.ROOT), disconnect.getReason());
        return false;
    }

    @Override
    public void handleGeneric(Packet packet) {
        HyProxyPlayer player = connection.getPlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.sendAsPlayer(packet);
    }

    @Override
    public void handleUnknown(ByteBuf buf) {
        HyProxyPlayer player = connection.getPlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.getOutboundConnection().getChannel().writeAndFlush(buf.retain());
    }

    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.getPlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.getOutboundConnection().getChannel().disconnect();
    }
}
