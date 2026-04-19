package ac.eva.hyproxy.util;

import com.google.common.net.InetAddresses;
import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

// mostly copied from velocity
@UtilityClass
public class AddressUtil {
    public InetSocketAddress parseAddress(String ip) {
        URI uri = URI.create("udp://" + ip);
        if (uri.getHost() == null) {
            throw new IllegalStateException("Invalid hostname/IP " + ip);
        }

        int port = uri.getPort() == -1 ? 5520 : uri.getPort();
        try {
            InetAddress ia = InetAddresses.forUriString(uri.getHost());
            return new InetSocketAddress(ia, port);
        } catch (IllegalArgumentException e) {
            return InetSocketAddress.createUnresolved(uri.getHost(), port);
        }
    }

    public InetSocketAddress parseAndResolveAddress(String ip) {
        URI uri = URI.create("udp://" + ip);
        if (uri.getHost() == null) {
            throw new IllegalStateException("Invalid hostname/IP " + ip);
        }

        int port = uri.getPort() == -1 ? 5520 : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }
}
