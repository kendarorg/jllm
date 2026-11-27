package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.OneShotModel;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.workflows.WorkflowApi;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic flows. Contributes slash commands and publishes the {@link WorkflowApi} service; flows are never
 * exposed to the model as tools. When the plugin is absent or disabled none of this exists.
 *
 * <p>Settings: {@code dir} (where flows and runs are kept; default {@code ~/.jllm-workflows}), {@code adminRole}
 * (default {@code admin}), {@code chatUser} (the user chat commands act as; default {@code local}) and
 * {@code chatRoles}. Flows and runs go to the host's {@link PluginStorages} when it publishes one. The model comes from the host's {@link OneShotModel} service when it publishes one.
 */
public final class WorkflowsPlugin implements HarnessPlugin {

    public static final String ID = "jllm-workflows";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void install(PluginRegistrar registrar, JsonNode settings) {
        Path dir = Path.of(settings.path("dir").asText(System.getProperty("user.home") + "/.jllm-workflows"));
        String admin = settings.path("adminRole").asText("admin");
        String user = settings.path("chatUser").asText("local");
        Set<String> roles = new HashSet<>();
        settings.path("chatRoles").forEach(n -> roles.add(n.asText()));

        Registry registry = ActionProvider.load(WorkflowsPlugin.class.getClassLoader());
        var services = registrar.services();
        registry.model(c -> {
            try {
                return services.lookup(OneShotModel.class)
                        .orElseThrow(() -> new IllegalStateException("The host offers no model for flows"))
                        .complete(new OneShotModel.Call(c.agent(), c.system(), c.user(), c.owner(), c.title(), c.approved(), c.tools(),
                                c.unattended(), c.onDenied()));
            } catch (OneShotModel.ApprovalRequiredException e) { // the engine asks the user, or fails an unattended flow
                throw new Registry.ApprovalNeededException(e.getMessage(), e.tools());
            }
        });
        registry.classifier((sys, question) -> services.lookup(OneShotModel.class)
                .orElseThrow(() -> new IllegalStateException("The host offers no model for flows")).classify(sys, question));
        // the web application publishes its plugin storage; without it flows live in files
        var storage = services.lookup(PluginStorages.class).map(s -> s.forPlugin(ID));
        RunStore runs = storage.<RunStore>map(PluginStores.Runs::new).orElseGet(() -> new RunStore.Files(dir.resolve("runs")));
        FlowStore flows = storage.<FlowStore>map(PluginStores.Flows::new).orElseGet(() -> new FlowStore.Files(dir.resolve("flows")));
        WorkflowApiImpl api = WorkflowApiImpl.create(registry, runs, flows, admin,
                services.lookup(org.kendar.llm.harness.workflows.UnattendedRoles.class)
                        .<java.util.function.Predicate<Set<String>>>map(u -> u::allowed).orElse(null));
        services.publish(WorkflowApi.class, api);

        FlowCommands c = new FlowCommands(api, user, roles);
        registrar.command("/flows", "List the flows you can start", c::list);
        registrar.command("/flow-start", "Start a flow: /flow-start [--chat] NAME [JSON input]", c::start);
        registrar.command("/flow-resume", "Answer a waiting run: /flow-resume RUN ANSWER", c::resume);
        registrar.command("/flow-request", "Send a request to a run: /flow-request RUN KIND [JSON]", c::request);
        registrar.command("/flow-cancel", "Cancel a run: /flow-cancel RUN", c::cancel);
    }
}
