package org.kendar.llm.harness.tools;

/**
 * Asks whether a web tool may contact a host. Blocks while the user answers. A {@code null} approver in a
 * {@link ToolContext} (direct calls in tests or embedding code) allows every host.
 */
@FunctionalInterface
public interface HostApprover {

    /**
     * @param host the host as written in the URL
     * @param url  the full URL about to be requested
     */
    boolean approve(String host, String url);

    /**
     * The certificate of {@code host} is not valid (expired, self-signed, wrong name, unknown authority). Asks whether
     * this one call may go on anyway, without checking the certificate. The answer covers that call only, never the
     * chat. The default refuses.
     *
     * @param reason what is wrong with the certificate
     */
    default boolean approveInsecure(String host, String url, String reason) {
        return false;
    }
}
