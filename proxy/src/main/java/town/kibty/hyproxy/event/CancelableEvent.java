package town.kibty.hyproxy.event;

public interface CancelableEvent {
    boolean isCanceled();
    void setCanceled(boolean canceled);
}
