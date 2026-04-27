package ac.eva.hyproxy.io;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.channel.OutboundChannelInitializer;
import ac.eva.hyproxy.util.NettyUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public final class BackendConnector {
    private BackendConnector() {
    }

    public static void connect(HytaleConnection inboundConnection, HyProxyBackend backend) {
        connect(inboundConnection, backend, cause -> {
        });
    }

    public static void connect(HytaleConnection inboundConnection, HyProxyBackend backend, Consumer<Throwable> onFailure) {
        InetSocketAddress address = backend.getInfo().address();
        SocketProtocolFamily family = address.getAddress() instanceof Inet6Address
                ? SocketProtocolFamily.INET6 : SocketProtocolFamily.INET;

        HyProxy proxy = inboundConnection.getProxy();
        QuicSslContext sslContext;
        try {
            sslContext = QuicSslContextBuilder.forClient()
                    .applicationProtocols("hytale/2")
                    .keyManager(proxy.getCertificate().key(), null, proxy.getCertificate().cert())
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } catch (Exception exception) {
            onFailure.accept(exception);
            return;
        }

        ChannelHandler codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .initialMaxData(512 * 1024)
                .initialMaxStreamDataBidirectionalLocal(128 * 1024)
                .initialMaxStreamDataBidirectionalRemote(128 * 1024)
                .initialMaxStreamDataUnidirectional(128 * 1024)
                .initialMaxStreamsBidirectional(4L)
                .initialMaxStreamsUnidirectional(2L)
                .activeMigration(false)
                .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC)
                .discoverPmtu(true)
                .build();

        new Bootstrap()
                .group(inboundConnection.getChannel().eventLoop())
                .channelFactory(NettyUtil.getDatagramChannelFactory(family))
                .handler(codec)
                .bind(0)
                .addListener((ChannelFutureListener) bindFuture -> {
                    if (!bindFuture.isSuccess()) {
                        log.error("failed to bind datagram channel for backend {}", backend.getInfo().id(), bindFuture.cause());
                        onFailure.accept(bindFuture.cause());
                        return;
                    }

                    Channel datagramChannel = bindFuture.channel();
                    QuicChannel.newBootstrap(datagramChannel)
                            .streamHandler(new OutboundChannelInitializer(proxy, inboundConnection, backend))
                            .remoteAddress(address)
                            .connect()
                            .addListener((GenericFutureListener<Future<QuicChannel>>) quicFuture -> {
                                if (!quicFuture.isSuccess()) {
                                    log.error("failed to connect quic channel to backend {}", backend.getInfo().id(), quicFuture.cause());
                                    datagramChannel.close();
                                    onFailure.accept(quicFuture.cause());
                                    return;
                                }

                                QuicChannel quicChannel = quicFuture.getNow();
                                quicChannel.closeFuture().addListener((ChannelFutureListener) _ -> datagramChannel.close());
                                quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new OutboundChannelInitializer(proxy, inboundConnection, backend));
                            });
                });
    }
}
