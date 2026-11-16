package org.kendar.llm.js;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;

/**
 * Adds {@code run_js}, a JavaScript sandbox over the harness tools. {@code bash} is left alone: whether a user may run
 * the script tool, the shell or both is decided by their role. A user who has {@code bash} has a host shell, a second,
 * unrestricted way out, so give it only to roles that are trusted with the host.
 */
public final class JsPlugin implements HarnessPlugin {

    @Override
    public String id() {
        return "js";
    }

    @Override
    public void install(PluginRegistrar registrar, JsonNode settings) {
        JsSettings s = JsSettings.from(settings);
        registrar.toolNameCheck(ToolLibrary::checkNames);
        registrar.tool(RunJsTool.create(new JsSandbox(s), registrar.catalog(), new ToolLibrary()));
    }
}
