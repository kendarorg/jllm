package org.kendar.llm.harness.identity;

/** The signed-in user of a web request, as the common API sees it. Supplied by the authentication implementation. */
public interface Principal {

    /** Request attribute holding the {@link Principal} of an authenticated {@code /api/**} call. */
    String ATTR = "jllm.user";

    String id();

    /** Sees every chat and may run the global slash commands. */
    boolean admin();
}
