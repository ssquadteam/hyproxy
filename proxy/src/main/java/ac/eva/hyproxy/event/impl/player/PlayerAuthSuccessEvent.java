package ac.eva.hyproxy.event.impl.player;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.event.CancelableEvent;
import ac.eva.hyproxy.event.HyProxyEvent;
import ac.eva.hyproxy.player.HyProxyPlayer;

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
