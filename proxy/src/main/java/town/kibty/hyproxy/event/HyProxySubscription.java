package town.kibty.hyproxy.event;

import java.util.function.Consumer;

public record HyProxySubscription<T extends HyProxyEvent>(
        Consumer<T> consumer,
        int priority
) {
}
