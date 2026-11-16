package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.plugin.PluginStorage;

import java.util.List;
import java.util.Optional;

/** Flow and run stores over the host's plugin storage, so they follow whichever storage the host is configured with. */
public final class PluginStores {
    private PluginStores() {
    }

    public static final class Flows implements FlowStore {
        private static final String C = "flows";
        private final PluginStorage st;

        public Flows(PluginStorage st) {
            this.st = st;
        }

        private static String owner(String scope, String user) {
            return FlowLibrary.GLOBAL.equals(scope) ? PluginStorage.GLOBAL : user;
        }

        private List<FlowDef> all(String owner) {
            return st.ids(owner, C).stream().map(id -> st.get(owner, C, id).orElse(null))
                    .filter(java.util.Objects::nonNull).map(n -> FlowDef.parse(n.toString())).toList();
        }

        @Override
        public List<FlowDef> listGlobal() {
            return all(PluginStorage.GLOBAL);
        }

        @Override
        public List<FlowDef> listPersonal(String user) {
            return all(user);
        }

        @Override
        public Optional<FlowDef> get(String scope, String user, String name) {
            return st.get(owner(scope, user), C, name).map(n -> FlowDef.parse(n.toString()));
        }

        @Override
        public void save(String scope, String user, FlowDef flow) {
            st.put(owner(scope, user), C, flow.name(), flow.doc());
        }

        @Override
        public void delete(String scope, String user, String name) {
            st.delete(owner(scope, user), C, name);
        }
    }

    public static final class Runs implements RunStore {
        private static final String C = "runs", IDX = "runindex";
        private final PluginStorage st;

        public Runs(PluginStorage st) {
            this.st = st;
        }

        @Override
        public void save(RunRecord run) {
            st.put(run.user, C, run.runId, run.toJson());
            st.put(PluginStorage.GLOBAL, IDX, run.runId, Json.M.createObjectNode().put("user", run.user));
        }

        @Override
        public Optional<RunRecord> load(String runId) {
            return st.get(PluginStorage.GLOBAL, IDX, runId).flatMap(ix ->
                    st.get(ix.path("user").asText(), C, runId)).map(RunRecord::fromJson);
        }

        @Override
        public List<RunRecord> list(String user) {
            return st.ids(user, C).stream().map(id -> st.get(user, C, id).orElse(null))
                    .filter(java.util.Objects::nonNull).map(RunRecord::fromJson).toList();
        }

        @Override
        public List<RunRecord> listAll() {
            return st.owners(C).stream().flatMap(u -> list(u).stream()).toList();
        }
    }
}
