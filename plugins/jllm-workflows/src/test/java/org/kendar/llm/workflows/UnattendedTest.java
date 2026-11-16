package org.kendar.llm.workflows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnattendedTest {
    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static Registry reg() {
        return new Registry().action("quiet", c -> null).action("talky", c -> null).chooser("cq", c -> "x")
                .chooser("ct", c -> "x").asking("talky").askingChooser("ct");
    }

    @Test
    void unattendedFlowMayNotUseAnythingThatAsks() {
        String head = "{'name':'u','unattended':true,'start':'s','steps':{";
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'action','action':'quiet','next':'e'},'e':{'type':'finish'}}}"), reg()).isEmpty());
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'action','action':'talky','next':'e'},'e':{'type':'finish'}}}"), reg())
                .stream().anyMatch(m -> m.contains("asks questions")));
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'ask','kind':'text','next':'e'},'e':{'type':'finish'}}}"), reg())
                .stream().anyMatch(m -> m.contains("ask step")));
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'decide','by':'user','outcomes':{'a':'e'}},'e':{'type':'finish'}}}"), reg())
                .stream().anyMatch(m -> m.contains("user chooser")));
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'decide','by':'java','chooser':'ct','outcomes':{'a':'e'}},'e':{'type':'finish'}}}"), reg())
                .stream().anyMatch(m -> m.contains("chooser 'ct' asks")));
        assertTrue(FlowValidator.check(flow(head + "'s':{'type':'decide','by':'java','chooser':'cq','outcomes':{'a':'e'}},'e':{'type':'finish'}}}"), reg()).isEmpty());
    }

    @Test
    void attendedFlowMayUseAskingActions() {
        var f = flow("{'name':'a','start':'s','steps':{'s':{'type':'action','action':'talky','next':'e'},'e':{'type':'finish'}}}");
        assertTrue(FlowValidator.check(f, reg()).isEmpty());
    }
}
