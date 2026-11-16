import {computed} from 'vue';
import {newStep, partOf, preceding, setNum, setOpt, slotsFor, STEP_TYPES, TYPE_LABEL, uniqueId} from '../flowModel.js';
import {isWarning} from '../flowCheck.js';
import KeyValueTable from './KeyValueTable.js';
import StepPicker from './StepPicker.js';
import AcceptsEditor from './AcceptsEditor.js';

const WHO = ['owner', 'operator', 'customer'];

/**
 * The data of the selected step (bottom half of the designer). Edits the step object of the document in place; renaming
 * is delegated to the parent (`rename`) because it touches every reference.
 */
export default {
  components: {KeyValueTable, StepPicker, AcceptsEditor},
  props: {
    doc: {type: Object, required: true},
    which: {type: String, default: 'main'},
    id: {type: String, required: true},
    catalog: {type: Object, default: () => ({actions: [], choosers: []})},
    callable: {type: Array, default: () => []},    // [{scope, name}] flows this flow may call
    readOnly: {type: Boolean, default: false},
    problems: {type: Array, default: () => []},
  },
  emits: ['rename'],
  setup(props, {emit}) {
    const part = computed(() => partOf(props.doc, props.which));
    const step = computed(() => part.value.steps[props.id]);
    const steps = computed(() => part.value.steps);
    const mainSteps = computed(() => props.doc.steps || {});
    const slots = computed(() => slotsFor(props.doc, props.which, props.id));
    const global = computed(() => props.doc.scope === 'global');
    const taskNames = computed(() => Object.keys(props.doc.tasks || {}));

    function changeType(t) {
      const old = step.value;
      const ns = newStep(t, props.which === 'handler');
      if ('next' in ns && old.next) ns.next = old.next;
      part.value.steps[props.id] = ns;
    }
    function createFor(setter) {
      const t = (prompt('Type of the new step (' + STEP_TYPES.join(', ') + ')', 'task') || '').trim();
      if (!STEP_TYPES.includes(t)) return;
      const id = uniqueId(part.value.steps, t);
      part.value.steps[id] = newStep(t, props.which === 'handler');
      setter(id);
    }
    const appendSlot = (field, slot) => {
      if (!slot) return;
      step.value[field] = (step.value[field] || '') + slot;
    };
    // a global flow runs for every role it is shared with, so an action limited to other roles breaks it
    const actionWarn = a => {
      if (!global.value || !a.roles.length) return '';
      const shared = props.doc.roles || [];
      if (!shared.length) return 'limited to ' + a.roles.join(', ') + ' but the flow is for everybody';
      return shared.every(r => a.roles.includes(r)) ? '' : 'limited to ' + a.roles.join(', ');
    };
    const setRequired = on => { if (on) delete step.value.required; else step.value.required = false; };
    const setCScope = v => { if (v === 'global') step.value.scope = 'global'; else delete step.value.scope; };
    const actionLabel = a => a.name + (a.asks ? ' (asks)' : '') + (actionWarn(a) ? ' ⚠ ' + actionWarn(a) : '');
    const chooserLabel = c => c.name + (c.asks ? ' (asks)' : '');
    const callValue = computed(() => step.value.flow ? (step.value.scope || 'personal') + '/' + step.value.flow : '');
    const setCall = v => {
      const i = v.indexOf('/');
      if (i < 0) { delete step.value.flow; delete step.value.scope; return; }
      step.value.flow = v.slice(i + 1);
      if (v.slice(0, i) === 'global') step.value.scope = 'global'; else delete step.value.scope;
    };
    const callMode = computed(() => step.value.chooser ? 'chooser' : 'flow');
    const setCallMode = m => {
      if (m === 'chooser') { delete step.value.flow; step.value.chooser = step.value.chooser || ''; step.value.allowed = step.value.allowed || []; }
      else { delete step.value.chooser; delete step.value.allowed; step.value.flow = step.value.flow || ''; }
    };
    const allowedText = computed(() => (step.value.allowed || []).join(', '));
    const setAllowed = t => { step.value.allowed = t.split(',').map(x => x.trim()).filter(Boolean); };

    // slots that point nowhere: unknown step ids, or steps that cannot have run before this one
    const slotWarnings = computed(() => {
      const out = [];
      const before = preceding(part.value, props.id);
      const text = JSON.stringify(step.value);
      for (const m of text.matchAll(/\{\{\s*([^}]*?)\s*\}\}/g)) {
        const p = m[1].split('.');
        if (p[0] === 'steps') {
          if (!steps.value[p[1]]) out.push('{{' + m[1] + '}}: no step ' + p[1]);
          else if (!before.has(p[1])) out.push('{{' + m[1] + '}}: step ' + p[1] + ' does not run before this step');
        } else if (p[0] === 'error') {
          if (props.which !== 'handler') out.push('{{' + m[1] + '}}: only available in the error handler');
        } else if (!['input', 'requests', 'user', 'runId'].includes(p[0])) out.push('{{' + m[1] + '}}: unknown slot');
      }
      return out;
    });
    const dis = computed(() => props.readOnly);
    const num = (k, e) => setNum(step.value, k, e.target.value.trim());
    return {part, step, steps, mainSteps, slots, taskNames, changeType, createFor, appendSlot, actionLabel, chooserLabel,
      callValue, setCall, setRequired, setCScope, callMode, setCallMode, allowedText, setAllowed, slotWarnings, dis, num, emit, setOpt, STEP_TYPES,
      TYPE_LABEL, WHO, isWarning, global};
  },
  template: `
  <div v-if="step" class="sf" :aria-label="'Step ' + id">
    <div class="sf-row">
      <div class="field"><label for="sf-id">Step id</label>
        <input id="sf-id" type="text" :value="id" :readonly="dis" pattern="[A-Za-z0-9_-]+"
               @change="emit('rename', $event.target.value.trim())" /></div>
      <div class="field"><label for="sf-type">Type</label>
        <select id="sf-type" :value="step.type" :disabled="dis" @change="changeType($event.target.value)">
          <option v-for="t in STEP_TYPES" :key="t" :value="t">{{ TYPE_LABEL[t] }}</option>
        </select></div>
    </div>

    <ul v-if="problems.length" class="sf-problems" role="status">
      <li v-for="p in problems" :key="p" :class="isWarning(p) ? 'warn' : 'err'">{{ p }}</li>
    </ul>
    <ul v-if="slotWarnings.length" class="sf-problems"><li v-for="w in slotWarnings" :key="w" class="warn">{{ w }}</li></ul>

    <datalist id="sf-slots"><option v-for="s in slots" :key="s" :value="s"></option></datalist>

    <template v-if="step.type === 'task'">
      <div class="sf-row">
        <div class="field"><label for="sf-task">Task</label>
          <select id="sf-task" v-model="step.task" :disabled="dis">
            <option value="">—</option>
            <option v-if="step.task && !taskNames.includes(step.task)" :value="step.task">{{ step.task }} (missing)</option>
            <option v-for="t in taskNames" :key="t" :value="t">{{ t }}</option>
          </select><small class="muted">Tasks are written in the Tasks tab.</small></div>
        <div class="field"><label>Next</label>
          <StepPicker v-model="step.next" :steps="steps" :exclude="id" :read-only="dis" label="Next step"
                      @create="createFor(v => step.next = v)" /></div>
      </div>
    </template>

    <template v-if="step.type === 'action'">
      <div class="sf-row">
        <div class="field"><label for="sf-action">Action</label>
          <select id="sf-action" v-model="step.action" :disabled="dis">
            <option value="">—</option>
            <option v-if="step.action && !catalog.actions.some(a => a.name === step.action)" :value="step.action">{{ step.action }} (unknown)</option>
            <option v-for="a in catalog.actions" :key="a.name" :value="a.name">{{ actionLabel(a) }}</option>
          </select></div>
        <div class="field"><label>Next</label>
          <StepPicker v-model="step.next" :steps="steps" :exclude="id" :read-only="dis" label="Next step"
                      @create="createFor(v => step.next = v)" /></div>
      </div>
    </template>

    <template v-if="step.type === 'decide'">
      <div class="sf-row">
        <div class="field"><label for="sf-by">Decided by</label>
          <select id="sf-by" v-model="step.by" :disabled="dis"><option>java</option><option>llm</option><option>user</option></select></div>
        <div v-if="step.by === 'java'" class="field"><label for="sf-chooser">Chooser</label>
          <select id="sf-chooser" v-model="step.chooser" :disabled="dis">
            <option value="">—</option>
            <option v-if="step.chooser && !catalog.choosers.some(c => c.name === step.chooser)" :value="step.chooser">{{ step.chooser }} (unknown)</option>
            <option v-for="c in catalog.choosers" :key="c.name" :value="c.name">{{ chooserLabel(c) }}</option>
          </select></div>
        <div v-if="step.by === 'llm'" class="field"><label for="sf-agent">Agent</label>
          <input id="sf-agent" type="text" v-model="step.agent" :readonly="dis" /></div>
        <div v-if="step.by === 'user'" class="field"><label for="sf-who">Who decides</label>
          <input id="sf-who" type="text" :value="step.who || ''" :readonly="dis" list="sf-who-list"
                 @change="setOpt(step, 'who', $event.target.value.trim())" />
          <datalist id="sf-who-list"><option v-for="w in WHO" :key="w" :value="w"></option></datalist></div>
      </div>
      <div class="field"><label for="sf-q">Question</label>
        <input id="sf-q" type="text" :value="step.question || ''" :readonly="dis" @change="setOpt(step, 'question', $event.target.value)" /></div>
      <div v-if="step.by === 'llm'" class="field"><label for="sf-prompt">Prompt</label>
        <textarea id="sf-prompt" rows="3" v-model="step.prompt" :readonly="dis"></textarea>
        <select v-if="!dis" class="sf-slot" aria-label="Insert slot" @change="appendSlot('prompt', $event.target.value); $event.target.value = ''">
          <option value="">insert slot…</option><option v-for="s in slots" :key="s" :value="s">{{ s }}</option></select></div>
      <div class="field"><label>Outcomes (outcome → step)</label>
        <KeyValueTable :model-value="step.outcomes || {}" key-label="Outcome" value-label="Leads to" :value-options="Object.keys(steps)"
                       new-key="outcome" :read-only="dis" @update:model-value="step.outcomes = $event" /></div>
      <div class="field"><label>Default</label>
        <StepPicker :model-value="step.default || ''" :steps="steps" allow-empty :read-only="dis" label="Default step"
                    @update:model-value="setOpt(step, 'default', $event)" @create="createFor(v => step.default = v)" /></div>
    </template>

    <template v-if="step.type === 'ask'">
      <div class="sf-row">
        <div class="field"><label for="sf-kind">Kind</label>
          <select id="sf-kind" v-model="step.kind" :disabled="dis"><option>text</option><option>single</option><option>multi</option></select></div>
        <div class="field"><label for="sf-ask-who">Who</label>
          <input id="sf-ask-who" type="text" :value="step.who || ''" :readonly="dis" list="sf-who-list"
                 @change="setOpt(step, 'who', $event.target.value.trim())" /></div>
        <div class="field"><label>Next</label>
          <StepPicker v-model="step.next" :steps="steps" :exclude="id" :read-only="dis" label="Next step"
                      @create="createFor(v => step.next = v)" /></div>
      </div>
      <div class="field"><label for="sf-text">Question text</label>
        <textarea id="sf-text" rows="3" v-model="step.text" :readonly="dis"></textarea>
        <select v-if="!dis" class="sf-slot" aria-label="Insert slot" @change="appendSlot('text', $event.target.value); $event.target.value = ''">
          <option value="">insert slot…</option><option v-for="s in slots" :key="s" :value="s">{{ s }}</option></select></div>
      <div v-if="step.kind && step.kind !== 'text'" class="field"><label>Options (value → label)</label>
        <KeyValueTable :model-value="step.options || {}" key-label="Value" value-label="Label" new-key="option"
                       :read-only="dis" @update:model-value="step.options = $event" /></div>
      <div class="sf-row">
        <div v-if="step.kind === 'multi'" class="field"><label for="sf-min">Min picks</label>
          <input id="sf-min" type="number" min="0" :value="step.min ?? ''" :readonly="dis" @change="num('min', $event)" /></div>
        <div v-if="step.kind === 'multi'" class="field"><label for="sf-max">Max picks</label>
          <input id="sf-max" type="number" min="0" :value="step.max ?? ''" :readonly="dis" @change="num('max', $event)" /></div>
        <div v-if="step.kind === 'text'" class="field"><label for="sf-pattern">Pattern (regular expression)</label>
          <input id="sf-pattern" type="text" :value="step.pattern || ''" :readonly="dis" @change="setOpt(step, 'pattern', $event.target.value)" /></div>
        <label class="checkbox-row"><input type="checkbox" :checked="step.required !== false" :disabled="dis"
               @change="setRequired($event.target.checked)" />
          <span>required</span></label>
      </div>
      <div class="field"><label>Facts shown with the question</label>
        <KeyValueTable :model-value="step.facts || {}" key-label="Label" value-label="Value" new-key="fact" :read-only="dis"
                       :suggestions="slots" @update:model-value="setOpt(step, 'facts', Object.keys($event).length ? $event : '')" /></div>
      <div class="field"><label>On timeout go to (needs a wait limit in advanced)</label>
        <StepPicker :model-value="step.onTimeout || ''" :steps="steps" allow-empty :read-only="dis" label="Timeout step"
                    @update:model-value="setOpt(step, 'onTimeout', $event)" @create="createFor(v => step.onTimeout = v)" /></div>
    </template>

    <template v-if="step.type === 'call'">
      <div class="sf-row">
        <div class="field"><label for="sf-callmode">Calls</label>
          <select id="sf-callmode" :value="callMode" :disabled="dis" @change="setCallMode($event.target.value)">
            <option value="flow">a named flow</option><option value="chooser">a flow chosen by Java</option></select></div>
        <div v-if="callMode === 'flow'" class="field"><label for="sf-flow">Flow</label>
          <select id="sf-flow" :value="callValue" :disabled="dis" @change="setCall($event.target.value)">
            <option value="">—</option>
            <option v-if="callValue && !callable.some(f => f.scope + '/' + f.name === callValue)" :value="callValue">{{ callValue }} (not available)</option>
            <option v-for="f in callable" :key="f.scope + '/' + f.name" :value="f.scope + '/' + f.name">{{ f.name }} ({{ f.scope }})</option>
          </select></div>
        <template v-else>
          <div class="field"><label for="sf-cchooser">Chooser</label>
            <select id="sf-cchooser" v-model="step.chooser" :disabled="dis">
              <option value="">—</option>
              <option v-for="c in catalog.choosers" :key="c.name" :value="c.name">{{ chooserLabel(c) }}</option>
            </select></div>
          <div class="field"><label for="sf-allowed">Allowed flows (comma separated)</label>
            <input id="sf-allowed" type="text" :value="allowedText" :readonly="dis" @change="setAllowed($event.target.value)" /></div>
          <div class="field"><label for="sf-cscope">Scope of those flows</label>
            <select id="sf-cscope" :value="step.scope || 'personal'" :disabled="dis"
                    @change="setCScope($event.target.value)">
              <option>personal</option><option>global</option></select></div>
        </template>
        <div class="field"><label>Next</label>
          <StepPicker v-model="step.next" :steps="steps" :exclude="id" :read-only="dis" label="Next step"
                      @create="createFor(v => step.next = v)" /></div>
      </div>
      <div class="field"><label>Input of the called flow (name → value)</label>
        <KeyValueTable :model-value="step.input || {}" key-label="Input" value-label="Value" new-key="input" :read-only="dis"
                       list-id="sf-slots" @update:model-value="setOpt(step, 'input', Object.keys($event).length ? $event : '')" /></div>
    </template>

    <template v-if="step.type === 'finish'">
      <div v-if="which === 'handler'" class="sf-row">
        <div class="field"><label for="sf-outcome">Outcome</label>
          <select id="sf-outcome" v-model="step.outcome" :disabled="dis">
            <option value="recovered">recovered</option><option value="retry">retry</option><option value="rethrow">rethrow</option></select></div>
        <div v-if="step.outcome === 'retry'" class="field"><label>Retry step (main flow)</label>
          <StepPicker :model-value="step.retryStep || ''" :steps="mainSteps" :read-only="dis" label="Retry step"
                      @update:model-value="step.retryStep = $event" /></div>
      </div>
      <div class="field"><label>Result (name → value)</label>
        <KeyValueTable :model-value="step.result || {}" key-label="Name" value-label="Value" new-key="result" :read-only="dis"
                       list-id="sf-slots" @update:model-value="setOpt(step, 'result', Object.keys($event).length ? $event : '')" /></div>
    </template>

    <details class="sf-adv">
      <summary>Advanced: retries, waiting, requests</summary>
      <div class="sf-row">
        <div class="field"><label for="sf-retries">Retries</label>
          <input id="sf-retries" type="number" min="0" :value="step.retries ?? ''" :readonly="dis" @change="num('retries', $event)" /></div>
        <div class="field"><label for="sf-retrywait">Retry wait (ms)</label>
          <input id="sf-retrywait" type="number" min="0" :value="step.retryWaitMs ?? ''" :readonly="dis" @change="num('retryWaitMs', $event)" /></div>
        <div class="field"><label for="sf-questions">Questions</label>
          <input id="sf-questions" type="number" min="0" :value="step.questions ?? ''" :readonly="dis" @change="num('questions', $event)" /></div>
        <div class="field"><label for="sf-waitms">Wait limit (ms)</label>
          <input id="sf-waitms" type="number" min="0" :value="step.waitMs ?? ''" :readonly="dis" @change="num('waitMs', $event)" /></div>
      </div>
      <div class="field"><label>Customer requests accepted at this step</label>
        <AcceptsEditor :model-value="step.accepts || null" :steps="steps" :read-only="dis"
                       @update:model-value="setOpt(step, 'accepts', $event)" /></div>
    </details>
  </div>
  <p v-else class="empty">Select a step in the graph or the outline.</p>
`,
};
