package org.kendar.llm.js;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.slf4j.LoggerFactory;

/**
 * Adds {@code run_js}, a JavaScript sandbox over the harness tools. {@code bash} is left alone: whether a user may run
 * the script tool, the shell or both is decided by their role. A user who has {@code bash} has a host shell, a second,
 * unrestricted way out, so give it only to roles that are trusted with the host.
 *
 * <p>GraalJS is not bundled: the host carries it. The plugin installs by probing first; when GraalJS (or its regex
 * language) is absent, or the {@code engine} setting asks for an isolate the runtime lacks, it logs one warning and
 * registers nothing, so the harness starts without {@code run_js}. This class names no GraalJS type, so it loads
 * without them.
 *
 * <p>Settings are those of {@link JsSettings}; an invalid value fails the install with a message naming the key.
 */
public final class JsPlugin implements HarnessPlugin {

    @Override
    public String id() {
        return "js";
    }

    @Override
    public void install(PluginRegistrar registrar, JsonNode settings) {
        JsSettings s = JsSettings.from(settings);
        GraalProbe.Result caps = GraalProbe.probe();
        if (!caps.usable()) {
            LoggerFactory.getLogger(JsPlugin.class).warn("Plugin 'js' is disabled, run_js is not available: {}",
                    caps.unusableReason());
            return;
        }
        JsInstaller.install(registrar, s, caps);
    }
}
