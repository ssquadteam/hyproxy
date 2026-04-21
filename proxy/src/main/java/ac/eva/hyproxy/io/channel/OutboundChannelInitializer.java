package ac.eva.hyproxy.io.channel;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.PacketDecoder;
import ac.eva.hyproxy.io.PacketEncoder;
import ac.eva.hyproxy.io.handler.outbound.OutboundInitialPacketHandler;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.quic.QuicStreamChannel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@RequiredArgsConstructor
public class OutboundChannelInitializer extends ChannelInitializer<QuicStreamChannel> {
    private final HyProxy proxy;
    private final HytaleConnection inboundConnection;
    private final HyProxyBackend backend;
    @Setter
    private NetworkChannel forcedNetworkChannel = null;

    @Override
    protected void initChannel(QuicStreamChannel channel) {
        long streamId = channel.streamId();
        NetworkChannel networkChannel = forcedNetworkChannel != null ? forcedNetworkChannel : NetworkChannel.fromStreamId(streamId);

        channel.pipeline().addLast("decoder", new PacketDecoder());
        channel.pipeline().addLast("encoder", new PacketEncoder());

        HytaleConnection outboundConnection = inboundConnection.ensurePlayer().getOutboundConnection();
        if (outboundConnection == null && networkChannel == NetworkChannel.DEFAULT) {
            outboundConnection = new HytaleConnection(channel.parent(), proxy);
            outboundConnection.setPlayer(inboundConnection.getPlayer());
            outboundConnection.ensurePlayer().setOutboundConnection(outboundConnection);
            outboundConnection.setPacketHandler(new OutboundInitialPacketHandler(outboundConnection, backend));
        }

        if (outboundConnection == null) {
            throw new IllegalStateException("network channel %s initialized before DEFAULT".formatted(networkChannel == null ? "<unknown>" : networkChannel.toString()));
        }

        outboundConnection.setQuicStream(networkChannel, channel);

        if (networkChannel != NetworkChannel.DEFAULT) {
            this.inboundConnection.getChannel().createStream(channel.type(), new InboundChannelInitializer(proxy));
        }

        channel.pipeline().addLast("handler", outboundConnection);
    }
}
