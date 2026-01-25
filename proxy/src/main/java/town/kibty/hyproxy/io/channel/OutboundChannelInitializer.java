package town.kibty.hyproxy.io.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.PacketDecoder;
import town.kibty.hyproxy.io.PacketEncoder;
import town.kibty.hyproxy.io.handler.outbound.OutboundInitialPacketHandler;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class OutboundChannelInitializer extends ChannelInitializer<Channel> {
    private final HyProxy proxy;
    private final HytaleConnection inboundConnection;
    private final HyProxyBackend backend;

    @Override
    protected void initChannel(Channel channel) {
        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(10L, TimeUnit.SECONDS));
        channel.pipeline().addLast("decoder", new PacketDecoder());
        channel.pipeline().addLast("encoder", new PacketEncoder());

        HytaleConnection outboundConnection = new HytaleConnection(channel, proxy);
        outboundConnection.setPlayer(inboundConnection.getPlayer());
        outboundConnection.ensurePlayer().setOutboundConnection(outboundConnection);

        outboundConnection.setPacketHandler(new OutboundInitialPacketHandler(outboundConnection, backend));
        channel.pipeline().addLast("handler", outboundConnection);
    }
}
