package org.kendar.llm.harness.tools;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * JVM-wide name resolver (JEP 418) that answers a pinned host name with exactly the addresses {@link AddressPolicy}
 * approved, so the HTTP client connects to what was checked (no DNS rebinding between check and connect). Every other
 * name, and every reverse lookup, goes to the built-in resolver unchanged. Registered in {@code META-INF/services}.
 * <p>
 * Pins are per host name and counted: each {@link #pin} is matched by a close of the returned handle (callers use
 * try/finally). The JVM name cache may keep a pinned answer after the release (30 s by default); it only ever holds
 * answers the policy approved, since a guarded name is resolved only while pinned.
 */
public final class GuardedResolverProvider extends InetAddressResolverProvider {

    private static volatile InetAddressResolver builtin;

    private static final class Pin {
        final InetAddress[] addresses;
        int users;

        Pin(InetAddress[] addresses) {
            this.addresses = addresses;
        }
    }

    private static final ConcurrentHashMap<String, Pin> PINS = new ConcurrentHashMap<>();

    /** Releases one pin of a host name. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    static final Handle NONE = () -> {
    };

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver b = configuration.builtinResolver();
        builtin = b;
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                InetAddress[] pinned = pinned(host);
                if (pinned == null) return b.lookupByName(host, policy);
                int c = policy.characteristics();
                boolean v4 = (c & LookupPolicy.IPV4) != 0;
                boolean v6 = (c & LookupPolicy.IPV6) != 0;
                InetAddress[] out = Arrays.stream(pinned)
                        .filter(a -> a.getAddress().length == 4 ? v4 : v6)
                        .toArray(InetAddress[]::new);
                if (out.length == 0) throw new UnknownHostException(host + " (pinned, no address of the requested family)");
                return Arrays.stream(out);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return b.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "jllm-guarded";
    }

    /** Whether this provider is the one the JVM uses (another provider may have won). */
    static boolean active() {
        if (builtin == null) {
            try {
                InetAddress.getAllByName("localhost"); // the provider is loaded on the first lookup
            } catch (UnknownHostException ignored) {
                // nothing to do: only the side effect counts
            }
        }
        return builtin != null;
    }

    /** All addresses of {@code host} from the built-in resolver, never from a pin. */
    static InetAddress[] lookup(String host) throws UnknownHostException {
        InetAddressResolver b = active() ? builtin : null;
        if (b == null) return InetAddress.getAllByName(host);
        return b.lookupByName(host, InetAddressResolver.LookupPolicy.of(
                InetAddressResolver.LookupPolicy.IPV4 | InetAddressResolver.LookupPolicy.IPV6)).toArray(InetAddress[]::new);
    }

    private static String key(String host) {
        return host.toLowerCase(java.util.Locale.ROOT);
    }

    private static InetAddress[] pinned(String host) {
        Pin p = PINS.get(key(host));
        if (p == null) return null;
        synchronized (p) {
            if (p.users <= 0) return null;
            return p.addresses;
        }
    }

    /**
     * Pins {@code host} to {@code addresses} until the handle is closed. When the host is already pinned to another
     * set, the newer set replaces it: both were approved, so a racing fetch connects to an approved address either way.
     */
    static Handle pin(String host, InetAddress[] addresses) {
        String k = key(host);
        InetAddress[] copy = addresses.clone();
        while (true) {
            Pin fresh = new Pin(copy);
            Pin p = PINS.compute(k, (name, old) -> {
                if (old == null) return fresh;
                synchronized (old) {
                    if (old.users <= 0) return fresh;
                    return sameSet(old.addresses, copy) ? old : fresh;
                }
            });
            synchronized (p) {
                if (p.users < 0) continue; // released and dropped meanwhile
                p.users++;
            }
            return new Handle() {
                private boolean closed;

                @Override
                public synchronized void close() {
                    if (closed) return;
                    closed = true;
                    synchronized (p) {
                        if (--p.users == 0) {
                            p.users = -1;
                            PINS.remove(k, p);
                        }
                    }
                }
            };
        }
    }

    private static boolean sameSet(InetAddress[] a, InetAddress[] b) {
        return new java.util.HashSet<>(Arrays.asList(a)).equals(new java.util.HashSet<>(Arrays.asList(b)));
    }

    /** For tests: whether a pin is live for {@code host}. */
    static boolean isPinned(String host) {
        return pinned(host) != null;
    }
}
