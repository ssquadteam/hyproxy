package town.kibty.hyproxy.io;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import lombok.RequiredArgsConstructor;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.io.channel.InboundChannelInitializer;

import java.util.concurrent.TimeUnit;

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
                .streamHandler(new InboundChannelInitializer(this.proxy))
                .build();
        ctx.channel().pipeline().addLast(handler);
    }
}
