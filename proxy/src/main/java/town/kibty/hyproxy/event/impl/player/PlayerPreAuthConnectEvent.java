package town.kibty.hyproxy.event.impl.player;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import town.kibty.hyproxy.event.CancelableEvent;
import town.kibty.hyproxy.event.HyProxyEvent;
import town.kibty.hyproxy.player.HyProxyPlayer;

/**
 * fired when the player initially sends a {@link town.kibty.hyproxy.io.packet.impl.auth.Connect} packet.
 * this is pre-authentication, we just received info from the player of who they claim to be.
 */
@Getter
@Setter
@AllArgsConstructor
public class PlayerPreAuthConnectEvent implements HyProxyEvent, CancelableEvent {
    private final HyProxyPlayer player;
    private boolean canceled;
}
