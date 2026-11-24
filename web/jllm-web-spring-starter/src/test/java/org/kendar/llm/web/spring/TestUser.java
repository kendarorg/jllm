package org.kendar.llm.web.spring;

import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.identity.ChatAccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.web.commons.ChatApi;
import org.kendar.llm.web.commons.WebAuthentication;

/** A stub principal and the owner-or-admin chat rule, for tests of the common API without a sign-in module. */
record TestUser(String id, boolean admin) implements Principal {

    static final ChatAccess OWNER_OR_ADMIN = (user, chat) -> chat.principal() != null
            && (user.admin() || chat.principal().equals(user.id()));

    static ChatController chatController(AgentHarness harness, ObjectMapper mapper) {
        return new ChatController(new ChatApi(harness, mapper, new HarnessPricing(0, 0, 0, 0), OWNER_OR_ADMIN),
                WebAuthentication.NONE);
    }
}
