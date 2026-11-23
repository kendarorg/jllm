package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.ChatApi;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** {@code /api/chat/**}: binds the request, calls the {@link ChatApi}. */
@RestController
public class ChatController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ChatApi chat;
    private final WebAuthentication authentication;

    /** @param authentication resolves the signed-in user on the open {@code /api/chat/commands} endpoint */
    public ChatController(ChatApi chat, WebAuthentication authentication) {
        this.chat = chat;
        this.authentication = authentication;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, String> body,
                                      @RequestAttribute(value = Principal.ATTR, required = false) Principal user) {
        return chat.create(body, user);
    }

    @GetMapping("/api/chat")
    public List<Map<String, Object>> list(
            @RequestAttribute(value = Principal.ATTR, required = false) Principal user) {
        return chat.list(user);
    }

    @GetMapping("/api/chat/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return chat.get(id);
    }

    @GetMapping("/api/chat/{id}/references")
    public List<JsonNode> references(@PathVariable String id) {
        return chat.references(id);
    }

    @DeleteMapping("/api/chat/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        chat.delete(id);
        return Responses.noContent();
    }

    @GetMapping("/api/chat/{id}/subagents")
    public List<Map<String, Object>> subAgents(@PathVariable String id,
                                               @RequestParam(required = false) String toolUseId) {
        return chat.subAgents(id, toolUseId);
    }

    @PostMapping("/api/chat/{id}/clear")
    public Map<String, Object> clear(@PathVariable String id) {
        return chat.clear(id);
    }

    @PostMapping("/api/chat/{id}/compact")
    public Map<String, Object> compact(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return chat.compact(id, body);
    }

    @PostMapping("/api/chat/{id}/messages")
    public ResponseEntity<Map<String, Object>> send(@PathVariable String id, @RequestBody Map<String, String> body,
                                                    @RequestAttribute(value = Principal.ATTR, required = false)
                                                    Principal user) {
        return Responses.entity(chat.send(id, body, user));
    }

    @PostMapping("/api/chat/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestParam(defaultValue = "false") boolean kill) {
        return chat.cancel(id, kill);
    }

    @PostMapping("/api/chat/{id}/mode")
    public Map<String, Object> mode(@PathVariable String id, @RequestBody Map<String, String> body) {
        return chat.mode(id, body);
    }

    @GetMapping(value = "/api/chat/{id}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        SseSink sink = new SseSink(SSE_TIMEOUT_MS);
        Heartbeats.drive(chat.events(id, after, sink), sink);
        return sink.emitter();
    }

    @GetMapping("/api/content-warnings")
    public List<JsonNode> contentWarnings() {
        return chat.contentWarnings();
    }

    @GetMapping("/api/chat/{id}/approvals")
    public Object approvals(@PathVariable String id) {
        return chat.approvals(id);
    }

    @PostMapping("/api/chat/{id}/approvals/{approvalId}")
    public Map<String, Object> resolve(@PathVariable String id, @PathVariable String approvalId,
                                       @RequestBody Map<String, Object> body) {
        return chat.resolve(id, approvalId, body);
    }

    @GetMapping("/api/chat/{id}/files")
    public List<String> files(@PathVariable String id, @RequestParam(defaultValue = "") String q) {
        return chat.files(id, q);
    }

    @GetMapping("/api/chat/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        return chat.usage(id);
    }

    /** Open: the list adapts to the caller, signed in or not. */
    @GetMapping("/api/chat/commands")
    public List<Map<String, String>> commands(HttpServletRequest request) {
        Principal user = Requests.principal(request);
        if (user == null) user = authentication.current(Requests.cookies(request)).orElse(null);
        return chat.commands(user);
    }
}
