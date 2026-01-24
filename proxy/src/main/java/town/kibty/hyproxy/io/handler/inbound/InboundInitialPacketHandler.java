package town.kibty.hyproxy.io.handler.inbound;

import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.common.util.SecretMessageUtil;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.packet.impl.Disconnect;
import town.kibty.hyproxy.io.packet.impl.auth.Connect;
import town.kibty.hyproxy.player.HyProxyPlayer;

import java.util.Locale;

@Slf4j
@RequiredArgsConstructor
public class InboundInitialPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;

    @Override
    public boolean handle(Connect connect) {
        if (connection.getProxy().getPlayerByProfileId(connect.getUuid()) != null) {
            connection.disconnect("You are already connected to this proxy!");
            return true;
        }

        HyProxyPlayer player = new HyProxyPlayer(connection.getProxy(), connection);

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

            player.setReferredBackend(backend);
        }

        connection.setPlayer(player);

        connection.getProxy().registerPlayer(player);

        log.info("authenticating player {}", this.connection.getIdentifier());
        connection.setPacketHandler(new InboundAuthPacketHandler(this.connection));
        return true;
    }

    @Override
    public boolean handle(Disconnect disconnect) {
        log.info("{} {}ed: {}", this.connection.getIdentifier(), disconnect.getType().name().toLowerCase(Locale.ROOT), disconnect.getReason());
        return true;
    }
}
