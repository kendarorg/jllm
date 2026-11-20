package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.gate.GateResultsHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The checks done when a flow is saved, so problems are found before a run, not during one. */
public final class FlowValidator {
    private static final Set<String> TYPES = Set.of("task", "action", "decide", "ask", "call", "finish");
    private static final Set<String> SHAPE_TYPES = Set.of("string", "number", "boolean", "array", "object");

    private FlowValidator() {
    }

    /** @return the problems found; empty when the flow can be saved */
    public static List<String> check(FlowDef flow, Registry registry) {
        List<String> errs = new ArrayList<>();
        JsonNode doc = flow.doc();
        if (flow.name().isBlank()) errs.add("flow has no name");
        for (String k : new String[]{"retries", "questions", "depth"}) {
            JsonNode v = doc.path("limits").path(k);
            if (!v.isMissingNode() && !(v.isInt() && v.asInt() >= 0)) {
                errs.add("limit '" + k + "' must be a whole number >= 0");
            }
        }
        if (doc.has("roles")) {
            boolean ok = doc.get("roles").isArray();
            if (ok) for (JsonNode n : doc.get("roles")) ok &= n.isTextual() && !n.asText().isBlank();
            if (!ok) errs.add("'roles' must be a list of role names");
        }
        if (doc.has("unattended") && !doc.get("unattended").isBoolean()) errs.add("'unattended' must be true or false");
        checkTasks(flow, registry, errs);
        checkAccepts("", doc.path("accepts"), flow, errs);
        if (doc.has("helper") && registry != null && registry.action(doc.get("helper").asText()) == null) {
            errs.add("helper '" + doc.get("helper").asText() + "' is not registered");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = flow.steps().fields(); it.hasNext(); ) {
            var e = it.next();
            checkAccepts("step '" + e.getKey() + "': ", e.getValue().path("accepts"), flow, errs);
            if (e.getValue().has("helper") && registry != null && registry.action(e.getValue().get("helper").asText()) == null) {
                errs.add("step '" + e.getKey() + "': helper '" + e.getValue().get("helper").asText() + "' is not registered");
            }
        }
        checkSteps("", flow.steps(), flow.start(), flow, registry, errs, false);
        JsonNode h = flow.handler();
        checkSteps("onError.", h.path("steps"), h.path("start").asText(""), flow, registry, errs, true);
        if (h.path("steps").isObject()) {
            boolean anyFinish = false;
            for (Iterator<Map.Entry<String, JsonNode>> it = h.path("steps").fields(); it.hasNext(); ) {
                JsonNode s = it.next().getValue();
                if ("finish".equals(s.path("type").asText()) && s.has("outcome")) anyFinish = true;
                if ("retry".equals(s.path("outcome").asText())
                        && !flow.steps().has(s.path("retryStep").asText(""))) {
                    errs.add("handler retry points to unknown step '" + s.path("retryStep").asText() + "'");
                }
            }
            if (!anyFinish) errs.add("error handler has no finish step with an outcome");
        }
        return errs;
    }

    private static void checkAccepts(String pre, JsonNode acc, FlowDef flow, List<String> errs) {
        if (acc.isMissingNode()) return;
        if (!acc.isObject()) {
            errs.add(pre + "accepts must be an object");
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = acc.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode d = e.getValue();
            String p = pre + "accepts '" + e.getKey() + "': ";
            String action = d.path("action").asText("");
            if (!Set.of("cancel", "update", "redirect").contains(action)) {
                errs.add(p + "action must be cancel, update or redirect");
            }
            if (action.equals("redirect") && !flow.steps().has(d.path("to").asText(""))) {
                errs.add(p + "redirect leads to unknown step");
            }
            if (d.has("kind") && !Set.of("text", "single", "multi").contains(d.get("kind").asText())) {
                errs.add(p + "kind must be text, single or multi");
            }
        }
    }

    private static void checkTasks(FlowDef flow, Registry reg, List<String> errs) {
        for (Iterator<Map.Entry<String, JsonNode>> it = flow.tasks().fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode t = e.getValue();
            String p = "task '" + e.getKey() + "': ";
            if (!t.path("prompt").isTextual()) errs.add(p + "no prompt");
            for (String role : new String[]{"before", "after", "instead"}) {
                if (t.has(role) && reg != null && reg.action(t.get(role).asText()) == null) {
                    errs.add(p + role + " action '" + t.get(role).asText() + "' is not registered");
                }
                if (flow.doc().path("unattended").asBoolean(false) && t.has(role) && reg != null
                        && reg.asks(t.get(role).asText())) {
                    errs.add(p + role + " action '" + t.get(role).asText() + "' asks questions; the flow is unattended");
                }
            }
            if (!t.has("instead") && !t.path("agent").isTextual()) errs.add(p + "no agent");
            if (t.has("clarify") && !t.get("clarify").isBoolean()) errs.add(p + "clarify must be true or false");
            JsonNode tl = t.path("tools");
            boolean toolsOk = tl.isMissingNode() || tl.isTextual() && tl.asText().equals("*") || tl.isArray();
            if (tl.isArray()) for (JsonNode n : tl) toolsOk &= n.isTextual() && !n.asText().isBlank();
            if (!toolsOk) errs.add(p + "tools must be \"*\" (all tools of the role) or a list of tool names");
            JsonNode a = t.path("answer");
            if (a.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> f = a.fields(); f.hasNext(); ) {
                    var fe = f.next();
                    if (!SHAPE_TYPES.contains(fe.getValue().asText())) {
                        errs.add(p + "answer field '" + fe.getKey() + "' has unknown type");
                    }
                }
            } else if (!a.isMissingNode() && !"text".equals(a.asText())) {
                errs.add(p + "answer must be \"text\" or an object of field types");
            }
        }
    }

    private static void checkSteps(String pre, JsonNode steps, String start, FlowDef flow, Registry reg,
                                   List<String> errs, boolean handler) {
        if (!steps.isObject() || steps.isEmpty()) {
            errs.add(pre + "steps missing");
            return;
        }
        if (!steps.has(start)) errs.add(pre + "start step '" + start + "' does not exist");
        for (Iterator<Map.Entry<String, JsonNode>> it = steps.fields(); it.hasNext(); ) {
            var e = it.next();
            String id = pre + "step '" + e.getKey() + "': ";
            JsonNode s = e.getValue();
            String type = s.path("type").asText("");
            if (!TYPES.contains(type)) {
                errs.add(id + "unknown type '" + type + "'");
                continue;
            }
            for (String k : new String[]{"retries", "questions"}) {
                if (s.has(k) && !(s.get(k).isInt() && s.get(k).asInt() >= 0)) {
                    errs.add(id + k + " must be a whole number >= 0");
                }
            }
            if (s.has("with") && !s.get("with").isObject()) errs.add(id + "'with' must be an object");
            if (flow.doc().path("unattended").asBoolean(false)) checkUnattended(id, type, s, reg, errs);
            switch (type) {
                case "task" -> {
                    if (!flow.tasks().has(s.path("task").asText(""))) {
                        errs.add(id + "unknown task '" + s.path("task").asText() + "'");
                    }
                    needNext(steps, s, id, errs);
                }
                case "action" -> {
                    if (reg != null && reg.action(s.path("action").asText("")) == null) {
                        errs.add(id + "action '" + s.path("action").asText() + "' is not registered");
                    }
                    if (BuiltinActions.RUN_COMMAND.equals(s.path("action").asText())) checkRunCommand(id, s, errs);
                    needNext(steps, s, id, errs);
                }
                case "decide" -> {
                    checkDecide(steps, s, id, reg, errs);
                    if ("java".equals(s.path("by").asText()) && BuiltinActions.GATE.equals(s.path("chooser").asText())) {
                        checkGate(steps, flow, s, id, errs);
                    }
                }
                case "call" -> {
                    if (!s.has("flow") && !(s.has("chooser") && s.path("allowed").isArray())) {
                        errs.add(id + "call needs 'flow', or 'chooser' with an 'allowed' list");
                    }
                    if (s.has("flow") && s.get("flow").asText().equals(flow.name())
                            && s.path("scope").asText("personal").equals(flow.scope())) {
                        errs.add(id + "a flow may not call itself");
                    }
                    if (s.has("chooser") && reg != null && reg.chooser(s.get("chooser").asText()) == null) {
                        errs.add(id + "chooser '" + s.get("chooser").asText() + "' is not registered");
                    }
                    needNext(steps, s, id, errs);
                }
                case "ask" -> {
                    if (!Set.of("text", "single", "multi").contains(s.path("kind").asText(""))) {
                        errs.add(id + "ask kind must be text, single or multi");
                    }
                    if (!s.path("kind").asText("").equals("text") && !s.path("options").isObject()) {
                        errs.add(id + "ask needs options");
                    }
                    if (s.has("onTimeout") && !steps.has(s.get("onTimeout").asText())) {
                        errs.add(id + "onTimeout leads to unknown step");
                    }
                    needNext(steps, s, id, errs);
                }
                case "finish" -> {
                    if (handler && !Set.of("recovered", "retry", "rethrow").contains(s.path("outcome").asText(""))) {
                        errs.add(id + "handler finish needs outcome recovered, retry or rethrow");
                    }
                    if (!handler && s.has("outcome")) errs.add(id + "'outcome' is only for error handlers");
                }
                default -> {
                }
            }
        }
    }

    /** Nobody could answer in an unattended flow, so nothing that asks a person may appear in it. */
    private static void checkUnattended(String id, String type, JsonNode s, Registry reg, List<String> errs) {
        switch (type) {
            case "ask" -> errs.add(id + "ask step in an unattended flow: nobody could answer");
            case "decide" -> {
                if ("user".equals(s.path("by").asText())) errs.add(id + "user chooser in an unattended flow");
                if (reg != null && reg.chooserAsks(s.path("chooser").asText(""))) {
                    errs.add(id + "chooser '" + s.path("chooser").asText() + "' asks questions; the flow is unattended");
                }
            }
            case "action" -> {
                if (reg != null && reg.asks(s.path("action").asText(""))) {
                    errs.add(id + "action '" + s.path("action").asText() + "' asks questions; the flow is unattended");
                }
            }
            case "call" -> {
                if (reg != null && reg.chooserAsks(s.path("chooser").asText(""))) {
                    errs.add(id + "chooser '" + s.path("chooser").asText() + "' asks questions; the flow is unattended");
                }
            }
            default -> {
            }
        }
    }

    /** A gate is built here, so a bad configuration fails when the flow is saved, not during a run. */
    private static void checkGate(JsonNode steps, FlowDef flow, JsonNode s, String id, List<String> errs) {
        JsonNode with = s.path("with");
        if (!with.isObject()) {
            errs.add(id + "gate needs a 'with' object");
            return;
        }
        String from = with.path("from").asText("").trim();
        String[] parts = from.split("[.]");
        boolean fromOk = with.path("from").isTextual() && (parts[0].equals("input")
                || parts[0].equals("steps") && parts.length > 1 && (flow.steps().has(parts[1]) || steps.has(parts[1])));
        if (!fromOk) errs.add(id + "gate 'from' must be input or steps.<step>, not '" + from + "'");
        GateResultsHandler gate;
        try {
            gate = new GateResultsHandler(GateResultsHandler.Config.fromJson(with));
        } catch (IllegalArgumentException e) {
            errs.add(id + "gate: " + e.getMessage());
            return;
        }
        for (String r : gate.responses()) {
            if (!s.path("outcomes").has(r)) errs.add(id + "gate response '" + r + "' is not a declared outcome");
        }
    }

    private static void checkRunCommand(String id, JsonNode s, List<String> errs) {
        JsonNode with = s.path("with");
        if (!with.path("command").isTextual() || with.path("command").asText().isBlank()) {
            errs.add(id + "run-command needs a 'command' in 'with'");
        }
        JsonNode t = with.path("timeoutSeconds");
        if (!t.isMissingNode() && !(t.isNumber() && t.asDouble() > 0)) {
            errs.add(id + "run-command timeoutSeconds must be a positive number");
        }
        JsonNode l = with.path("tailLines");
        if (!l.isMissingNode() && !(l.isInt() && l.asInt() >= 0)) {
            errs.add(id + "run-command tailLines must be a whole number >= 0");
        }
    }

    private static void needNext(JsonNode steps, JsonNode s, String id, List<String> errs) {
        if (!steps.has(s.path("next").asText(""))) {
            errs.add(id + "next '" + s.path("next").asText() + "' does not exist");
        }
    }

    private static void checkDecide(JsonNode steps, JsonNode s, String id, Registry reg, List<String> errs) {
        String by = s.path("by").asText("");
        if (!Set.of("java", "llm", "user").contains(by)) errs.add(id + "'by' must be java, llm or user");
        if (by.equals("java") && reg != null && reg.chooser(s.path("chooser").asText("")) == null) {
            errs.add(id + "chooser '" + s.path("chooser").asText() + "' is not registered");
        }
        if (by.equals("llm") && !s.path("agent").isTextual()) errs.add(id + "llm chooser needs an agent");
        JsonNode out = s.path("outcomes");
        if (!out.isObject() || out.isEmpty()) {
            errs.add(id + "no outcomes");
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = out.fields(); it.hasNext(); ) {
            var o = it.next();
            if (!steps.has(o.getValue().asText())) errs.add(id + "outcome '" + o.getKey() + "' leads to unknown step");
        }
        if (s.has("default") && !steps.has(s.get("default").asText())) {
            errs.add(id + "default leads to unknown step");
        }
    }
}
