package ac.eva.hyproxy.io.handler.outbound;

import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.common.communication.ProxyCommunicationMessage;
import ac.eva.hyproxy.common.util.ProxyCommunicationUtil;
import ac.eva.hyproxy.event.impl.proxy.ProxyCommunicationMessageEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.auth.AuthGrant;
import ac.eva.hyproxy.io.packet.impl.setup.ServerInfo;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.HyProxyPlayer;

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
    public void handleGeneric(NetworkChannel channel, Packet packet) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.sendToPlayer(channel, packet);
    }

    @Override
    public void handleUnknown(NetworkChannel channel, ByteBuf buf) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().write(channel, buf.retain());
    }

    // this should never happen in normal conditions, but we make it happen in the backend plugin to have a way for the backend
    // to communicate with the proxy.
    // the backend sends a AuthGrant packet in play/setup state, with the server identity token set to hyProxy.<base64_payload>
    // which we then decode into our own, or fire an event for plugins to listen to for unknown ones
    @Override
    public boolean handle(AuthGrant passwordResponse) {
        HyProxy proxy = connection.getProxy();

        if (!proxy.getConfiguration().isProxyCommunicationEnabled()) return false;
        if (passwordResponse.getServerIdentityToken() == null) return false;

        ProxyCommunicationMessage message = ProxyCommunicationUtil.deserializeMessage(passwordResponse.getServerIdentityToken());

        if (message == null) return false;

        // todo: move this somewhere else
        switch (message) {
            case ProxyCommunicationMessage.SendToBackend sendToBackend -> {
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
            case ProxyCommunicationMessage.Unknown msg -> {
                ProxyCommunicationMessageEvent event = proxy.getEventBus().fire(new ProxyCommunicationMessageEvent(
                        msg,
                        false
                ));

                if (event.isHandled()) {
                    return true;
                }

                log.warn("backend sent unknown proxy communication message but no plugin handled it");
                return false;
            }
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
                serverInfo.getFallbackServer(),
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
