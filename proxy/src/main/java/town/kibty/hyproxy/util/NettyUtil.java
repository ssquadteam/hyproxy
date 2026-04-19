package town.kibty.hyproxy.util;

import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamPriority;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.experimental.UtilityClass;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.PacketDecoder;
import town.kibty.hyproxy.io.PacketEncoder;
import town.kibty.hyproxy.io.proto.NetworkChannel;
import town.kibty.hyproxy.player.HyProxyPlayer;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

@UtilityClass
public class NettyUtil {
    public static CompletableFuture<Void> createForwardingStream(HytaleConnection connection, QuicChannel conn, QuicStreamType streamType, NetworkChannel networkChannel, QuicStreamPriority priority, HyProxyPlayer player) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        conn.createStream(streamType, new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                //channel.pipeline().addLast("timeout", new ReadTimeoutHandler(10L, TimeUnit.SECONDS));
                channel.pipeline().addLast("decoder", new PacketDecoder());
                channel.pipeline().addLast("encoder", new PacketEncoder());
            }
        }).addListener(result -> {
            if (!result.isSuccess()) {
                future.completeExceptionally(result.cause());
                return;
            }

            QuicStreamChannel channel = (QuicStreamChannel) result.getNow();
            channel.attr(HyProxy.STREAM_CHANNEL_KEY).set(networkChannel);
            if (priority != null) {
                channel.updatePriority(priority);
            }

            connection.ensurePlayer().setQuicChannel(networkChannel, channel);
            channel.pipeline().addLast("handler", connection);
            future.complete(null);
        });
        return future;
    }

    public EventLoopGroup getEventLoopGroup(String name) {
        return getEventLoopGroup(0, name);
    }

    public EventLoopGroup getEventLoopGroup(int nThreads, String name) {
        if (nThreads == 0)
            nThreads = Math.max(1, SystemPropertyUtil.getInt("server.io.netty.eventLoopThreads", Runtime.getRuntime().availableProcessors() * 2));
        ThreadFactory factory = ThreadUtil.daemonCounted(name + " - %d");
        if (Epoll.isAvailable())
            return new EpollEventLoopGroup(nThreads, factory);
        if (KQueue.isAvailable())
            return new KQueueEventLoopGroup(nThreads, factory);
        return new NioEventLoopGroup(nThreads, factory);
    }

    public Class<? extends Channel> getServerChannel() {
        if (Epoll.isAvailable())
            return EpollServerSocketChannel.class;
        if (KQueue.isAvailable())
            return KQueueServerSocketChannel.class;
        return NioServerSocketChannel.class;
    }

    public ReflectiveChannelFactory<? extends DatagramChannel> getDatagramChannelFactory(SocketProtocolFamily family) {
        if (Epoll.isAvailable())
            return new ReflectiveChannelFactory<>(EpollDatagramChannel.class, family);
        if (KQueue.isAvailable())
            return new ReflectiveChannelFactory<>(KQueueDatagramChannel.class, family);
        return new ReflectiveChannelFactory<>(NioDatagramChannel.class, family);
    }

    public String formatRemoteAddress(Channel channel) {
        if (channel instanceof QuicChannel quicChannel) {
            return quicChannel.remoteAddress() + " (" + quicChannel.remoteAddress() + ")";
        }
        if (channel instanceof QuicStreamChannel quicStreamChannel) {
            return quicStreamChannel.parent().localAddress() + " (" + quicStreamChannel.parent().localAddress() + ", streamId=" + quicStreamChannel.parent().remoteSocketAddress() + ")";
        }
        return channel.remoteAddress().toString();
    }

    public String formatLocalAddress(Channel channel) {
        if (channel instanceof QuicChannel quicChannel) {
            return quicChannel.localAddress() + " (" + quicChannel.localAddress() + ")";
        }
        if (channel instanceof QuicStreamChannel quicStreamChannel) {
            return quicStreamChannel.parent().localAddress() + " (" + quicStreamChannel.parent().localAddress() + ", streamId=" + quicStreamChannel.parent().localSocketAddress() + ")";
        }
        return channel.localAddress().toString();
    }

    public SocketAddress getRemoteSocketAddress(Channel channel) {
        if (channel instanceof QuicChannel quicChannel) {
            return quicChannel.remoteSocketAddress();
        }
        if (channel instanceof QuicStreamChannel quicStreamChannel) {
            return quicStreamChannel.parent().remoteSocketAddress();
        }
        return channel.remoteAddress();
    }

    public boolean isFromSameOrigin(Channel channel1, Channel channel2) {
        SocketAddress remoteSocketAddress1 = getRemoteSocketAddress(channel1);
        SocketAddress remoteSocketAddress2 = getRemoteSocketAddress(channel2);
        if (remoteSocketAddress1 == null || remoteSocketAddress2 == null)
            return false;
        if (Objects.equals(remoteSocketAddress1, remoteSocketAddress2))
            return true;
        if (!remoteSocketAddress1.getClass().equals(remoteSocketAddress2.getClass()))
            return false;
        if (remoteSocketAddress1 instanceof InetSocketAddress remoteInetSocketAddress1) {
            if (remoteSocketAddress2 instanceof InetSocketAddress remoteInetSocketAddress2) {
                if (remoteInetSocketAddress1.getAddress().isLoopbackAddress() && remoteInetSocketAddress2.getAddress().isLoopbackAddress())
                    return true;
                return remoteInetSocketAddress1.getAddress().equals(remoteInetSocketAddress2.getAddress());
            }
        }
        return false;
    }

    public static class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {
        private final Constructor<? extends T> constructor;

        private final SocketProtocolFamily family;

        public ReflectiveChannelFactory(Class<? extends T> clazz, SocketProtocolFamily family) {
            try {
                this.constructor = clazz.getConstructor(SocketProtocolFamily.class);
                this.family = family;
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("class " + StringUtil.simpleClassName(clazz) + " does not have a public non-arg constructor", e);
            }
        }

        public T newChannel() {
            try {
                return this.constructor.newInstance(this.family);
            } catch (Throwable t) {
                throw new ChannelException("unable to create Channel from class " + this.constructor.getDeclaringClass(), t);
            }
        }

        public String toString() {
            return StringUtil.simpleClassName(io.netty.channel.ReflectiveChannelFactory.class) + "(" + StringUtil.simpleClassName(io.netty.channel.ReflectiveChannelFactory.class) + ".class, " +
                    StringUtil.simpleClassName(this.constructor.getDeclaringClass()) + ")";
        }
    }
}