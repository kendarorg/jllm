package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.llm.LimitedModelClient;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelSwitch;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.settings.HarnessSettings;

/** Resolves the main model: the provider catalog, the switchable client and the parallelism limit around it. */
final class ModelAssembler {

    /** @param client the client the loop uses: switchable, and limited when {@code maxParallel} is above zero */
    record Models(ModelClient client, ProviderCatalog providers, ModelSwitch modelSwitch, int maxParallel) {
    }

    private ModelAssembler() {
    }

    static Models assemble(HarnessSettings settings, HarnessConfig config, ObjectMapper mapper,
                           ModelClient injected, String injectedProvider, ProviderSettings sessionProvider) {
        ProviderCatalog providers = new ProviderCatalog(settings.providers(), mapper, config.maxTokens());
        if (sessionProvider != null) providers.setCustom(sessionProvider);
        ModelClient client = injected;
        String activeProvider = injectedProvider;
        if (client == null && settings.defaultProvider() != null) {
            client = providers.client(settings.defaultProvider());
            activeProvider = settings.defaultProvider();
        }
        if (client == null) throw new IllegalStateException("modelClient is required");
        ProviderSettings active = providers.find(activeProvider);
        ModelSwitch modelSwitch = new ModelSwitch(client, activeProvider, active == null ? null : active.contextTokens());
        client = modelSwitch;
        int maxParallel = settings.maxParallel();
        if (maxParallel > 0) client = new LimitedModelClient(client, maxParallel);
        return new Models(client, providers, modelSwitch, maxParallel);
    }
}
