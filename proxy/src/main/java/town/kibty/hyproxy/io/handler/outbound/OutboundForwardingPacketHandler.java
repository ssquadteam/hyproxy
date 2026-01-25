package town.kibty.hyproxy.io.handler.outbound;

import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.common.util.ProxyCommunicationUtil;
import town.kibty.hyproxy.io.HytaleConnection;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.impl.auth.AuthGrant;
import town.kibty.hyproxy.io.packet.impl.setup.ServerInfo;
import town.kibty.hyproxy.message.HyProxyColors;
import town.kibty.hyproxy.message.Message;
import town.kibty.hyproxy.player.HyProxyPlayer;

@Slf4j
@RequiredArgsConstructor
public class OutboundForwardingPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;
    private final HyProxyBackend backend;

    @Override
    public void activated() {
        connection.ensurePlayer().setConnectedBackend(backend);
    }

    @Override
    public void handleGeneric(Packet packet) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.sendToPlayer(packet);
    }

    @Override
    public void handleUnknown(ByteBuf buf) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().getChannel().writeAndFlush(buf.retain());
    }

    @Override
    public boolean handle(AuthGrant passwordResponse) {
        if (passwordResponse.getServerIdentityToken() == null) return false;

        ProxyCommunicationUtil.ProxyCommunicationMessage message = ProxyCommunicationUtil.deserializeMessage(passwordResponse.getServerIdentityToken());

        if (message == null) return false;

        HyProxy proxy = connection.getProxy();
        // todo: move this somewhere else
        switch (message) {
            case ProxyCommunicationUtil.ProxyCommunicationMessage.SendToBackend sendToBackend -> {
                HyProxyPlayer target = proxy.getPlayerByProfileId(sendToBackend.targetId());
                HyProxyPlayer sender = proxy.getPlayerByProfileId(sendToBackend.senderId());

                if (target == null) {
                    if (sender != null) {
                        sender.sendMessage(Message.raw("The target isn't online on the network").color(HyProxyColors.ERROR_COLOR));
                    }

                    break;
                }

                HyProxyBackend targetBackend = proxy.getBackendById(sendToBackend.backendId());
                if (targetBackend == null) {
                    if (sender != null) {
                        sender.sendMessage(Message.raw("The specified backend id doesn't exist").color(HyProxyColors.ERROR_COLOR));
                    }

                    break;
                }

                target.sendPlayerToBackend(targetBackend);
                if (sender != null) {
                    sender.sendMessage(Message.empty()
                            .insert(Message.raw("Successfully sent ").color(HyProxyColors.SECONDARY_COLOR))
                            .insert(Message.raw(target.getUsername()).color(HyProxyColors.PRIMARY_COLOR))
                            .insert(Message.raw(" to backend ").color(HyProxyColors.SECONDARY_COLOR))
                            .insert(Message.raw(targetBackend.getInfo().id()).color(HyProxyColors.PRIMARY_COLOR))
                    );
                }
            }
            case ProxyCommunicationUtil.ProxyCommunicationMessage.Unknown _ -> log.warn("backend sent unknown proxy communication message, ignoring");
        }

        return true;
    }

    @Override
    public boolean handle(ServerInfo serverInfo) {
        String serverName = serverInfo.getServerName();
        if (serverName == null || serverName.endsWith(" (hyproxy)")) {
            return false;
        }

        this.connection.ensurePlayer().sendToPlayer(new ServerInfo(
                serverName + " (hyproxy)",
                serverInfo.getMotd(),
                serverInfo.getMaxPlayers()
        ));
        return true;
    }


    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().close();
    }
}
