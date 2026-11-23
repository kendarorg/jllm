package org.kendar.llm.harness.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Startup check of the multi-user web token stores (file and SQL, one rule): without {@code JLLM_TOKEN_KEY} the
 * users' OAuth tokens are stored in clear. That is tolerated, with a loud warning, for local play; it is refused when
 * the server is exposed ({@code jllm.web.use-real-password=true} or a {@code server.address} that is not loopback;
 * unset, {@code 0.0.0.0} and {@code ::} are not) unless {@code jllm.web.allow-unencrypted-tokens=true}.
 */
public final class TokenKeyGuard {

    public static final String USE_REAL_PASSWORD = "jllm.web.use-real-password";
    public static final String SERVER_ADDRESS = "server.address";
    public static final String ALLOW_UNENCRYPTED = "jllm.web.allow-unencrypted-tokens";

    private static final Logger log = LoggerFactory.getLogger(TokenKeyGuard.class);

    private TokenKeyGuard() {
    }

    /**
     * @param env   environment variables ({@code JLLM_TOKEN_KEY})
     * @param props application properties ({@code jllm.web.*}, {@code server.address}); null for an unset one
     * @return true when the tokens will be encrypted (a key is set)
     * @throws IllegalStateException when there is no key and the server is exposed without the override
     */
    public static boolean check(Function<String, String> env, Function<String, String> props) {
        return check(env, props, log::warn);
    }

    /** As {@link #check(Function, Function)}, the warning going to {@code warn}. */
    public static boolean check(Function<String, String> env, Function<String, String> props, Consumer<String> warn) {
        String key = env.apply(TokenCipher.KEY_ENV);
        if (key != null && !key.isBlank()) return true;
        boolean real = bool(props.apply(USE_REAL_PASSWORD));
        String address = props.apply(SERVER_ADDRESS);
        boolean exposed = real || !isLoopbackAddress(address);
        boolean allowed = bool(props.apply(ALLOW_UNENCRYPTED));
        String generate = "generate one with: openssl rand -base64 32 (any base64 of 32 random bytes)";
        if (exposed && !allowed) {
            throw new IllegalStateException(TokenCipher.KEY_ENV + " is not set: users' OAuth tokens would be stored"
                    + " in clear text, and this server is not local-only (" + (real ? USE_REAL_PASSWORD + "=true"
                    : SERVER_ADDRESS + "=" + (address == null || address.isBlank() ? "<unset>" : address))
                    + "). Set " + TokenCipher.KEY_ENV + " (" + generate + "), or accept clear-text tokens with "
                    + ALLOW_UNENCRYPTED + "=true.");
        }
        warn.accept(exposed
                ? TokenCipher.KEY_ENV + " is not set and " + ALLOW_UNENCRYPTED + "=true: users' OAuth tokens are"
                + " stored in CLEAR TEXT on an exposed server. Set " + TokenCipher.KEY_ENV + " (" + generate + ")."
                : "*** " + TokenCipher.KEY_ENV + " is not set: users' OAuth tokens are stored in CLEAR TEXT. Fine for"
                + " local play only. Set " + TokenCipher.KEY_ENV + " to the base64 of 32 random bytes (" + generate
                + ") before exposing this server. ***");
        return false;
    }

    private static boolean bool(String v) {
        return v != null && v.strip().equalsIgnoreCase("true");
    }

    /** Whether a {@code server.address} value is loopback; unset, blank, any-local or unresolvable is not. */
    static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String a = address.trim();
        if (a.equalsIgnoreCase("localhost")) return true;
        if (!a.matches("[0-9.]+") && !a.contains(":")) return false; // a host name: not resolved here
        try {
            return InetAddress.getByName(a).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
