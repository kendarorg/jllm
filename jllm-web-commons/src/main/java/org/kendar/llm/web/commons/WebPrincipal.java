package org.kendar.llm.web.commons;

/** The signed-in user of a web request, as the common API sees it. Supplied by the authentication implementation. */
public interface WebPrincipal {

    /** Request attribute holding the {@link WebPrincipal} of an authenticated {@code /api/**} call. */
    String ATTR = "jllm.user";

    String id();

    /** Sees every chat and may run the global slash commands. */
    boolean admin();
}
