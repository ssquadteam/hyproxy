package town.kibty.hyproxy;

import com.google.common.collect.ImmutableList;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import jdk.net.ExtendedSocketOptions;
import kong.unirest.core.Unirest;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.auth.HytaleAccountDataServiceClient;
import town.kibty.hyproxy.auth.HytaleOAuthServiceClient;
import town.kibty.hyproxy.auth.HytaleSessionServiceClient;
import town.kibty.hyproxy.auth.JWTVerifier;
import town.kibty.hyproxy.backend.BackendInfo;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.command.HyProxyCommandManager;
import town.kibty.hyproxy.command.provided.ReloadCommand;
import town.kibty.hyproxy.command.provided.SendCommand;
import town.kibty.hyproxy.command.provided.ShutdownCommand;
import town.kibty.hyproxy.config.HyProxyConfiguration;
import town.kibty.hyproxy.console.HyProxyConsole;
import town.kibty.hyproxy.io.QuicChannelInboundHandlerAdapter;
import town.kibty.hyproxy.player.HyProxyPlayer;
import town.kibty.hyproxy.player.permission.OperatorsPlayerPermissionProvider;
import town.kibty.hyproxy.player.permission.PlayerPermissionProvider;
import town.kibty.hyproxy.util.AddressUtil;
import town.kibty.hyproxy.util.CertificateUtil;
import town.kibty.hyproxy.util.NettyUtil;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class HyProxy {
    public static final AttributeKey<X509Certificate> CLIENT_CERTIFICATE_ATTR = AttributeKey.valueOf("CLIENT_CERTIFICATE");
    private static final EventLoopGroup WORKER_GROUP = NettyUtil.getEventLoopGroup("hyproxy-worker-group");

    private Bootstrap bootstrapIpv4 = null;
    private Bootstrap bootstrapIpv6 = null;
    private final List<ChannelFuture> endpoints = new ArrayList<>();


    @Getter
    private HyProxyConfiguration configuration;
    private final HyProxyConsole console = new HyProxyConsole(this);

    @Getter
    private final HytaleSessionServiceClient sessionServiceClient = new HytaleSessionServiceClient(this);
    @Getter
    private final HytaleOAuthServiceClient oAuthServiceClient = new HytaleOAuthServiceClient(this);
    @Getter
    private final HytaleAccountDataServiceClient accountDataServiceClient = new HytaleAccountDataServiceClient();

    @Getter
    private final JWTVerifier jwtVerifier = new JWTVerifier(this);
    @Getter
    private X509Certificate serverCert;

    private final Map<UUID, HyProxyPlayer> playersByProfileId = new ConcurrentHashMap<>();
    private final Map<String, HyProxyPlayer> playersByUsername = new ConcurrentHashMap<>();
    private final Map<String, HyProxyBackend> backendsById = new ConcurrentHashMap<>();

    @Getter
    private final HyProxyCommandManager commandManager = new HyProxyCommandManager(this);

    private final List<PlayerPermissionProvider> playerPermissionProviders = new ArrayList<>();

    public HyProxy() {
        try {
            SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate("localhost");
            this.serverCert = selfSignedCertificate.cert();

            QuicSslContext sslContext = QuicSslContextBuilder
                    .forServer(selfSignedCertificate.key(), null, selfSignedCertificate.cert())
                    .applicationProtocols("hytale/1")
                    .earlyData(false).clientAuth(ClientAuth.REQUIRE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();

            NettyUtil.ReflectiveChannelFactory<? extends DatagramChannel> channelFactoryIpv4 = NettyUtil.getDatagramChannelFactory(SocketProtocolFamily.INET);
            this.bootstrapIpv4 = new Bootstrap()
                    .group(WORKER_GROUP)
                    .channelFactory(channelFactoryIpv4)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(NioChannelOption.of(ExtendedSocketOptions.IP_DONTFRAGMENT), true)
                    .handler(new QuicChannelInboundHandlerAdapter(sslContext, this))
                    .validate();

            NettyUtil.ReflectiveChannelFactory<? extends DatagramChannel> channelFactoryIpv6 = NettyUtil.getDatagramChannelFactory(SocketProtocolFamily.INET6);
            this.bootstrapIpv6 = new Bootstrap()
                    .group(WORKER_GROUP)
                    .channelFactory(channelFactoryIpv6)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(NioChannelOption.of(ExtendedSocketOptions.IP_DONTFRAGMENT), true)
                    .handler(new QuicChannelInboundHandlerAdapter(sslContext, this))
                    .validate();
        } catch (Throwable throwable) {
            log.error("failed to initialize hyproxy", throwable);
            System.exit(1);
        }
    }

    public void start() {
        try {
            log.info("starting hyproxy (github.com/xyzeva/hyproxy)");
            log.info("heavily inspired by velocity");

            Unirest.config()
                    .addDefaultHeader("user-agent", "hyproxy (github.com/xyzeva/hyproxy)");

            this.configuration = HyProxyConfiguration.load(this, Path.of("config.toml"));

            if (!this.configuration.validate()) {
                log.error("your configuration isn't valid, please read the logs above and fix all the errors.");
                this.shutdown(true);
                return;
            }

            this.registerInitialConfigBackends();
            this.registerProvidedCommands();
            this.addPlayerPermissionProvider(new OperatorsPlayerPermissionProvider());

            this.oAuthServiceClient.start();
            this.sessionServiceClient.start();

            endpoints.add(this.bootstrapIpv4.bind(this.configuration.getBind()).syncUninterruptibly());
            log.info("bound ipv4");

            if (this.configuration.isIpv6Support()) {
                endpoints.add(this.bootstrapIpv6.bind(this.configuration.getBind()).syncUninterruptibly());
                log.info("bound ipv6");
            }

            for (ChannelFuture endpoint : endpoints) {
                if (!endpoint.isSuccess()) {
                    log.error("failed to bind endpoint", endpoint.exceptionNow());
                    this.shutdown(true);
                    return;
                }
            }

            console.start();
        } catch (Throwable throwable) {
            log.error("failed to start hyproxy", throwable);
            System.exit(1);
        }
    }

    private void registerProvidedCommands() {
        commandManager.registerCloudAnnotationCommand(new SendCommand());
        commandManager.registerCloudAnnotationCommand(new ReloadCommand(this));
        commandManager.registerCloudAnnotationCommand(new ShutdownCommand());
    }

    public boolean reloadConfig() {
        try {
            HyProxyConfiguration newConfig = HyProxyConfiguration.load(this, Path.of("config.toml"));
            if (!newConfig.validate()) {
                return false;
            }

            if (!newConfig.getBind().equals(this.configuration.getBind())) {
                log.warn("hyproxy does not yet support changing bind via reloading config, your bind will not change until a restart!");
            }

            if (newConfig.isIpv6Support() != this.configuration.isIpv6Support()) {
                log.warn("hyproxy does not yet support enabling/disabling ipv6 via reloading config, ipv6 support will not change until a restart!");
            }

            for (Map.Entry<String, String> entry : newConfig.getBackends().entrySet()) {
                BackendInfo newInfo = new BackendInfo(
                        entry.getKey(),
                        AddressUtil.parseAndResolveAddress(entry.getValue())
                );
                HyProxyBackend existingBackend = this.getBackendById(newInfo.id());

                if (existingBackend == null) {
                    this.registerBackend(newInfo);
                    continue;
                }

                if (existingBackend.getInfo().equals(newInfo)) {
                    continue;
                }

                log.warn("existing backend {} will not be updated via config reload, please restart if you wan't to change the address of an existing server", existingBackend.getInfo().id());
            }

            this.configuration = newConfig;

            return true;
        } catch (Exception ex) {
            log.error("error while reloading config", ex);
            return false;
        }
    }

    public @NonNull HyProxyBackend getInitialBackend() {
        return backendsById.get(configuration.getInitialBackend().toLowerCase(Locale.ROOT));
    }

    public @Nullable HyProxyBackend getBackendById(String id) {
        return backendsById.get(id.toLowerCase(Locale.ROOT));
    }

    public void registerBackend(BackendInfo info) {
        if (this.getBackendById(info.id()) != null) {
            throw new IllegalArgumentException("backend id " + info.id() + " is already registered");
        }

        this.backendsById.put(info.id().toLowerCase(Locale.ROOT), new HyProxyBackend(info));
    }

    public void unregisterBackend(HyProxyBackend backend) {
        if (this.getBackendById(backend.getInfo().id()) == null) {
            throw new IllegalArgumentException("backend id " + backend.getInfo().id() + " is not registered");
        }

        this.backendsById.remove(backend.getInfo().id().toLowerCase(Locale.ROOT));
    }

    private void registerInitialConfigBackends() {
        int i = 0;
        for (Map.Entry<String, String> backendEntry : this.configuration.getBackends().entrySet()) {
            this.registerBackend(new BackendInfo(
                    backendEntry.getKey(),
                    AddressUtil.parseAndResolveAddress(backendEntry.getValue())
            ));
            i++;
        }
        log.info("registered {} config backends", i);
    }

    public void shutdown(boolean error) {
        log.info("hyproxy is shutting down");


        for (HyProxyPlayer player : this.getPlayers(true)) {
            player.disconnect("Proxy shutting down");
        }

        for (ChannelFuture endpoint : this.endpoints) {
            endpoint.channel().close();
        }

        System.exit(error ? 1 : 0);
    }


    public void addPlayerPermissionProvider(PlayerPermissionProvider provider) {
        this.playerPermissionProviders.add(provider);
        provider.initialize(this);
        // re-sort
        this.playerPermissionProviders.sort(Comparator.comparingInt(PlayerPermissionProvider::priority).reversed());

    }

    public void removePlayerPermissionProvider(PlayerPermissionProvider provider) {
        this.playerPermissionProviders.add(provider);
        // re-sort
        this.playerPermissionProviders.sort(Comparator.comparingInt(PlayerPermissionProvider::priority).reversed());
    }

    public List<PlayerPermissionProvider> getPlayerPermissionProviders() {
        return ImmutableList.copyOf(this.playerPermissionProviders);
    }

    public List<HyProxyPlayer> getPlayers() {
        return this.getPlayers(false);
    }

    public List<HyProxyPlayer> getPlayers(boolean includeNonAuthenticated) {
        Collection<HyProxyPlayer> allPlayers = this.playersByProfileId.values();
        if (includeNonAuthenticated) return ImmutableList.copyOf(allPlayers);

        List<HyProxyPlayer> activePlayers = new ArrayList<>();
        for (HyProxyPlayer player : allPlayers) {
            if (!player.isAuthenticated()) continue;
            activePlayers.add(player);
        }

        return ImmutableList.copyOf(activePlayers);
    }

    public @Nullable HyProxyPlayer getPlayerByProfileId(UUID profileId) {
        return this.getPlayerByProfileId(profileId, false);
    }

    public @Nullable HyProxyPlayer getPlayerByProfileId(UUID profileId, boolean includeNonAuthenticated) {
        HyProxyPlayer player = this.playersByProfileId.get(profileId);
        if (player == null) {
            return null;
        }

        if (includeNonAuthenticated) return player;
        if (player.isAuthenticated()) return player;

        return null;
     }

    public @Nullable HyProxyPlayer getPlayerByUsername(String username) {
        return this.getPlayerByUsername(username, false);
    }

    public @Nullable HyProxyPlayer getPlayerByUsername(String username, boolean includeNonAuthenticated) {
        HyProxyPlayer player = this.playersByUsername.get(username.toLowerCase(Locale.ROOT));
        if (player == null) {
            return null;
        }

        if (includeNonAuthenticated) return player;
        if (player.isAuthenticated()) return player;

        return null;
    }


    public void registerPlayer(HyProxyPlayer player) {
        if (this.getPlayerByProfileId(player.getProfileId(), true) != null) {
            throw new IllegalArgumentException("player profile id " + player.getProfileId() + " already registered");
        }
        this.playersByProfileId.put(player.getProfileId(), player);
        this.playersByUsername.put(player.getUsername().toLowerCase(Locale.ROOT), player);
    }

    public void unregisterPlayer(HyProxyPlayer player) {
        if (this.getPlayerByProfileId(player.getProfileId(), true) == null) {
            throw new IllegalArgumentException("player profile id " + player.getProfileId() + " not registered");
        }
        this.playersByProfileId.remove(player.getProfileId());
        this.playersByUsername.remove(player.getUsername().toLowerCase(Locale.ROOT));
    }

    public String getServerCertFingerprint() {
        return CertificateUtil.computeCertificateFingerprint(this.serverCert);
    }

    public void shutdown() {
        this.shutdown(false);
    }

}
