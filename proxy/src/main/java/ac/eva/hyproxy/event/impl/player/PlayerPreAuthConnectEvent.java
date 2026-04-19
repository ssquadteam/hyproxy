package ac.eva.hyproxy.event.impl.player;

import ac.eva.hyproxy.io.packet.impl.auth.Connect;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import ac.eva.hyproxy.event.CancelableEvent;
import ac.eva.hyproxy.event.HyProxyEvent;
import ac.eva.hyproxy.player.HyProxyPlayer;

/**
 * fired when the player initially sends a {@link Connect} packet.
 * this is pre-authentication, we just received info from the player of who they claim to be.
 */
@Getter
@Setter
@AllArgsConstructor
public class PlayerPreAuthConnectEvent implements HyProxyEvent, CancelableEvent {
    private final HyProxyPlayer player;
    private boolean canceled;
}
