import {computed, onMounted, reactive, ref, watch} from '../vendor/vue.esm-browser.prod.js';
import {api} from '../api.js';
import {toast} from '../store.js';
import {wf} from '../workflows.js';
import {calledFlows, inputsOf, insertStep, newFlow, partOf, removeStep, renameStep, slotsFor} from '../flowModel.js';
import {checkFlow, emptyProblems, isWarning, mapServerErrors, mergeProblems} from '../flowCheck.js';
import FlowCanvas from './FlowCanvas.js';
import FlowStepForm from './FlowStepForm.js';
import FlowTasksForm from './FlowTasksForm.js';
import FlowSettingsForm from './FlowSettingsForm.js';

const TABS = [['main', 'Main flow'], ['handler', 'Error handler'], ['tasks', 'Tasks'], ['settings', 'Flow settings']];
const SPLIT_KEY = 'jllm.flows.top';
const copy = o => JSON.parse(JSON.stringify(o));

function readSplit() {
  try { const n = Number(localStorage.getItem(SPLIT_KEY)); if (n >= 160 && n <= 900) return n; } catch (e) { /* no storage */ }
  return 340;
}

/**
 * The Flows screen, for every user: personal and global flows, and a designer for one flow at a time: the structure of
 * the flow on top, the data of the selected step below. The flow document is edited in place and saved through the
 * jllm-workflow-web endpoints, after the server's own checks (`validate`). Global flows are editable by administrators
 * only; everybody else views them, starts them and copies them.
 */
export default {
  components: {FlowCanvas, FlowStepForm, FlowTasksForm, FlowSettingsForm},
  props: {me: {type: Object, required: true}},
  emits: ['started'],
  setup(props, {emit}) {
    const flows = ref([]);
    const filter = ref('all');
    const catalog = ref({actions: [], choosers: [], canManageGlobal: false});
    const roleNames = ref([]);
    const cur = ref(null);              // {scope, name, isNew, doc, saved}
    const tab = ref('main');
    const selected = reactive({main: '', handler: ''});
    const server = ref(emptyProblems());
    const jsonMode = ref(false);
    const jsonText = ref('');
    const topH = ref(readSplit());
    const busy = ref(false);
    const starting = reactive({open: false, inputs: [], values: {}});
    const fitKey = ref('');
    const runs = ref([]);               // runs of the signed-in user, for the overlay
    const overlay = reactive({run: '', trail: [], status: '', active: ''});

    const guard = fn => async (...a) => {
      busy.value = true;
      try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } finally { busy.value = false; }
    };
    const key = f => f.scope + '/' + f.name;
    const shown = computed(() => flows.value.filter(f => filter.value === 'all' || f.scope === filter.value));
    const docText = computed(() => cur.value ? JSON.stringify(cur.value.doc) : '');
    const dirty = computed(() => !!cur.value && docText.value !== cur.value.saved);
    const entry = computed(() => cur.value && !cur.value.isNew
      ? flows.value.find(f => f.scope === cur.value.scope && f.name === cur.value.name) : null);
    const readOnly = computed(() => {
      if (!cur.value) return true;
      if (cur.value.isNew) return cur.value.scope === 'global' && !catalog.value.canManageGlobal;
      return entry.value ? !entry.value.canEdit : cur.value.scope === 'global' && !catalog.value.canManageGlobal;
    });
    const isGlobal = computed(() => !!cur.value && cur.value.scope === 'global');

    const local = computed(() => cur.value ? checkFlow(cur.value.doc) : emptyProblems());
    const problems = computed(() => mergeProblems(local.value, server.value));
    watch(docText, () => { server.value = emptyProblems(); });

    const marks = computed(() => {
      const out = {};
      for (const [k, list] of Object.entries(problems.value.byStep)) {
        const i = k.indexOf('/');
        if (k.slice(0, i) !== tab.value) continue;
        out[k.slice(i + 1)] = list.some(m => !isWarning(m)) ? 'error' : 'warn';
      }
      return out;
    });
    const stepProblems = computed(() => problems.value.byStep[tab.value + '/' + selected[tab.value]] || []);
    const tabCount = t => {
      if (t === 'main' || t === 'handler') {
        return Object.entries(problems.value.byStep).filter(([k, v]) => k.startsWith(t + '/') && v.some(m => !isWarning(m))).length;
      }
      return t === 'tasks' ? problems.value.flow.filter(m => m.startsWith('task ')).length
        : problems.value.flow.filter(m => !m.startsWith('task ') && !/steps|finish|start step/.test(m)).length;
    };
    const flowProblems = computed(() => problems.value.flow.filter(m => !m.startsWith('task ')));
    const taskProblems = computed(() => problems.value.flow.filter(m => m.startsWith('task ')));
    const part = computed(() => cur.value && (tab.value === 'main' || tab.value === 'handler') ? partOf(cur.value.doc, tab.value) : null);
    const callable = computed(() => {
      const c = cur.value;
      if (!c) return [];
      return flows.value.filter(f => (c.scope !== 'global' || f.scope === 'global') && !(f.scope === c.scope && f.name === c.doc.name))
        .map(f => ({scope: f.scope, name: f.name}));
    });
    const slots = computed(() => cur.value ? slotsFor(cur.value.doc, 'main', '') : []);

    const loadList = async () => { flows.value = await wf.flows(); };
    const flowRuns = computed(() => cur.value ? runs.value.filter(r => r.flow === cur.value.doc.name) : []);
    const clearOverlay = () => Object.assign(overlay, {run: '', trail: [], status: '', active: ''});
    // the steps a run went through, from its recorded history (nothing is stored by looking at it)
    const showRun = guard(async id => {
      if (!id) { clearOverlay(); return; }
      const [run, hist] = await Promise.all([wf.run(id), wf.history(id)]);
      const trail = hist.map(e => e.step).filter(Boolean);
      Object.assign(overlay, {run: id, trail, status: run.status, active: String(run.status).toUpperCase() === 'WAITING' ? (trail[trail.length - 1] || '') : ''});
    });
    const loadRuns = async () => { try { runs.value = await wf.runs(); } catch (e) { runs.value = []; } };
    const load = guard(async () => {
      await loadList();
      try { catalog.value = await wf.catalog(); } catch (e) { /* editor still works with free text */ }
      if (props.me.role === 'admin') {
        try { roleNames.value = (await api('/api/admin/roles')).map(r => r.name); } catch (e) { /* none */ }
      }
    });
    onMounted(load);

    function setDoc(scope, name, isNew, doc) {
      doc.scope = scope;
      if (!doc.steps) doc.steps = {};
      partOf(doc, 'handler');
      cur.value = {scope, name, isNew, doc, saved: JSON.stringify(doc)};
      tab.value = 'main';
      selected.main = doc.start && doc.steps[doc.start] ? doc.start : '';
      selected.handler = '';
      clearOverlay();
      loadRuns();
      server.value = emptyProblems();
      jsonMode.value = false;
      starting.open = false;
      fitKey.value = scope + '/' + name + Date.now();
    }
    const confirmLeave = () => !dirty.value || confirm('Discard the changes of this flow?');

    const open = guard(async f => {
      if (!confirmLeave()) return;
      setDoc(f.scope, f.name, false, copy(await wf.flow(f.scope, f.name)));
    });
    function beginNew(scope) {
      if (!confirmLeave()) return;
      setDoc(scope, '', true, newFlow());
      tab.value = 'settings';
    }
    const validate = guard(async () => {
      const r = await wf.validate(cur.value.scope, copy(cur.value.doc));
      server.value = mapServerErrors(r.errors);
      toast(r.errors.length ? r.errors.length + ' problem(s) found' : 'The server finds no problem', r.errors.length ? 'error' : 'success');
      return r.errors.length;
    });
    const save = guard(async () => {
      const c = cur.value;
      if (readOnly.value) return;
      const r = await wf.validate(c.scope, copy(c.doc));
      server.value = mapServerErrors(r.errors);
      if (r.errors.length) { toast('Not saved: ' + r.errors.length + ' problem(s)', 'error'); return; }
      if (c.scope === 'global' && !confirm('Save the global flow "' + c.doc.name + '"? It changes for everybody it is shared with; '
          + 'runs in progress are not affected.')) return;
      await wf.save(c.scope, copy(c.doc));
      await loadList();
      setDoc(c.scope, c.doc.name, false, copy(await wf.flow(c.scope, c.doc.name)));
      toast('Saved', 'success');
    });
    const remove = guard(async () => {
      const c = cur.value;
      let msg = 'Delete ' + c.scope + ' flow "' + c.name + '"? Runs in progress are not affected.';
      if (c.scope === 'global') {
        const callers = [];
        for (const f of flows.value.filter(x => x.scope === 'global' && x.name !== c.name)) {
          try { if (calledFlows(await wf.flow('global', f.name)).has('global/' + c.name)) callers.push(f.name); } catch (e) { /* skip */ }
        }
        if (callers.length) msg += '\nCalled by the global flows: ' + callers.join(', ') + ' (they will fail).';
        msg += '\nIt disappears for everybody it is shared with.';
      }
      if (!confirm(msg)) return;
      await wf.remove(c.scope, c.name);
      cur.value = null;
      await loadList();
    });
    const copyFlow = guard(async () => {
      const c = cur.value;
      const name = prompt('Name of your copy', c.name);
      if (!name) return;
      const made = await wf.copy(c.name, name);
      await loadList();
      setDoc('personal', made.name || name, false, copy(await wf.flow('personal', made.name || name)));
      toast('Copied to your flows', 'success');
    });
    const beginStart = guard(async () => {
      if (dirty.value && !confirm('Start runs the saved version, not your unsaved changes. Continue?')) return;
      starting.inputs = await wf.inputs(cur.value.scope, cur.value.name);
      starting.values = Object.fromEntries(starting.inputs.map(i => [i, '']));
      starting.open = true;
    });
    const doStart = guard(async () => {
      const run = await wf.start(cur.value.name, cur.value.scope, {...starting.values});
      starting.open = false;
      emit('started', run);
    });

    // structure editing
    const select = id => { selected[tab.value] = id; };
    function onInsert(type) {
      if (readOnly.value) return;
      selected[tab.value] = insertStep(part.value, type, selected[tab.value], tab.value === 'handler');
    }
    function onRemove(id) {
      if (readOnly.value || !id) return;
      removeStep(part.value, id);
      if (selected[tab.value] === id) selected[tab.value] = '';
    }
    function onStart(id) { if (id && !readOnly.value) part.value.start = id; }
    function onRename(to) {
      const from = selected[tab.value];
      if (readOnly.value || !to || to === from) return;
      if (!/^[A-Za-z0-9_-]+$/.test(to)) { toast('A step id uses letters, digits, _ and -', 'error'); return; }
      if (!renameStep(cur.value.doc, tab.value, from, to)) { toast('A step called ' + to + ' exists already', 'error'); return; }
      selected[tab.value] = to;
    }

    // raw JSON escape hatch
    function toJson() { jsonText.value = JSON.stringify(cur.value.doc, null, 2); jsonMode.value = true; }
    function fromJson() {
      try {
        const d = JSON.parse(jsonText.value);
        if (!d || typeof d !== 'object' || Array.isArray(d)) throw new Error('A flow is a JSON object');
        const c = cur.value;
        d.scope = c.scope;
        if (!c.isNew) d.name = c.name;
        c.doc = d;
        if (!d.steps) d.steps = {};
        partOf(d, 'handler');
        jsonMode.value = false;
      } catch (e) { toast('JSON: ' + e.message, 'error'); }
    }

    // splitter between the structure and the step data
    function splitDown(e) {
      const y0 = e.clientY, h0 = topH.value;
      const move = ev => { topH.value = Math.min(900, Math.max(160, h0 + ev.clientY - y0)); };
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        try { localStorage.setItem(SPLIT_KEY, String(topH.value)); } catch (x) { /* no storage */ }
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
      e.preventDefault();
    }
    function splitKey(e) {
      const d = e.key === 'ArrowUp' ? -24 : e.key === 'ArrowDown' ? 24 : 0;
      if (!d) return;
      topH.value = Math.min(900, Math.max(160, topH.value + d));
      try { localStorage.setItem(SPLIT_KEY, String(topH.value)); } catch (x) { /* no storage */ }
      e.preventDefault();
    }
    function onTabKey(e) {
      const i = TABS.findIndex(t => t[0] === tab.value);
      const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
      if (n === null) return;
      tab.value = TABS[(n + TABS.length) % TABS.length][0];
      e.preventDefault();
    }
    const inputs = computed(() => cur.value ? inputsOf(cur.value.doc) : []);
    return {flows, shown, filter, catalog, roleNames, cur, tab, selected, jsonMode, jsonText, topH, busy, starting, TABS,
      dirty, entry, readOnly, isGlobal, problems, marks, stepProblems, tabCount, flowProblems, taskProblems, part, callable,
      slots, inputs, fitKey, flowRuns, overlay, showRun, loadRuns, key, open, beginNew, validate, save, remove, copyFlow, beginStart, doStart, select, onInsert,
      onRemove, onStart, onRename, toJson, fromJson, splitDown, splitKey, onTabKey, isAdmin: computed(() => catalog.value.canManageGlobal)};
  },
  template: `
  <section class="fl" aria-label="Flows">
    <aside class="fl-list">
      <div class="fl-filter" role="group" aria-label="Show">
        <button v-for="f in ['all', 'personal', 'global']" :key="f" type="button" class="btn-sm" :class="{active: filter === f}"
                :aria-pressed="filter === f" @click="filter = f">{{ f === 'all' ? 'All' : f === 'personal' ? 'Personal' : 'Global' }}</button>
      </div>
      <ul class="fl-items">
        <li v-for="f in shown" :key="key(f)">
          <button type="button" class="fl-item" :class="{active: cur && !cur.isNew && cur.scope === f.scope && cur.name === f.name}"
                  @click="open(f)">
            <span class="fl-name">{{ f.name }}</span>
            <span class="badge" :class="f.scope === 'global' ? 'global' : 'own'">{{ f.scope === 'global' ? 'Global' : 'Personal' }}</span>
            <small v-if="f.scope === 'global'" class="muted">{{ f.roles.length ? 'for ' + f.roles.join(', ') : 'everybody' }}</small>
            <small v-if="f.scope === 'global' && !f.sharedWithYou" class="badge warn" title="You see it because administrators see every flow">not shared with you</small>
          </button>
        </li>
        <li v-if="!shown.length" class="empty">No flows here.</li>
      </ul>
      <div class="row-actions">
        <button class="btn-primary" type="button" @click="beginNew('personal')">New flow</button>
        <button v-if="isAdmin" class="btn-sm" type="button" @click="beginNew('global')">New global flow</button>
      </div>
    </aside>

    <div class="fl-main">
      <p v-if="!cur" class="empty">Select a flow or create a new one.</p>
      <template v-else>
        <div class="fl-head">
          <h3><span class="fl-title">{{ cur.name || 'New flow' }}</span>
            <span class="badge" :class="isGlobal ? 'global' : 'own'">{{ isGlobal ? 'Global' : 'Personal' }}</span>
            <span v-if="dirty" class="fl-dirty" title="Unsaved changes" aria-label="Unsaved changes">●</span>
            <span v-if="readOnly" class="badge">read only</span></h3>
          <div class="row-actions">
            <button v-if="!cur.isNew" type="button" class="btn-sm" :disabled="busy" @click="beginStart">Start</button>
            <button v-if="isGlobal && !cur.isNew" type="button" class="btn-sm" :disabled="busy" @click="copyFlow">Copy to mine</button>
            <button v-if="!readOnly" type="button" class="btn-sm" :disabled="busy" @click="validate">Validate</button>
            <button v-if="!readOnly" type="button" class="btn-primary" :disabled="busy || !dirty && !cur.isNew" @click="save">Save</button>
            <button v-if="!readOnly && !cur.isNew" type="button" class="btn-danger" :disabled="busy" @click="remove">Delete</button>
            <button type="button" class="btn-sm" :class="{active: jsonMode}" :aria-pressed="jsonMode"
                    @click="jsonMode ? (jsonMode = false) : toJson()">JSON</button>
          </div>
        </div>

        <form v-if="starting.open" class="card fl-start" @submit.prevent="doStart">
          <div class="row-actions">
            <h4>Start {{ cur.name }}</h4>
            <button class="btn-primary" type="submit" :disabled="busy">Start</button>
            <button class="btn-sm" type="button" @click="starting.open = false">Cancel</button>
          </div>
          <template v-for="i in starting.inputs" :key="i">
            <label :for="'fl-in-' + i">{{ i }}</label>
            <input :id="'fl-in-' + i" type="text" v-model="starting.values[i]" />
          </template>
          <p v-if="!starting.inputs.length">This flow takes no inputs.</p>
        </form>

        <div v-if="jsonMode" class="fl-json">
          <div class="row-actions"><button v-if="!readOnly" type="button" class="btn-primary" @click="fromJson">Apply</button>
            <button type="button" class="btn-sm" @click="jsonMode = false">Back to the designer</button></div>
          <textarea v-model="jsonText" rows="24" spellcheck="false" :readonly="readOnly" aria-label="Flow definition (JSON)"></textarea>
        </div>

        <template v-else>
          <div class="tabs" role="tablist" aria-label="Flow parts" @keydown="onTabKey">
            <button v-for="t in TABS" :key="t[0]" role="tab" type="button" class="tab" :class="{active: tab === t[0]}"
                    :aria-selected="tab === t[0]" :tabindex="tab === t[0] ? 0 : -1" @click="tab = t[0]">
              {{ t[1] }}<span v-if="tabCount(t[0])" class="wf-badge" :aria-label="tabCount(t[0]) + ' problems'">{{ tabCount(t[0]) }}</span></button>
          </div>

          <div v-if="tab === 'main' || tab === 'handler'" class="fl-design" role="tabpanel">
            <ul v-if="flowProblems.length && tab === 'main'" class="sf-problems"><li v-for="p in flowProblems" :key="p" class="err">{{ p }}</li></ul>
            <p v-if="tab === 'handler'" class="muted small">What happens when a step fails for good. Finish steps need an outcome:
              recovered, retry (a main step) or rethrow.</p>
            <div v-if="tab === 'main' && !cur.isNew" class="fl-overlay">
              <label for="fl-run">Show a run on the graph</label>
              <select id="fl-run" :value="overlay.run" @focus="loadRuns" @change="showRun($event.target.value)">
                <option value="">none</option>
                <option v-for="r in flowRuns" :key="r.runId" :value="r.runId">{{ r.runId.slice(0, 8) }} · {{ r.status }}</option>
              </select>
              <small v-if="overlay.run" class="muted">Numbers show the order steps ran in; the run is {{ overlay.status }}.
                It ran the saved version of the flow, which may differ from what is shown.</small>
            </div>
            <div class="fl-top" :style="{height: topH + 'px'}">
              <FlowCanvas :part="part" :selected="selected[tab]" :marks="marks" :read-only="readOnly" :fit-key="fitKey + tab"
                          :trail="tab === 'main' ? overlay.trail : []" :active="tab === 'main' ? overlay.active : ''"
                          @select="select" @insert="onInsert" @remove="onRemove" @start="onStart" />
            </div>
            <div class="fl-split" role="separator" aria-orientation="horizontal" tabindex="0" aria-label="Resize the graph"
                 @pointerdown="splitDown" @keydown="splitKey"></div>
            <div class="fl-bottom">
              <FlowStepForm v-if="selected[tab] && part.steps[selected[tab]]" :doc="cur.doc" :which="tab" :id="selected[tab]"
                            :catalog="catalog" :callable="callable" :read-only="readOnly" :problems="stepProblems"
                            @rename="onRename" />
              <p v-else class="empty">Select a step in the graph or the outline.</p>
            </div>
          </div>

          <div v-else-if="tab === 'tasks'" role="tabpanel">
            <FlowTasksForm :doc="cur.doc" :catalog="catalog" :read-only="readOnly" :problems="taskProblems" :slots="slots" />
          </div>
          <div v-else role="tabpanel">
            <FlowSettingsForm :doc="cur.doc" :is-new="cur.isNew" :roles="roleNames" :catalog="catalog" :read-only="readOnly"
                              :can-share="isAdmin" :problems="flowProblems" />
          </div>
        </template>
      </template>
    </div>
  </section>
`,
};
