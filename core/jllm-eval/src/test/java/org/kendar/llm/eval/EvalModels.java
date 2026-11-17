package org.kendar.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ProviderCatalog;

import java.util.List;

/** The one entry point to ready-to-use harness clients, built by the harness' own {@link ProviderCatalog}. */
public final class EvalModels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalModels() {
    }

    public static ModelClient target() {
        return client(EvalRun.get().config().target());
    }

    public static ModelClient judge() {
        return client(EvalRun.get().config().judge());
    }

    /** The fast model; the target client itself unless a separate fast model is configured. */
    public static ModelClient fast() {
        EvalConfig c = EvalRun.get().config();
        return c.fastSeparate() ? client(c.fast()) : target();
    }

    /** Builds a client for an endpoint, no fallback chain. */
    public static ModelClient client(ModelEndpoint endpoint) {
        ProviderCatalog catalog = new ProviderCatalog(List.of(), MAPPER, 8192);
        return catalog.client(endpoint.toProviderSettings());
    }
}
