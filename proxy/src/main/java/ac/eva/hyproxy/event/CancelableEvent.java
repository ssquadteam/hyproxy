package ac.eva.hyproxy.event;

public interface CancelableEvent {
    boolean isCanceled();
    void setCanceled(boolean canceled);
}
