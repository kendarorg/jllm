package org.kendar.llm.docs;

import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;

/** Lets {@code read_file} read docx files. */
public final class DocsPlugin implements HarnessPlugin {

    @Override
    public void install(PluginRegistrar registrar) {
        registrar.documentReader(new DocxReader());
    }
}
