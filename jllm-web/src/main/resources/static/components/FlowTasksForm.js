import {computed, ref, watch} from '../vendor/vue.esm-browser.prod.js';
import {setOpt} from '../flowModel.js';
import KeyValueTable from './KeyValueTable.js';

const TYPES = ['string', 'number', 'boolean', 'array', 'object'];

/**
 * The `tasks` map of a flow: a prompt with slots, the agent that runs it and the shape the answer must have. Answer
 * fields are written as `name: type`; a name ending in `?` is optional.
 */
export default {
  components: {KeyValueTable},
  props: {
    doc: {type: Object, required: true},
    catalog: {type: Object, default: () => ({actions: []})},
    readOnly: {type: Boolean, default: false},
    problems: {type: Array, default: () => []},
    slots: {type: Array, default: () => []},
  },
  setup(props) {
    const current = ref('');
    const names = computed(() => Object.keys(props.doc.tasks || {}));
    watch(names, n => { if (!n.includes(current.value)) current.value = n[0] || ''; }, {immediate: true});
    const task = computed(() => (props.doc.tasks || {})[current.value]);
    const usedBy = name => Object.entries(props.doc.steps || {}).filter(([, s]) => s.type === 'task' && s.task === name).map(e => e[0]);

    function add() {
      const name = (prompt('Name of the new task', 'task') || '').trim();
      if (!name) return;
      if (!props.doc.tasks) props.doc.tasks = {};
      if (props.doc.tasks[name]) { current.value = name; return; }
      props.doc.tasks[name] = {agent: '', tools: '*', prompt: '', answer: 'text'};
      current.value = name;
    }
    function rename() {
      const from = current.value;
      const to = (prompt('New name of the task', from) || '').trim();
      if (!to || to === from || props.doc.tasks[to]) return;
      const t = {};
      for (const [k, v] of Object.entries(props.doc.tasks)) t[k === from ? to : k] = v;
      props.doc.tasks = t;
      for (const s of Object.values(props.doc.steps || {})) if (s.type === 'task' && s.task === from) s.task = to;
      current.value = to;
    }
    function remove() {
      const u = usedBy(current.value);
      if (!confirm('Delete the task ' + current.value + (u.length ? '? It is used by: ' + u.join(', ') : '?'))) return;
      delete props.doc.tasks[current.value];
    }
    // "*" = every tool of the role that runs the flow; a comma list names tools; empty = the agent's own tools
    const toolsText = computed(() => { const t = task.value && task.value.tools; return Array.isArray(t) ? t.join(', ') : t || ''; });
    const setTools = v => {
      const t = v.trim();
      if (!t) delete task.value.tools;
      else if (t === '*') task.value.tools = '*';
      else task.value.tools = t.split(',').map(x => x.trim()).filter(Boolean);
    };
    const isText = computed(() => task.value && (task.value.answer === undefined || task.value.answer === 'text'));
    const setShape = mode => { task.value.answer = mode === 'text' ? 'text' : {field: 'string'}; };
    const fields = computed(() => (isText.value ? {} : task.value.answer));
    const setFields = o => { task.value.answer = Object.keys(o).length ? o : {field: 'string'}; };
    const appendSlot = slot => { if (slot) task.value.prompt = (task.value.prompt || '') + slot; };
    return {toolsText, setTools, current, names, task, usedBy, add, rename, remove, isText, setShape, fields, setFields, appendSlot, setOpt,
      TYPES, actions: computed(() => props.catalog.actions)};
  },
  template: `
  <div class="tf">
    <aside class="tf-list">
      <button v-for="n in names" :key="n" type="button" class="role-item" :class="{active: n === current}" @click="current = n">
        <code>{{ n }}</code></button>
      <p v-if="!names.length" class="empty">No tasks yet.</p>
      <button v-if="!readOnly" type="button" class="btn-sm" @click="add">+ New task</button>
    </aside>
    <div class="tf-main">
      <ul v-if="problems.length" class="sf-problems"><li v-for="p in problems" :key="p" class="err">{{ p }}</li></ul>
      <template v-if="task">
        <div class="row-actions">
          <strong>{{ current }}</strong>
          <small class="muted">used by: {{ usedBy(current).join(', ') || 'no step' }}</small>
          <button v-if="!readOnly" type="button" class="btn-sm" @click="rename">Rename</button>
          <button v-if="!readOnly" type="button" class="btn-danger" @click="remove">Delete</button>
        </div>
        <div class="sf-row">
          <div class="field"><label for="tf-agent">Agent</label>
            <input id="tf-agent" type="text" :value="task.agent || ''" :readonly="readOnly" @change="setOpt(task, 'agent', $event.target.value.trim())" /></div>
          <div class="field"><label class="tp-check"><input type="checkbox" :checked="task.clarify !== false" :disabled="readOnly"
              @change="task.clarify = $event.target.checked ? undefined : false" />
            Check the reply for questions (the model proceeds on its own, or the owner is asked)</label></div>
          <div class="field"><label for="tf-tools">Tools</label>
            <input id="tf-tools" type="text" :value="toolsText" :readonly="readOnly" placeholder="* = all tools of the role; or read_file, write_file, …"
                   @change="setTools($event.target.value)" /></div>
          <div class="field"><label for="tf-system">System prompt</label>
            <input id="tf-system" type="text" :value="task.system || ''" :readonly="readOnly" @change="setOpt(task, 'system', $event.target.value)" /></div>
        </div>
        <div class="field"><label for="tf-prompt">Prompt</label>
          <textarea id="tf-prompt" rows="5" v-model="task.prompt" :readonly="readOnly"></textarea>
          <select v-if="!readOnly" class="sf-slot" aria-label="Insert slot" @change="appendSlot($event.target.value); $event.target.value = ''">
            <option value="">insert slot…</option><option v-for="s in slots" :key="s" :value="s">{{ s }}</option></select></div>
        <div class="field"><label for="tf-shape">Answer</label>
          <select id="tf-shape" :value="isText ? 'text' : 'fields'" :disabled="readOnly" @change="setShape($event.target.value)">
            <option value="text">free text</option><option value="fields">JSON object with fields</option></select></div>
        <div v-if="!isText" class="field"><label>Fields (name → type; end the name with ? for optional)</label>
          <KeyValueTable :model-value="fields" key-label="Field" value-label="Type" :value-options="TYPES" new-key="field"
                         :read-only="readOnly" @update:model-value="setFields" /></div>
        <div class="sf-row">
          <div v-for="role in ['before', 'after', 'instead']" :key="role" class="field">
            <label :for="'tf-' + role">{{ role === 'instead' ? 'Instead of the model (action)' : role === 'before' ? 'Before (action)' : 'After (action)' }}</label>
            <select :id="'tf-' + role" :value="task[role] || ''" :disabled="readOnly" @change="setOpt(task, role, $event.target.value)">
              <option value="">—</option>
              <option v-if="task[role] && !actions.some(a => a.name === task[role])" :value="task[role]">{{ task[role] }} (unknown)</option>
              <option v-for="a in actions" :key="a.name" :value="a.name">{{ a.name }}{{ a.asks ? ' (asks)' : '' }}</option>
            </select></div>
        </div>
      </template>
      <p v-else class="empty">Select or create a task.</p>
    </div>
  </div>
`,
};
