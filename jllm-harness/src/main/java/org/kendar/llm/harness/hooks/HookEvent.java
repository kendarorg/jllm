package org.kendar.llm.harness.hooks;

public enum HookEvent {
    /** A user message arrived, before the model sees it. Can block it or add context. */
    USER_PROMPT,
    /** A tool is about to run, before the permission check. Can deny it, rewrite its input or add context. */
    PRE_TOOL,
    /** A tool finished. Can add context to its result. */
    POST_TOOL,
    /** The turn of the main agent ended. Observation only. */
    STOP,
    /** A model call is about to be made (main agent or sub-agent). Can deny it, which ends the run with an error. */
    PRE_LLM,
    /** A model call returned or failed. Observation only. */
    POST_LLM,
    /** An agent (the main one for a user turn, or a sub-agent) is about to start. Can deny it. */
    PRE_AGENT,
    /** An agent finished, successfully or not. Observation only. */
    POST_AGENT
}
