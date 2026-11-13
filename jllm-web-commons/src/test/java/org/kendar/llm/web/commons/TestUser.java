package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;

/** A stub principal and the owner-or-admin chat rule, for tests of the common API without a sign-in module. */
record TestUser(String id, boolean admin) implements WebPrincipal {

    static final ChatAccess OWNER_OR_ADMIN = (user, chat) -> chat.principal() != null
            && (user.admin() || chat.principal().equals(user.id()));

    static ChatController chatController(AgentHarness harness, ObjectMapper mapper) {
        return new ChatController(harness, mapper, new HarnessPricing(0, 0, 0, 0), WebAuthentication.NONE,
                OWNER_OR_ADMIN);
    }
}
