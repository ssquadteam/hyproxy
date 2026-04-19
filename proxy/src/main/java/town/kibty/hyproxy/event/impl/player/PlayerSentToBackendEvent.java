package town.kibty.hyproxy.event.impl.player;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.event.CancelableEvent;
import town.kibty.hyproxy.event.HyProxyEvent;
import town.kibty.hyproxy.player.HyProxyPlayer;

/**
 * fired when the player is sent to another backend via a command, plugin or similar.
 * <p>
 * this event does not fire on initial connections.
 */
@Getter
@Setter
@AllArgsConstructor
public class PlayerSentToBackendEvent implements HyProxyEvent, CancelableEvent {
    private final HyProxyPlayer player;
    private final @Nullable HyProxyBackend oldBackend;
    private HyProxyBackend newBackend;
    private boolean canceled;
}
