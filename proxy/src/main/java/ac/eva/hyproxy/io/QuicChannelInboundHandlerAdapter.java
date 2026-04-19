package ac.eva.hyproxy.io;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.channel.InboundChannelInitializer;
import ac.eva.hyproxy.io.packet.impl.Disconnect;
import ac.eva.hyproxy.io.proto.DisconnectType;

import javax.net.ssl.SSLEngine;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class QuicChannelInboundHandlerAdapter extends ChannelInboundHandlerAdapter {
    private final QuicSslContext sslContext;
    private final HyProxy proxy;

    @Override
    public boolean isSharable() {
        return true;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ChannelHandler handler = new QuicServerCodecBuilder()
                .sslContext(this.sslContext)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .ackDelayExponent(3L)
                .initialMaxData(524288L)
                .initialMaxStreamDataUnidirectional(0L)
                .initialMaxStreamDataBidirectionalLocal(131072L)
                .initialMaxStreamDataBidirectionalRemote(131072L)
                .initialMaxStreamsBidirectional(1L)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public boolean isSharable() {
                        return true;
                    }

                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        QuicChannel channel = (QuicChannel) ctx.channel();
                        X509Certificate clientCert = extractClientCertificate(channel);
                        if (clientCert == null) {
                            ProtocolUtil.closeConnection(channel);
                            return;
                        }

                        channel.attr(HyProxy.CLIENT_CERTIFICATE_ATTR).set(clientCert);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        log.error("error in channel initializer", cause);

                        Channel channel = ctx.channel();
                        if (channel.isWritable()) {
                            channel.writeAndFlush(new Disconnect("Internal proxy error!", DisconnectType.CRASH)).addListener(ProtocolUtil.CLOSE_ON_COMPLETE);
                            return;
                        }

                        ProtocolUtil.closeApplicationConnection(channel);
                    }
                })
                .streamHandler(new InboundChannelInitializer(this.proxy))
                .build();
        ctx.channel().pipeline().addLast(handler);
    }

    private @Nullable X509Certificate extractClientCertificate(QuicChannel channel) {
        try {
            SSLEngine sslEngine = channel.sslEngine();
            if (sslEngine == null) {
                return null;
            }

            Certificate[] peerCerts = sslEngine.getSession().getPeerCertificates();
            if (peerCerts != null && peerCerts.length > 0 && peerCerts[0] instanceof X509Certificate) {
                return (X509Certificate)peerCerts[0];
            }
        } catch (Exception _) {

        }

        return null;
    }
}
