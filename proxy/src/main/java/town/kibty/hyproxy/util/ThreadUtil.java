package town.kibty.hyproxy.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

@UtilityClass
public class ThreadUtil {
    public ThreadFactory daemonCounted(String name) {
        AtomicLong count = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, String.format(name, count.incrementAndGet()));
            t.setDaemon(true);
            return t;
        };
    }
}
