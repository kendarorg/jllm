package org.kendar.llm.harness.identity;

import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatVisibility;

/** Which chats a user may see. A chat the user may not see answers 404, as if it did not exist. */
@FunctionalInterface
public interface ChatAccess {

    /** No chat is visible. */
    ChatAccess NONE = new ChatAccess() {
        @Override
        public boolean canSee(Principal user, ChatSessionData chat) {
            return false;
        }

        @Override
        public ChatVisibility visibility(Principal user) {
            return ChatVisibility.nothing();
        }
    };

    boolean canSee(Principal user, ChatSessionData chat);

    /**
     * What a chat listing may load for the user: the store filters by it, so chats of others are never read. It must
     * admit at least every chat {@link #canSee} accepts; {@link #canSee} stays the final check on each listed chat.
     * Implementations that cannot narrow it admit everything.
     */
    default ChatVisibility visibility(Principal user) {
        return ChatVisibility.everything();
    }
}
