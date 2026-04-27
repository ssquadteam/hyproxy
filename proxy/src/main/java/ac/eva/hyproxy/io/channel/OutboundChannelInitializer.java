package ac.eva.hyproxy.io.channel;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.PacketDecoder;
import ac.eva.hyproxy.io.PacketEncoder;
import ac.eva.hyproxy.io.handler.outbound.OutboundInitialPacketHandler;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.player.HyProxyPlayer;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.quic.QuicStreamChannel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
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
        if (networkChannel == null) {
            log.warn("rejected unknown outbound stream {} for backend {}", streamId, backend.getInfo().id());
            channel.close();
            return;
        }

        channel.pipeline().addLast("decoder", new PacketDecoder());
        channel.pipeline().addLast("encoder", new PacketEncoder());

        HyProxyPlayer player = inboundConnection.ensurePlayer();
        HytaleConnection outboundConnection = null;
        if (player.isSeamlessSwitching() && player.getPendingSeamlessBackend() == backend) {
            outboundConnection = player.getPendingOutboundConnection();
        } else if (!player.isSeamlessSwitching()
                && (player.getConnectedBackend() == null || player.getConnectedBackend() == backend)) {
            outboundConnection = player.getOutboundConnection();
        }

        if (outboundConnection == null) {
            if (player.isSeamlessSwitching() && player.getPendingSeamlessBackend() != backend) {
                String pendingBackendId = player.getPendingSeamlessBackend() == null ? "<unknown>" : player.getPendingSeamlessBackend().getInfo().id();
                log.warn("{} rejected late outbound stream for backend {} while seamless handoff to {} is pending",
                        player.getIdentifier(),
                        backend.getInfo().id(),
                        pendingBackendId);
                channel.close();
                return;
            }

            if (!player.isSeamlessSwitching() && player.getConnectedBackend() != null && player.getConnectedBackend() != backend) {
                log.warn("{} rejected late outbound stream for backend {} while connected to {}",
                        player.getIdentifier(),
                        backend.getInfo().id(),
                        player.getConnectedBackend().getInfo().id());
                channel.close();
                return;
            }

            outboundConnection = new HytaleConnection(channel.parent(), proxy);
            outboundConnection.setPlayer(inboundConnection.getPlayer());
            if (player.isSeamlessSwitching()) {
                outboundConnection.ensurePlayer().setPendingOutboundConnection(outboundConnection);
            } else {
                outboundConnection.ensurePlayer().setOutboundConnection(outboundConnection);
            }
        }

        if (networkChannel == NetworkChannel.DEFAULT && outboundConnection.getPacketHandler() == null) {
            outboundConnection.setPacketHandler(new OutboundInitialPacketHandler(outboundConnection, backend));
        }

        if (outboundConnection.getPacketHandler() == null && networkChannel != NetworkChannel.DEFAULT) {
            log.debug("{} accepted backend {} {} stream before DEFAULT",
                    player.getIdentifier(),
                    backend.getInfo().id(),
                    networkChannel == null ? "<unknown>" : networkChannel);
        }

        outboundConnection.setQuicStream(networkChannel, channel);

        if (networkChannel != NetworkChannel.DEFAULT) {
            this.inboundConnection.getChannel().createStream(channel.type(), new InboundChannelInitializer(proxy));
        }

        channel.pipeline().addLast("handler", outboundConnection);
    }
}
