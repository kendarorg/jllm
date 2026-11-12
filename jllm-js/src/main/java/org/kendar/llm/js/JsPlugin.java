package org.kendar.llm.js;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;

/**
 * Adds {@code run_js}, a JavaScript sandbox over the harness tools, and removes {@code bash}: a host shell would be a
 * second, unrestricted way out. Only meaningful in a profile where no tool gives host access.
 */
public final class JsPlugin implements HarnessPlugin {

    @Override
    public String id() {
        return "js";
    }

    @Override
    public void install(PluginRegistrar registrar, JsonNode settings) {
        JsSettings s = JsSettings.from(settings);
        registrar.replaces("bash");
        registrar.toolNameCheck(ToolLibrary::checkNames);
        registrar.tool(RunJsTool.create(new JsSandbox(s), registrar.catalog(), new ToolLibrary()));
    }
}
