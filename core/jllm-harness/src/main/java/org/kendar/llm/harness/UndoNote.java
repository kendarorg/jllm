package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.tools.Checkpoints;

/** Tells the conversation that file changes were undone, so the model does not write them again. */
final class UndoNote {

    private UndoNote() {
    }

    /**
     * Appends the undo to the history: a user note and a short assistant reply, or the note as one more text block
     * when the history ends with a user message (roles must alternate).
     */
    static void append(ChatSessionService sessions, String chatId, Checkpoints.UndoResult r) {
        StringBuilder note = new StringBuilder("[/undo] I reverted the file changes of the last ").append(r.turns())
                .append(" turn(s) with edits; the changes described above for these files no longer exist.");
        if (!r.restored().isEmpty()) note.append(" Back to their earlier content: ").append(String.join(", ", r.restored())).append('.');
        if (!r.deleted().isEmpty()) note.append(" Deleted (they did not exist before): ").append(String.join(", ", r.deleted())).append('.');
        note.append(" Do not redo these changes unless I ask again; read the files for their current content.");
        var chat = sessions.find(chatId);
        if (chat.isEmpty()) return;
        var messages = sessions.messages(chat.get());
        if (messages.isEmpty()) return;
        var last = messages.get(messages.size() - 1);
        if ("user".equals(last.path("role").asText()) && last.path("content").isArray()) {
            ((ArrayNode) last.path("content")).addObject().put("type", "text").put("text", note.toString());
        } else {
            var user = messages.addObject().put("role", "user");
            user.putArray("content").addObject().put("type", "text").put("text", note.toString());
            var ack = messages.addObject().put("role", "assistant");
            ack.putArray("content").addObject().put("type", "text").put("text", "Understood: those changes are undone.");
        }
        sessions.saveMessages(chatId, messages);
    }
}
