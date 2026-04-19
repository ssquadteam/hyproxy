package ac.eva.hyproxy.event.impl.player;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.event.CancelableEvent;
import ac.eva.hyproxy.event.HyProxyEvent;
import ac.eva.hyproxy.player.HyProxyPlayer;

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
