package town.kibty.hyproxy.io;

import io.netty.buffer.ByteBuf;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.impl.ClientReferral;
import town.kibty.hyproxy.io.packet.impl.Disconnect;
import town.kibty.hyproxy.io.packet.impl.auth.*;
import town.kibty.hyproxy.io.packet.impl.game.ChatMessage;
import town.kibty.hyproxy.io.packet.impl.game.ServerMessage;
import town.kibty.hyproxy.io.packet.impl.setup.ServerInfo;

public interface HytalePacketHandler {
    default void connected() {}
    default void disconnected() {}
    default void activated() {}
    default void deactivated() {}

    default boolean handle(Connect connect) {
        return false;
    }
    default boolean handle(Disconnect disconnect) {
        return false;
    }
    default boolean handle(AuthGrant authGrant) {
        return false;
    }
    default boolean handle(AuthToken authToken) {
        return false;
    }
    default boolean handle(ServerAuthToken serverAuthToken) {
        return false;
    }
    default boolean handle(ConnectAccept connectAccept) {
        return false;
    }
    default boolean handle(ClientReferral referral) {
        return false;
    }
    default boolean handle(ServerMessage serverMessage) {
        return false;
    }
    default boolean handle(ChatMessage chatMessage) {
        return false;
    }
    default boolean handle(ServerInfo serverInfo) {
        return false;
    }

    default void handleGeneric(Packet packet) {}
    default void handleUnknown(ByteBuf buf) {}
}
