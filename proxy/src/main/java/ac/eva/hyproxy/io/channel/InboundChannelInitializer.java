package ac.eva.hyproxy.io.channel;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.PacketDecoder;
import ac.eva.hyproxy.io.PacketEncoder;
import ac.eva.hyproxy.io.handler.inbound.InboundInitialPacketHandler;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.player.HyProxyPlayer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.cert.X509Certificate;

@Slf4j
@RequiredArgsConstructor
public class InboundChannelInitializer extends ChannelInitializer<QuicStreamChannel> {
    private final HyProxy proxy;

    @Override
    protected void initChannel(QuicStreamChannel channel) {
        long streamId = channel.streamId();
        NetworkChannel networkChannel = NetworkChannel.fromStreamId(streamId);
        QuicChannel parentChannel = channel.parent();
        // log.info("got stream {} to {}", NettyUtil.formatRemoteAddress(channel), NettyUtil.formatLocalAddress(channel));

        X509Certificate clientCert = parentChannel.attr(HyProxy.CLIENT_CERTIFICATE_ATTR).get();
        if (clientCert != null) {
            channel.attr(HyProxy.CLIENT_CERTIFICATE_ATTR).set(clientCert);
        }

        // channel.pipeline().addLast("timeout", new ReadTimeoutHandler(10L, TimeUnit.SECONDS));
        channel.pipeline().addLast("decoder", new PacketDecoder());
        channel.pipeline().addLast("encoder", new PacketEncoder());

        HytaleConnection connection = parentChannel.attr(HyProxy.HYTALE_CONNECTION_ATTR).get();
        if (connection == null) {
            connection = new HytaleConnection(channel.parent(), proxy);
            connection.setPacketHandler(new InboundInitialPacketHandler(connection));
            parentChannel.attr(HyProxy.HYTALE_CONNECTION_ATTR).set(connection);
        }

        if (streamId != 0 && !channel.isLocalCreated()) {
            connection.setQuicStream(NetworkChannel.VOICE, channel);

            HyProxyPlayer player = connection.getPlayer();
            if (player != null && player.hasActiveOutboundConnection()) {
                OutboundChannelInitializer handler = new OutboundChannelInitializer(
                        proxy,
                        connection,
                        player.getConnectedBackend()
                );
                handler.setForcedNetworkChannel(NetworkChannel.VOICE);
                player.getOutboundConnection().getChannel().createStream(channel.type(), handler);
            } else {
                log.warn("auxiliary stream opened without outbound connection, cannot relay");
            }
        } else {
            if (networkChannel == null) {
                throw new IllegalStateException("networkChannel is null");
            }

            connection.setQuicStream(networkChannel, channel);
        }
        channel.pipeline().addLast("handler", connection);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ProtocolUtil.closeConnection(ctx.channel());
    }
}
