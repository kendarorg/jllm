package org.kendar.llm.pdf;

import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;

/** Lets {@code read_file} read PDF files. */
public final class PdfPlugin implements HarnessPlugin {

    @Override
    public String id() {
        return "pdf";
    }

    @Override
    public void install(PluginRegistrar registrar) {
        registrar.documentReader(new PdfReader());
    }
}
