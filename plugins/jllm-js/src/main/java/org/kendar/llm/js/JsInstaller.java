package org.kendar.llm.js;

import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.slf4j.LoggerFactory;

/** Registers {@code run_js}; reached only when the probe found GraalJS, so it may use GraalJS types. */
final class JsInstaller {

    private JsInstaller() {
    }

    static void install(PluginRegistrar registrar, JsSettings settings, GraalProbe.Result caps) {
        JsProfiles.Selection sel = JsProfiles.select(settings, caps);
        if (sel.profile() == null) {
            LoggerFactory.getLogger(JsPlugin.class).warn("Plugin 'js' is disabled, run_js is not available: {}",
                    sel.reason());
            return;
        }
        JsSandbox sandbox = new JsSandbox(settings, sel.profile());
        LoggerFactory.getLogger(JsPlugin.class).info("run_js: {}; profile {}", caps.summary(), sel.profile().describe());
        registrar.toolNameCheck(ToolLibrary::checkNames);
        registrar.tool(RunJsTool.create(sandbox, registrar.catalog(), new ToolLibrary(), status(caps, sandbox)));
    }

    /** Edition and profile as the administrator's tool picker shows them. */
    static String status(GraalProbe.Result caps, JsSandbox sandbox) {
        return "edition " + caps.edition().name().toLowerCase(java.util.Locale.ROOT) + ", profile " + sandbox.profileName()
                + ", isolate " + (caps.isolate() ? "available" : "not available");
    }
}
