package town.kibty.hyproxy.backend;

import java.net.InetSocketAddress;

public record BackendInfo(
        String id,
        InetSocketAddress address
) {
}
