package org.kendar.llm.harness.session;

/**
 * Which chats a listing returns, decided by the store itself (a SQL {@code WHERE}, a filter while reading rows) so a
 * caller never loads chats it may not see. A pre-filter only: the caller's own access check stays the final word.
 */
public record ChatVisibility(Kind kind, String owner) {

    public enum Kind {
        /** Every chat, owned or not (CLI, single user). */
        EVERYTHING,
        /** Every chat that has an owner (an administrator). */
        OWNED,
        /** The chats of one owner. */
        OWNER,
        /** No chat. */
        NOTHING
    }

    private static final ChatVisibility EVERYTHING = new ChatVisibility(Kind.EVERYTHING, null);
    private static final ChatVisibility OWNED = new ChatVisibility(Kind.OWNED, null);
    private static final ChatVisibility NOTHING = new ChatVisibility(Kind.NOTHING, null);

    public ChatVisibility {
        if (kind == Kind.OWNER && (owner == null || owner.isEmpty())) kind = Kind.NOTHING;
        if (kind != Kind.OWNER) owner = null;
    }

    public static ChatVisibility everything() {
        return EVERYTHING;
    }

    public static ChatVisibility owned() {
        return OWNED;
    }

    public static ChatVisibility nothing() {
        return NOTHING;
    }

    /** A null or empty owner matches nothing. */
    public static ChatVisibility ownedBy(String owner) {
        return new ChatVisibility(Kind.OWNER, owner);
    }

    /** True when a chat of this principal (null = no owner) is listed. */
    public boolean matches(String principal) {
        return switch (kind) {
            case EVERYTHING -> true;
            case OWNED -> principal != null;
            case OWNER -> owner.equals(principal);
            case NOTHING -> false;
        };
    }

    public boolean matches(ChatSessionData chat) {
        return matches(chat.principal());
    }
}
