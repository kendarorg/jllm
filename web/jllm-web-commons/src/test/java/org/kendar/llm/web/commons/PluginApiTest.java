package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.plugin.WebPluginHost;
import org.kendar.llm.web.commons.plugins.PluginApi;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginApiTest {

    private static PluginApi api() {
        ServiceRegistry services = new ServiceRegistry();
        return new PluginApi(new WebPluginHost(services, (PluginStorages) null, null));
    }

    @Test
    void nobodyIs401() {
        ApiException e = assertThrows(ApiException.class,
                () -> api().handle("x", "GET", "/a", Map.of(), null, null));
        assertEquals(401, e.status());
    }

    @Test
    void aStreamOfAnUnknownPluginIs404() {
        ApiException e = assertThrows(ApiException.class,
                () -> api().events("x", new Fixtures.User("alice", false), new Fixtures.RecordingSink()));
        assertEquals(404, e.status());
    }
}
