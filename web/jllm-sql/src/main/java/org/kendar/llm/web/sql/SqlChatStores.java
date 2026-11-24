package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.context.mnemonic.MnemonicState;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.ChatVisibility;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The per-chat stores of the harness on tables keyed by {@code chat_id}: {@code chats} (the row) + {@code chat_log}
 * (the messages, one JSON line each, kept apart so listing never reads them), {@code chat_state},
 * {@code mnemonic_state} and {@code subagent_runs}. {@link #delete} cascades over all of them.
 */
public final class SqlChatStores {

    private static final Logger log = LoggerFactory.getLogger(SqlChatStores.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SqlChatStores() {
    }

    static long nanos(Instant i) {
        Instant v = i == null ? Instant.now() : i;
        return v.getEpochSecond() * 1_000_000_000L + v.getNano();
    }

    static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    /** The chat rows and their logs. */
    public static final class Sessions implements ChatSessionStore {

        private static final String COLS = "chat_id, agent_id, title, mode, tool_groups, input_tokens, output_tokens,"
                + " cache_read_tokens, cache_creation_tokens, created_at, updated_at, message_count,"
                + " qualifier_input_tokens, qualifier_output_tokens, principal, chat_tools, tainted_tool_uses";

        private final Database db;

        public Sessions(Database db) {
            this.db = db;
        }

        @Override
        public ChatSessionData save(ChatSessionData s) {
            String id = s.chatId();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Invalid chat id '" + id + "'");
            String messages = s.messagesJson() == null ? "" : s.messagesJson();
            db.run(tx -> {
                tx.upsert("chats", List.of("chat_id"), List.of("agent_id", "title", "mode", "tool_groups",
                                "input_tokens", "output_tokens", "cache_read_tokens", "cache_creation_tokens", "created_at",
                                "updated_at", "message_count", "qualifier_input_tokens", "qualifier_output_tokens",
                                "principal", "chat_tools", "tainted_tool_uses"),
                        id, s.agentId(), s.title(), s.mode(), s.toolGroups(), s.inputTokens(), s.outputTokens(),
                        s.cacheReadTokens(), s.cacheCreationTokens(), nanos(s.createdAt()), nanos(s.updatedAt()),
                        s.messageCount(), s.qualifierInputTokens(), s.qualifierOutputTokens(), s.principal(),
                        s.chatTools(), s.taintedToolUses());
                tx.upsert("chat_log", List.of("chat_id"), List.of("messages"), id, messages);
            });
            return s;
        }

        @Override
        public Optional<ChatSessionData> find(String chatId) {
            if (chatId == null) return Optional.empty();
            return db.tx(tx -> tx.one("SELECT " + COLS + " FROM chats WHERE chat_id = ?", rs -> row(rs), chatId)
                    .map(row -> {
                        String log = tx.one("SELECT messages FROM chat_log WHERE chat_id = ?", rs -> rs.getString(1),
                                chatId).orElse("");
                        return row.withLoadedMessages(log);
                    }));
        }

        @Override
        public List<ChatSessionData> list(ChatVisibility visibility) {
            String where;
            Object[] args;
            switch (visibility.kind()) {
                case NOTHING:
                    return List.of();
                case OWNED:
                    where = " WHERE principal IS NOT NULL";
                    args = new Object[0];
                    break;
                case OWNER:
                    where = " WHERE principal = ?";
                    args = new Object[]{visibility.owner()};
                    break;
                default:
                    where = "";
                    args = new Object[0];
            }
            return db.tx(tx -> tx.query("SELECT " + COLS + " FROM chats" + where + " ORDER BY updated_at DESC, chat_id",
                    Sessions::row, args));
        }

        @Override
        public void delete(String chatId) {
            if (chatId == null) return;
            db.run(tx -> {
                for (String t : List.of("chat_log", "chat_state", "mnemonic_state", "subagent_runs", "chats")) {
                    tx.update("DELETE FROM " + t + " WHERE chat_id = ?", chatId);
                }
            });
        }

        private static ChatSessionData row(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new ChatSessionData(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), "",
                    rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                    instant(rs.getLong(10)), instant(rs.getLong(11)), rs.getInt(12), rs.getLong(13), rs.getLong(14),
                    rs.getString(15), rs.getString(16), rs.getString(17));
        }
    }

    /** Small JSON documents per chat. */
    public static final class State implements StateStore {

        private final Database db;

        public State(Database db) {
            this.db = db;
        }

        @Override
        public JsonNode load(String chatId, String name) {
            String doc = db.tx(tx -> tx.one("SELECT doc FROM chat_state WHERE chat_id = ? AND name = ?",
                    rs -> rs.getString(1), chatId, name)).orElse(null);
            if (doc == null) return null;
            try {
                return MAPPER.readTree(doc);
            } catch (JsonProcessingException e) {
                log.warn("Unreadable state document {} of chat {}: {}", name, chatId, e.getMessage());
                return null;
            }
        }

        @Override
        public void save(String chatId, String name, JsonNode value) {
            String doc;
            try {
                doc = MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Not serializable state " + name, e);
            }
            db.run(tx -> tx.upsert("chat_state", List.of("chat_id", "name"), List.of("doc"), chatId, name, doc));
        }

        @Override
        public void delete(String chatId) {
            db.run(tx -> tx.update("DELETE FROM chat_state WHERE chat_id = ?", chatId));
        }
    }

    /** Mnemonic decisions per chat and agent. */
    public static final class Mnemonic implements MnemonicStore {

        private final Database db;

        public Mnemonic(Database db) {
            this.db = db;
        }

        /** An unreadable document starts over with no decisions and a warning on the state (the row is kept). */
        @Override
        public MnemonicState load(String chatId, String agentKey) {
            String doc = db.tx(tx -> tx.one("SELECT doc FROM mnemonic_state WHERE chat_id = ? AND agent_key = ?",
                    rs -> rs.getString(1), chatId, agentKey)).orElse(null);
            if (doc == null) return new MnemonicState();
            try {
                return MAPPER.readValue(doc, MnemonicState.class);
            } catch (JsonProcessingException | RuntimeException e) { // boundary: unreadable state starts over
                log.warn("Unreadable mnemonic state of chat {} agent {}: {}", chatId, agentKey, e.getMessage());
                MnemonicState fresh = new MnemonicState();
                fresh.warning = "The saved context decisions could not be read; masking starts over from the full history.";
                return fresh;
            }
        }

        @Override
        public void save(String chatId, String agentKey, MnemonicState state) {
            String doc;
            try {
                doc = MAPPER.writeValueAsString(state);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Not serializable mnemonic state", e);
            }
            db.run(tx -> tx.upsert("mnemonic_state", List.of("chat_id", "agent_key"), List.of("doc"), chatId,
                    agentKey, doc));
        }

        @Override
        public void delete(String chatId) {
            db.run(tx -> tx.update("DELETE FROM mnemonic_state WHERE chat_id = ?", chatId));
        }
    }

    /** Audit trail of the sub-agent runs; recording never throws. */
    public static final class Runs implements SubAgentRunLog {

        private static final String COLS = "id, chat_id, tool_use_id, prompt, messages_json, answer, status,"
                + " input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens, created_at";

        private final Database db;

        public Runs(Database db) {
            this.db = db;
        }

        @Override
        public void record(SubAgentRunData r) {
            try {
                db.run(tx -> tx.upsert("subagent_runs", List.of("id"), List.of("chat_id", "tool_use_id", "prompt",
                                "messages_json", "answer", "status", "input_tokens", "output_tokens", "cache_read_tokens",
                                "cache_creation_tokens", "created_at"),
                        r.id(), r.chatId(), r.toolUseId(), r.prompt(), r.messagesJson(), r.answer(), r.status(),
                        r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheCreationTokens(),
                        nanos(r.createdAt())));
            } catch (RuntimeException e) { // boundary: the log must not fail the run it describes
                log.warn("Cannot record sub-agent run {}: {}", r.id(), e.getMessage());
            }
        }

        @Override
        public List<SubAgentRunData> list(String chatId) {
            return db.tx(tx -> tx.query("SELECT " + COLS + " FROM subagent_runs WHERE chat_id = ?"
                    + " ORDER BY created_at, id", Runs::row, chatId));
        }

        @Override
        public List<SubAgentRunData> forToolUse(String chatId, String toolUseId) {
            return db.tx(tx -> tx.query("SELECT " + COLS + " FROM subagent_runs WHERE chat_id = ? AND tool_use_id = ?"
                    + " ORDER BY created_at, id", Runs::row, chatId, toolUseId));
        }

        @Override
        public void deleteChat(String chatId) {
            db.run(tx -> tx.update("DELETE FROM subagent_runs WHERE chat_id = ?", chatId));
        }

        private static SubAgentRunData row(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new SubAgentRunData(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                    rs.getLong(11), instant(rs.getLong(12)));
        }
    }
}
