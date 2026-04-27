package ac.eva.hyproxy.io.handler.inbound;

import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.common.util.SecretMessageUtil;
import ac.eva.hyproxy.event.impl.player.PlayerPreAuthConnectEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.impl.auth.Connect;
import ac.eva.hyproxy.player.HyProxyPlayer;

import java.util.Locale;

@Slf4j
@RequiredArgsConstructor
public class InboundInitialPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;

    @Override
    public boolean handle(Connect connect) {
        HyProxyPlayer player = new HyProxyPlayer(connection.getProxy(), connection);
        HyProxyBackend referredBackend = null;

        player.setProtocolCrc(connect.getProtocolCrc());
        player.setProtocolBuildNumber(connect.getProtocolBuildNumber());
        player.setClientVersion(connect.getClientVersion());
        player.setProfileId(connect.getUuid());
        player.setUsername(connect.getUsername());
        player.setIdentityToken(connect.getIdentityToken());
        player.setLanguage(connect.getLanguage());
        player.setClientType(connect.getClientType());

        byte[] referralData = connect.getReferralData();
        if (referralData != null) {
            SecretMessageUtil.BackendReferralMessage referralMessage = SecretMessageUtil.validateAndDecodeReferralData(
                    Unpooled.copiedBuffer(referralData),
                    connect.getUuid(),
                    connection.getProxy().getConfiguration().getProxySecret()
            );

            if (referralMessage == null) {
                connection.disconnect("invalid referral data");
                return true;
            }

            HyProxyBackend backend = connection.getProxy().getBackendById(referralMessage.backendId());

            if (backend == null) {
                connection.disconnect("invalid referral backend");
                return true;
            }

            referredBackend = backend;
        }

        HyProxyPlayer existingPlayer = connection.getProxy().getPlayerByProfileId(connect.getUuid(), true);
        if (existingPlayer != null) {
            if (referredBackend == null) {
                connection.disconnect("You are already connected to this proxy!");
                return true;
            }

            log.info(
                    "replacing existing connection for {} during referral to backend {}",
                    existingPlayer.getIdentifier(),
                    referredBackend.getInfo().id()
            );
            existingPlayer.onDisconnect();
            existingPlayer.getInboundConnection().setPlayer(null);
            if (existingPlayer.getOutboundConnection() != null) {
                existingPlayer.getOutboundConnection().setPlayer(null);
                existingPlayer.getOutboundConnection().close();
            }
            existingPlayer.getInboundConnection().close();
        }

        if (referredBackend != null) {
            player.setReferredBackend(referredBackend);
        }

        PlayerPreAuthConnectEvent event = connection.getProxy().getEventBus().fire(new PlayerPreAuthConnectEvent(
                player,
                false
        ));

        if (event.isCanceled()) {
            if (connection.isDisconnected()) {
                return true;
            }

            connection.disconnect("proxy pre-auth connect event cancelled without disconnect");
            return true;
        }

        if (connection.isDisconnected()) {
            return true;
        }

        connection.setPlayer(player);

        connection.getProxy().registerPlayer(player);

        log.info("authenticating player {}", this.connection.getIdentifier());
        connection.setPacketHandler(new InboundAuthPacketHandler(this.connection));
        return true;
    }

    @Override
    public boolean handle(ClientDisconnect serverDisconnect) {
        log.info("{} {}ed: {}", this.connection.getIdentifier(), serverDisconnect.getType().name().toLowerCase(Locale.ROOT), serverDisconnect.getReason());
        return true;
    }
}
