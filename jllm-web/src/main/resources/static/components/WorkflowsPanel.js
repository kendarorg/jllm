import {computed, onMounted, onUnmounted, reactive, ref} from 'vue';
import {onRunChanged, refreshWaiting, wf} from '../workflows.js';
import {toast} from '../store.js';
import QuestionForm from './QuestionForm.js';

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED'];

/**
 * The workflow screens: flows (own and global: start, copy, edit, delete) and runs (follow, answer, history, cancel).
 * Everything goes through the jllm-workflow-web endpoints; the engine decides what is allowed.
 */
export default {
  components: {QuestionForm},
  props: {me: {type: Object, required: true}, focusRun: {type: String, default: null}},
  setup(props) {
    const tab = ref('runs');
    const flows = ref([]);
    const runs = ref([]);
    const busy = ref(false);
    const open = ref(null);             // the run being looked at: {run, history}
    const edit = reactive({scope: 'user', text: '', open: false});
    const starting = reactive({flow: null, inputs: [], values: {}});

    const isAdmin = computed(() => props.me.role === 'admin');
    const guard = fn => async (...a) => {
      busy.value = true;
      try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } finally { busy.value = false; }
    };

    const show = guard(async id => {
      open.value = {run: await wf.run(id), history: await wf.history(id)};
    });
    const load = guard(async () => {
      flows.value = await wf.flows();
      runs.value = await wf.runs();
      if (open.value) open.value = {run: await wf.run(open.value.run.runId), history: await wf.history(open.value.run.runId)};
      await refreshWaiting();
    });

    let stop = null;
    onMounted(async () => {
      await load();
      if (props.focusRun) await show(props.focusRun);
      stop = onRunChanged(() => load());
    });
    onUnmounted(() => { if (stop) stop(); });

    const beginStart = guard(async f => {
      starting.inputs = await wf.inputs(f.scope, f.name);
      starting.values = Object.fromEntries(starting.inputs.map(i => [i, '']));
      starting.flow = f;
    });
    const doStart = guard(async () => {
      const f = starting.flow;
      const run = await wf.start(f.name, f.scope, {...starting.values});
      starting.flow = null;
      tab.value = "runs";
      await load();
      open.value = {run, history: await wf.history(run.runId)};
    });
    const copyFlow = guard(async f => {
      const name = prompt('Name of your copy', f.name);
      if (!name) return;
      await wf.copy(f.name, name);
      toast('Copied');
      await load();
    });
    const beginEdit = guard(async f => {
      edit.scope = f.scope;
      edit.text = JSON.stringify(await wf.flow(f.scope, f.name), null, 2);
      edit.open = true;
    });
    function beginNew(scope) {
      edit.scope = scope;
      edit.text = JSON.stringify({name: '', start: 'end', steps: {end: {type: 'finish'}}}, null, 2);
      edit.open = true;
    }
    const saveFlow = guard(async () => {
      await wf.save(edit.scope, JSON.parse(edit.text));
      edit.open = false;
      toast('Saved');
      await load();
    });
    const removeFlow = guard(async f => {
      if (!confirm('Delete ' + f.scope + ' flow "' + f.name + '"? Runs in progress are not affected.')) return;
      await wf.remove(f.scope, f.name);
      await load();
    });
    const answer = guard(async value => {
      await wf.answer(open.value.run.runId, value);
      await load();
    });
    const cancel = guard(async () => {
      await wf.cancel(open.value.run.runId);
      await load();
    });
    const canEdit = f => f.scope === 'user' || isAdmin.value;
    const canCancel = run => run && !TERMINAL.includes(String(run.status).toUpperCase());
    const pretty = v => (v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v));
    return {tab, flows, runs, busy, open, edit, starting, isAdmin, load, show, beginStart, doStart, copyFlow,
      beginEdit, beginNew, saveFlow, removeFlow, answer, cancel, canEdit, canCancel, pretty};
  },
  template: `
  <section class="wf" aria-label="Workflows">
    <div class="tabs" role="tablist">
      <button class="tab" role="tab" :class="{active: tab === 'runs'}" :aria-selected="tab === 'runs'"
              @click="tab = 'runs'">Runs</button>
      <button class="tab" role="tab" :class="{active: tab === 'flows'}" :aria-selected="tab === 'flows'"
              @click="tab = 'flows'">Flows</button>
    </div>

    <div v-if="tab === 'flows'" class="wf-flows">
      <div class="row-actions">
        <button class="btn-sm" @click="beginNew('user')">New flow</button>
        <button v-if="isAdmin" class="btn-sm" @click="beginNew('global')">New global flow</button>
      </div>
      <p v-if="!flows.length" class="empty">No flows yet.</p>
      <table v-else class="wf-table">
        <thead><tr><th>Name</th><th>Scope</th><th></th></tr></thead>
        <tbody>
          <tr v-for="f in flows" :key="f.scope + '/' + f.name">
            <td>{{ f.name }}</td>
            <td><span class="wf-scope" :class="f.scope">{{ f.scope === 'global' ? 'Global' : 'Personal' }}</span></td>
            <td class="wf-actions">
              <button class="btn-sm" :disabled="busy" @click="beginStart(f)">Start</button>
              <button class="btn-sm" :disabled="busy" @click="beginEdit(f)">{{ canEdit(f) ? 'Edit' : 'View' }}</button>
              <button v-if="f.scope === 'global'" class="btn-sm" :disabled="busy" @click="copyFlow(f)">Copy to mine</button>
              <button v-if="canEdit(f)" class="btn-sm" :disabled="busy" @click="removeFlow(f)">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>

      <form v-if="starting.flow" class="card wf-start" @submit.prevent="doStart">
        <h4>Start {{ starting.flow.name }}</h4>
        <template v-for="i in starting.inputs" :key="i">
          <label :for="'wf-in-' + i">{{ i }}</label>
          <input :id="'wf-in-' + i" type="text" v-model="starting.values[i]" />
        </template>
        <p v-if="!starting.inputs.length">This flow takes no inputs.</p>
        <div class="row-actions">
          <button class="btn-primary" type="submit" :disabled="busy">Start</button>
          <button class="btn-sm" type="button" @click="starting.flow = null">Cancel</button>
        </div>
      </form>

      <form v-if="edit.open" class="card wf-edit" @submit.prevent="saveFlow">
        <h4>{{ edit.scope === 'global' ? 'Global flow' : 'Personal flow' }}</h4>
        <label for="wf-edit-text">Definition (JSON)</label>
        <textarea id="wf-edit-text" v-model="edit.text" rows="18" spellcheck="false"></textarea>
        <div class="row-actions">
          <button class="btn-primary" type="submit" :disabled="busy">Save</button>
          <button class="btn-sm" type="button" @click="edit.open = false">Close</button>
        </div>
      </form>
    </div>

    <div v-if="tab === 'runs'" class="wf-runs">
      <p v-if="!runs.length" class="empty">No runs yet. Start one from the Flows tab.</p>
      <table v-else class="wf-table">
        <thead><tr><th>Flow</th><th>Status</th><th>Run</th></tr></thead>
        <tbody>
          <tr v-for="r in runs" :key="r.runId" :class="{current: open && open.run.runId === r.runId}">
            <td><button class="btn-link" @click="show(r.runId)">{{ r.flow }}</button></td>
            <td><span class="wf-status" :class="r.status.toLowerCase()">{{ r.status }}</span></td>
            <td><code :title="r.runId">{{ r.runId.slice(0, 8) }}</code></td>
          </tr>
        </tbody>
      </table>

      <div v-if="open" class="card wf-run">
        <h4>{{ open.run.flow }} <span class="wf-status" :class="open.run.status.toLowerCase()">{{ open.run.status }}</span></h4>
        <QuestionForm v-if="String(open.run.status).toUpperCase() === 'WAITING' && open.run.pending" :question="open.run.pending"
                      :busy="busy" @answer="answer" />
        <div v-if="open.run.result" class="wf-result"><b>Result</b> <code>{{ pretty(open.run.result) }}</code></div>
        <div v-if="open.run.error" class="banner banner-error">{{ pretty(open.run.error.message || open.run.error) }}</div>
        <h5>History</h5>
        <ol class="wf-history">
          <li v-for="(e, i) in open.history" :key="i">
            <b>{{ e.step || 'flow' }}</b> <span>{{ e.kind }}</span> <span>{{ e.detail }}</span>
          </li>
        </ol>
        <div class="row-actions">
          <button v-if="canCancel(open.run)" class="btn-sm" :disabled="busy" @click="cancel">Cancel run</button>
          <button class="btn-sm" @click="open = null">Close</button>
        </div>
      </div>
    </div>
  </section>
`,
};
