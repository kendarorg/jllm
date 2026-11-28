package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.agent.ToolExecutor.AuthorizeInput;
import org.kendar.llm.harness.agent.ToolExecutor.AuthorizeOutcome;
import org.kendar.llm.harness.permission.Decision;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.permission.PermissionRules.Kind;
import org.kendar.llm.harness.permission.ToolClassifier.Verdict;
import org.kendar.llm.harness.permission.ToolPermission;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization of the permission decision: {@code legacy} is the decision code of {@code authorize} as it was
 * before it became a pure function, copied verbatim; {@code decide} must give the same outcome on the whole input space.
 */
class ToolExecutorAuthorizeTest {

    private static AuthorizeOutcome legacy(AuthorizeInput in) {
        boolean free = in.free() && (in.readOnly() || !in.tainted());
        boolean adminAllowed = in.admin() == ToolPermission.ALLOW;
        Decision decision = free || adminAllowed ? Decision.ALLOW : in.gate();
        if (in.admin() == ToolPermission.ASK && !free) decision = Decision.ASK;
        if (!free && !adminAllowed && decision == Decision.ALLOW && in.mode() == PermissionMode.AUTO
                && in.classifierAvailable() && !in.readOnly() && !in.hookDenied()) {
            if (in.classifier() != Verdict.SAFE) decision = Decision.ASK;
        }
        Kind rule = in.rule();
        if (!free && !adminAllowed && rule != null && rule == Kind.ASK && decision == Decision.ALLOW) {
            decision = Decision.ASK;
        }
        if (decision == Decision.ASK && in.grant()) decision = Decision.ALLOW;
        if (rule != null && rule == Kind.ALLOW && decision == Decision.ASK && in.mode() != PermissionMode.AUTO) {
            decision = Decision.ALLOW;
        }
        boolean warn = false;
        if (!free && !adminAllowed && decision == Decision.ALLOW && !in.readOnly() && in.tainted()
                && (rule == null || rule != Kind.DENY)) {
            decision = Decision.ASK;
            warn = true;
        }
        boolean unavailable = false;
        if (decision == Decision.ASK && !in.interactive()) {
            decision = Decision.DENY;
            unavailable = true;
        }
        return new AuthorizeOutcome(decision, warn, unavailable);
    }

    private static final boolean[] B = {false, true};

    @Test
    void decideMatchesTheCharacterizedBehaviourOnTheWholeInputSpace() {
        List<Kind> rules = new ArrayList<>();
        rules.add(null);
        rules.addAll(List.of(Kind.values()));
        int n = 0;
        for (boolean free : B) for (boolean ro : B) for (ToolPermission admin : adminValues())
            for (PermissionMode mode : PermissionMode.values()) for (Decision gate : Decision.values())
                for (Kind rule : rules) for (boolean grant : B) for (boolean tainted : B) for (boolean hook : B)
                    for (boolean cls : B) for (Verdict v : Verdict.values()) for (boolean inter : B) {
                        AuthorizeInput in = new AuthorizeInput(free, ro, admin, mode, gate, rule, grant, tainted,
                                hook, cls, v, inter, false);
                        assertEquals(legacy(in), ToolExecutor.decide(in), in.toString());
                        n++;
                    }
        assertTrue(n > 10_000);
    }

    private static List<ToolPermission> adminValues() {
        List<ToolPermission> l = new ArrayList<>(List.of(ToolPermission.values()));
        l.remove(ToolPermission.DENY); // denied earlier: never reaches the decision
        return l;
    }

    @Test
    void protectedWriteAsksInEveryModeAndIsDeniedWhenNobodyCanAnswer() {
        for (PermissionMode mode : PermissionMode.values()) {
            for (Kind rule : new Kind[]{null, Kind.ALLOW}) {
                for (boolean grant : B) {
                    AuthorizeInput in = new AuthorizeInput(false, false, null, mode, Decision.ALLOW, rule, grant,
                            false, false, false, null, true, true);
                    assertEquals(Decision.ASK, ToolExecutor.decide(in).decision(), in.toString());
                    AuthorizeInput un = new AuthorizeInput(false, false, null, mode, Decision.ALLOW, rule, grant,
                            false, false, false, null, false, true);
                    assertEquals(Decision.DENY, ToolExecutor.decide(un).decision(), un.toString());
                }
            }
        }
        AuthorizeInput admin = new AuthorizeInput(false, false, ToolPermission.ALLOW, PermissionMode.AUTO,
                Decision.ALLOW, null, false, false, false, false, null, true, true);
        assertEquals(Decision.ASK, ToolExecutor.decide(admin).decision());
    }

    @Test
    void classifierIsConsultedOnlyForSideEffectsInAutoModeAllowedByTheGate() {
        AuthorizeInput base = new AuthorizeInput(false, false, null, PermissionMode.AUTO, Decision.ALLOW, null, false,
                false, false, true, null, true, false);
        assertTrue(base.consultsClassifier());
        assertTrue(!new AuthorizeInput(false, true, null, PermissionMode.AUTO, Decision.ALLOW, null, false,
                false, false, true, null, true, false).consultsClassifier());
        assertTrue(!new AuthorizeInput(false, false, null, PermissionMode.AUTO, Decision.ASK, null, false,
                false, false, true, null, true, false).consultsClassifier());
        assertEquals(Decision.ASK, ToolExecutor.decide(base.withClassifier(Verdict.UNSURE)).decision());
        assertEquals(Decision.ALLOW, ToolExecutor.decide(base.withClassifier(Verdict.SAFE)).decision());
    }
}
