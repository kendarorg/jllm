package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.tools.HostApprover;
import org.kendar.llm.harness.tools.HostMatcher;

import java.util.function.BooleanSupplier;

/**
 * Per-host approval for the web tools. A host the chat has granted passes (also after untrusted output was read:
 * a host not granted always asks, which is what stops data leaving). Not interactive: refused.
 */
public final class ChatHostApprover implements HostApprover {
    private final ApprovalService approvals;
    private final HarnessMetrics metrics;
    private final ObjectMapper mapper;
    private final String chatId;
    private final EventSink sink;
    private final BooleanSupplier cancelled;
    private final boolean interactive;
    private final String label;

    public ChatHostApprover(ApprovalService approvals, HarnessMetrics metrics, ObjectMapper mapper, String chatId,
                            EventSink sink, BooleanSupplier cancelled, boolean interactive, String label) {
        this.approvals = approvals;
        this.metrics = metrics;
        this.mapper = mapper;
        this.chatId = chatId;
        this.sink = sink;
        this.cancelled = cancelled;
        this.interactive = interactive;
        this.label = label;
    }

    @Override
    public boolean approve(String host, String url) {
        if (approvals.hostGranted(chatId, host)) return true;
        if (!interactive) return false;
        String h = HostMatcher.normalize(host);
        ApprovalService.ApprovalDecision d = approvals.await(chatId, "host", "Allow web access to " + h,
                url, null, a -> {
                    ObjectNode ev = mapper.valueToTree(a);
                    if (label != null) ev.put("agent", label);
                    ev.put("host", h);
                    sink.emit("approval_request", ev);
                }, cancelled, () -> approvals.hostGranted(chatId, host));
        if (d.approved() && d.remember()) approvals.grantHost(chatId, host);
        metrics.approval("host", d.approved());
        return d.approved();
    }

    /** Per call, never remembered: a bad certificate is asked about every time. */
    @Override
    public boolean approveInsecure(String host, String url, String reason) {
        if (!interactive) return false;
        String h = HostMatcher.normalize(host);
        ApprovalService.ApprovalDecision d = approvals.await(chatId, "insecure_cert",
                "Invalid certificate for " + h + ": continue anyway?",
                "The certificate of " + h + " is not valid:\n" + reason + "\n\nURL: " + url
                        + "\n\nAllowing sends this one request without checking who is on the other end.",
                null, a -> {
                    ObjectNode ev = mapper.valueToTree(a);
                    if (label != null) ev.put("agent", label);
                    ev.put("host", h);
                    sink.emit("approval_request", ev);
                }, cancelled);
        metrics.approval("insecure_cert", d.approved());
        return d.approved();
    }
}
