package town.kibty.hyproxy.event;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HyProxyEventBus {
    private final Map<Class<? extends HyProxyEvent>, Set<HyProxySubscription<? extends HyProxyEvent>>> subscriptions = new ConcurrentHashMap<>();

    /**
     * fires an event and notifies all current subscribers
     * @param event the event to fire
     * @return the final event after subscribers mutate
     */
    public <T extends HyProxyEvent> T fire(T event) {
        Set<HyProxySubscription<? extends HyProxyEvent>> eventSubscriptions = this.subscriptions.get(event.getClass());

        if (eventSubscriptions == null) {
            return event;
        }

        for (HyProxySubscription<? extends HyProxyEvent> subscription : eventSubscriptions) {
            //noinspection unchecked
            ((HyProxySubscription<T>) subscription).consumer().accept(event);

            if (event instanceof CancelableEvent cancelableEvent && cancelableEvent.isCanceled()) {
                return event;
            }
        }

        return event;
    }

    /**
     * subscribes to an event
     * @param clazz the event to subscribe to
     * @param priority the priority to subscribe at
     * @param consumer the consumer to fire when a event is called
     * @param <T> the event to subscribe to
     */
    public <T extends HyProxyEvent> void subscribe(Class<T> clazz, int priority, Consumer<T> consumer) {
        HyProxySubscription<T> subscription = new HyProxySubscription<>(consumer, priority);
        Set<HyProxySubscription<? extends HyProxyEvent>> eventSubscriptions = this.subscriptions.computeIfAbsent(clazz, _ -> new LinkedHashSet<>());
        eventSubscriptions.add(subscription);

        this.subscriptions.put(clazz, eventSubscriptions.stream()
                .sorted(Comparator.comparingInt(o -> ((HyProxySubscription<? extends HyProxyEvent>) o)
                        .priority())
                        .reversed()
                )
                .collect(Collectors.toCollection(LinkedHashSet::new))
        );
    }
}
