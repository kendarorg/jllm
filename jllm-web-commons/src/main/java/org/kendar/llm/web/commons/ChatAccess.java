package org.kendar.llm.web.commons;

import org.kendar.llm.harness.session.ChatSessionData;

/** Which chats a user may see. A chat the user may not see answers 404, as if it did not exist. */
@FunctionalInterface
public interface ChatAccess {

    /** No chat is visible. */
    ChatAccess NONE = (user, chat) -> false;

    boolean canSee(WebPrincipal user, ChatSessionData chat);
}
