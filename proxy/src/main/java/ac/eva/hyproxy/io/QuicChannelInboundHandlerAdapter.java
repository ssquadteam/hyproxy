package ac.eva.hyproxy.io;

import ac.eva.hyproxy.io.packet.impl.ServerDisconnect;
import ac.eva.hyproxy.message.Message;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.channel.InboundChannelInitializer;
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
                .activeMigration(false)
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .ackDelayExponent(3L)
                .initialMaxData(512 * 1024)
                .initialMaxStreamDataUnidirectional(0)
                .initialMaxStreamsUnidirectional(0)
                .initialMaxStreamDataBidirectionalLocal(128 * 1024)
                .initialMaxStreamDataBidirectionalRemote(128 * 1024)
                .initialMaxStreamsBidirectional(8)
                .discoverPmtu(true)
                .congestionControlAlgorithm(QuicCongestionControlAlgorithm.BBR)
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
                            channel.writeAndFlush(new ServerDisconnect(Message.raw("Internal proxy error!").getFormatted(), DisconnectType.CRASH)).addListener(ProtocolUtil.CLOSE_ON_COMPLETE);
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
