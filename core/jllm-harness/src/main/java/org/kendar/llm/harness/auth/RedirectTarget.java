package org.kendar.llm.harness.auth;

import java.io.IOException;
import java.time.Duration;

/**
 * Where the authorization server sends the browser back after an authorization code login: a loopback listener on
 * this machine (command line, browser on the same host), or the callback URL of a web front end, which hands the
 * code over to the waiting login.
 */
public interface RedirectTarget {

    /** One pending redirect: its URI, and the code once the browser comes back. */
    interface Receiver extends AutoCloseable {

        /** The exact {@code redirect_uri} to send (and to register, with dynamic registration). */
        String redirectUri();

        /**
         * Waits for the redirect carrying the expected state.
         *
         * @throws OAuthException on an error redirect, a redirect without code, or a timeout (code {@code timeout})
         */
        String awaitCode(Duration timeout);

        @Override
        void close();
    }

    /** Starts waiting for the redirect that carries {@code state}. */
    Receiver open(String state) throws IOException;

    /** True for a listener on this machine: the browser must run here. */
    default boolean isLoopback() {
        return false;
    }

    /** A listener on 127.0.0.1. @param port 0 = a free port */
    static RedirectTarget loopback(int port) {
        return new RedirectTarget() {
            @Override
            public Receiver open(String state) throws IOException {
                return new LoopbackCallbackServer(port, state);
            }

            @Override
            public boolean isLoopback() {
                return true;
            }
        };
    }

    static RedirectTarget loopback() {
        return loopback(0);
    }
}
