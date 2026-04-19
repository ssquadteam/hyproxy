package ac.eva.hyproxy.player.permission;

import com.google.common.collect.ImmutableSet;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.player.HyProxyPlayer;

import java.util.Set;

public class OperatorsPlayerPermissionProvider implements PlayerPermissionProvider {
    private HyProxy proxy;

    @Override
    public void initialize(HyProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public Set<String> getPlayerPermissions(HyProxyPlayer player) {
        return ImmutableSet.copyOf(proxy.getConfiguration().getProfilePermissions(player.getProfileId()));
    }

    @Override
    public int priority() {
        return 1000;
    }
}
