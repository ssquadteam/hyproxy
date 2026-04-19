package ac.eva.hyproxy.io.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.PacketDecoder;
import ac.eva.hyproxy.io.PacketEncoder;
import ac.eva.hyproxy.io.handler.inbound.InboundInitialPacketHandler;
import ac.eva.hyproxy.util.NettyUtil;

import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class InboundChannelInitializer extends ChannelInitializer<Channel> {
    private final HyProxy proxy;

    @Override
    protected void initChannel(Channel ch) {
        QuicStreamChannel channel = (QuicStreamChannel) ch;
        log.info("got stream {} to {}", NettyUtil.formatRemoteAddress(channel), NettyUtil.formatLocalAddress(channel));

        QuicChannel parentChannel = channel.parent();

        X509Certificate clientCert = parentChannel.attr(HyProxy.CLIENT_CERTIFICATE_ATTR).get();
        if (clientCert != null) {
            channel.attr(HyProxy.CLIENT_CERTIFICATE_ATTR).set(clientCert);
        }

        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(10L, TimeUnit.SECONDS));
        channel.pipeline().addLast("decoder", new PacketDecoder());
        channel.pipeline().addLast("encoder", new PacketEncoder());

        HytaleConnection connection = new HytaleConnection(channel, proxy);
        connection.setPacketHandler(new InboundInitialPacketHandler(connection));
        channel.pipeline().addLast("handler", connection);
    }
}
