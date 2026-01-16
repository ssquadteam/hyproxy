package town.kibty.hyproxy.backend;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import town.kibty.hyproxy.player.HyProxyPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class HyProxyBackend {
    @Getter
    private final BackendInfo info;

    private final Map<UUID, HyProxyPlayer> playersById = new ConcurrentHashMap<>();

    public Collection<HyProxyPlayer> getPlayersConnected() {
        return ImmutableList.copyOf(playersById.values());
    }

    public void registerPlayer(HyProxyPlayer player) {
        this.playersById.put(player.getProfileId(), player);
    }

    public void unregisterPlayer(HyProxyPlayer player) {
        this.playersById.remove(player.getProfileId());
    }
}
