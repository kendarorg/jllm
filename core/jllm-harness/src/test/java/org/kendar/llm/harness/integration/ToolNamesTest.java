package org.kendar.llm.harness.integration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolNamesTest {

    @Test
    void sanitizesAndTruncates() {
        assertEquals("mcp__srv__a_b", ToolNames.of("mcp__srv__a.b"));
        assertEquals(64, ToolNames.of("x".repeat(100)).length());
    }

    @Test
    void sameNameTwiceInOneSourceNamesBothTools() {
        ToolNames.SourceNames names = new ToolNames.SourceNames();
        names.add("mcp__srv__a.b", "MCP server 'srv' tool 'a.b'");
        ToolNames.CollisionException e = assertThrows(ToolNames.CollisionException.class,
                () -> names.add("mcp__srv__a_b", "MCP server 'srv' tool 'a_b'"));
        assertEquals("mcp__srv__a_b", e.toolName());
        assertTrue(e.getMessage().contains("tool 'a.b'"), e.getMessage());
        assertTrue(e.getMessage().contains("tool 'a_b'"), e.getMessage());
    }

    @Test
    void claimsRefuseANameOfAnotherSourceAndTakeNothing() {
        ToolNames.Claims claims = new ToolNames.Claims();
        claims.claimAll("mcp:a__b", Map.of("mcp__a__b__c", "MCP server 'a__b' tool 'c'"));
        ToolNames.CollisionException e = assertThrows(ToolNames.CollisionException.class,
                () -> claims.claimAll("mcp:a", Map.of("mcp__a__x", "MCP server 'a' tool 'x'",
                        "mcp__a__b__c", "MCP server 'a' tool 'b__c'")));
        assertEquals("MCP server 'a__b' tool 'c'", e.first());
        assertEquals("MCP server 'a' tool 'b__c'", e.second());
        assertNull(claims.owner("mcp__a__x"));
    }

    @Test
    void reclaimingASourceReplacesItsNamesAndReleaseFreesThem() {
        ToolNames.Claims claims = new ToolNames.Claims();
        claims.claimAll("api:p", Map.of("p__get", "op get"));
        claims.claimAll("api:p", Map.of("p__get", "op get", "p__put", "op put"));
        assertEquals("op put", claims.owner("p__put"));
        claims.release("api:p");
        assertNull(claims.owner("p__get"));
        claims.claimAll("api:q", Map.of("p__get", "other"));
        assertEquals("other", claims.owner("p__get"));
    }
}
