package town.kibty.hyproxy.event.impl.player;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import town.kibty.hyproxy.backend.HyProxyBackend;
import town.kibty.hyproxy.event.CancelableEvent;
import town.kibty.hyproxy.event.HyProxyEvent;
import town.kibty.hyproxy.player.HyProxyPlayer;

/**
 * fired when player completed authentication successfully and their connection is about to be forwarded to a backend
 * <p>
 *
 * to change the players backend they will be forwarded to, use {@link HyProxyPlayer#setReferredBackend(HyProxyBackend)}
 */
@Getter
@Setter
@AllArgsConstructor
public class PlayerAuthSuccessEvent implements HyProxyEvent, CancelableEvent {
    private final HyProxyPlayer player;
    private boolean canceled;
}
