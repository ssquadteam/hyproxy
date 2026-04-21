package ac.eva.hyproxy.io.handler.inbound;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.handler.codec.quic.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.channel.OutboundChannelInitializer;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.game.ChatMessage;
import ac.eva.hyproxy.player.HyProxyPlayer;
import ac.eva.hyproxy.util.NettyUtil;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
public class InboundForwardingPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        HyProxyBackend backend = player.getReferredBackend() != null ? player.getReferredBackend() : connection.getProxy().getInitialBackend();

        InetSocketAddress address = backend.getInfo().address();
        SocketProtocolFamily family = address.getAddress() instanceof Inet6Address
                ? SocketProtocolFamily.INET6 : SocketProtocolFamily.INET;

        HyProxy proxy = player.getProxy();
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .applicationProtocols("hytale/2")
                .keyManager(proxy.getCertificate().key(), null, proxy.getCertificate().cert())
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

        ChannelHandler codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .initialMaxData(512 * 1024)
                .initialMaxStreamDataBidirectionalLocal( 128 * 1024)
                .initialMaxStreamDataBidirectionalRemote(128 * 1024)
                .initialMaxStreamDataUnidirectional(128 * 1024)
                .initialMaxStreamsBidirectional(4L)
                .initialMaxStreamsUnidirectional(2L)
                .activeMigration(false)
                .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC)
                .discoverPmtu(true)
                .build();

        new Bootstrap()
                .group(connection.getChannel().eventLoop())
                .channelFactory(NettyUtil.getDatagramChannelFactory(family))
                .handler(codec)
                .bind(0)
                .addListener((ChannelFutureListener) bindFuture -> {
                    if (!bindFuture.isSuccess()) {
                        log.error("failed to bind datagram channel for backend {}", backend.getInfo().id(), bindFuture.cause());
                        return;
                    }

                    Channel datagramChannel = bindFuture.channel();
                    QuicChannel.newBootstrap(datagramChannel)
                            .streamHandler(new OutboundChannelInitializer(proxy, connection, backend))
                            .remoteAddress(address)
                            .connect()
                            .addListener((GenericFutureListener<Future<QuicChannel>>) quicFuture -> {
                                if (!quicFuture.isSuccess()) {
                                    log.error("failed to connect quic channel to backend {}", backend.getInfo().id(), quicFuture.cause());
                                    datagramChannel.close();
                                    return;
                                }

                                QuicChannel quicChannel = quicFuture.getNow();
                                quicChannel.closeFuture().addListener((ChannelFutureListener) _ -> datagramChannel.close());
                                quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new OutboundChannelInitializer(connection.getProxy(), connection, backend));
                            });
                });
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

        if (!player.hasActiveOutboundConnection()) return;
        player.getOutboundConnection().write(channel, buf.retain());
    }

    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveOutboundConnection()) return;
        ProtocolUtil.closeConnection(player.getOutboundConnection().getChannel());
    }
}
