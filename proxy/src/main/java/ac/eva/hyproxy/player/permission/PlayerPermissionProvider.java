package ac.eva.hyproxy.player.permission;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.player.HyProxyPlayer;

import java.util.Set;

public interface PlayerPermissionProvider {
    default void initialize(HyProxy proxy) {};
    default boolean hasPermission(HyProxyPlayer player, String permission) {
        Set<String> permissions = this.getPlayerPermissions(player);
        if (permissions.contains("op"))
            return true;

        return permissions.contains(permission);
    }
    Set<String> getPlayerPermissions(HyProxyPlayer player);
    int priority();
}
