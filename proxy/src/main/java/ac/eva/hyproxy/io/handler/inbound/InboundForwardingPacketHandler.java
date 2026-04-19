package ac.eva.hyproxy.io.handler.inbound;

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
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.channel.OutboundChannelInitializer;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.Disconnect;
import ac.eva.hyproxy.io.packet.impl.game.ChatMessage;
import ac.eva.hyproxy.player.HyProxyPlayer;

import java.util.Locale;

@RequiredArgsConstructor
@Slf4j
public class InboundForwardingPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
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

        return connection.ensurePlayer().performCommand(message.substring(1));
    }

    @Override
    public boolean handle(Disconnect disconnect) {
        log.info("{} {}ed: {}", this.connection.getIdentifier(), disconnect.getType().name().toLowerCase(Locale.ROOT), disconnect.getReason());
        return false;
    }

    @Override
    public void handleGeneric(Packet packet) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.sendAsPlayer(packet);
    }

    @Override
    public void handleUnknown(ByteBuf buf) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.getOutboundConnection().write(buf);
    }

    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        player.getOutboundConnection().getChannel().disconnect();
    }
}
