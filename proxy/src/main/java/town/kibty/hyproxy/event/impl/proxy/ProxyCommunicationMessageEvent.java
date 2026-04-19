package town.kibty.hyproxy.event.impl.proxy;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import town.kibty.hyproxy.common.communication.ProxyCommunicationMessage;
import town.kibty.hyproxy.event.HyProxyEvent;

/**
 * fired when a proxy communication message was sent by the backend.
 * if none of the events subscribers set the handled field to true, and it is an unknown proxy communication message, a warning log will be printed
 */
@Getter
@Setter
@AllArgsConstructor
public class ProxyCommunicationMessageEvent implements HyProxyEvent {
    private ProxyCommunicationMessage message;
    private boolean handled;
}
