import {computed} from '../vendor/vue.esm-browser.prod.js';
import {setNum, setOpt} from '../flowModel.js';
import StepPicker from './StepPicker.js';
import AcceptsEditor from './AcceptsEditor.js';

/**
 * Flow level settings: name, scope (fixed once saved), start step, limits, unattended, helper, who a global flow is
 * shared with and the customer requests accepted everywhere.
 */
export default {
  components: {StepPicker, AcceptsEditor},
  props: {
    doc: {type: Object, required: true},
    isNew: {type: Boolean, default: false},
    roles: {type: Array, default: () => []},          // role names an administrator can share a global flow with
    catalog: {type: Object, default: () => ({actions: []})},
    readOnly: {type: Boolean, default: false},
    canShare: {type: Boolean, default: false},
    problems: {type: Array, default: () => []},
  },
  setup(props) {
    const global = computed(() => props.doc.scope === 'global');
    const limit = k => (props.doc.limits || {})[k] ?? '';
    const setLimit = (k, e) => {
      const l = {...(props.doc.limits || {})};
      setNum(l, k, e.target.value.trim());
      if (Object.keys(l).length) props.doc.limits = l; else delete props.doc.limits;
    };
    const shared = computed(() => props.doc.roles || []);
    const toggleRole = (r, on) => {
      const set = new Set(shared.value);
      if (on) set.add(r); else set.delete(r);
      if (set.size) props.doc.roles = [...set]; else delete props.doc.roles;
    };
    const unknownRoles = computed(() => shared.value.filter(r => !props.roles.includes(r)));
    return {global, limit, setLimit, shared, toggleRole, unknownRoles, setOpt};
  },
  template: `
  <div class="sf">
    <ul v-if="problems.length" class="sf-problems"><li v-for="p in problems" :key="p" class="err">{{ p }}</li></ul>
    <div class="sf-row">
      <div class="field"><label for="fs-name">Name</label>
        <input id="fs-name" type="text" v-model.trim="doc.name" :readonly="readOnly || !isNew" pattern="[A-Za-z0-9_.-]+"
               :title="isNew ? '' : 'A saved flow keeps its name; use Copy to make another'" /></div>
      <div class="field"><label>Scope</label>
        <input type="text" :value="global ? 'global (everybody it is shared with)' : 'personal (only you)'" readonly /></div>
      <div class="field"><label>Start step</label>
        <StepPicker v-model="doc.start" :steps="doc.steps || {}" :read-only="readOnly" label="Start step" /></div>
    </div>

    <fieldset v-if="global" class="fs-roles">
      <legend>Shared with</legend>
      <p class="muted small">Nothing ticked: everybody. Administrators always see the flow.</p>
      <label v-for="r in roles" :key="r" class="checkbox-row"><input type="checkbox" :checked="shared.includes(r)"
             :disabled="readOnly || !canShare" @change="toggleRole(r, $event.target.checked)" /><span>{{ r }}</span></label>
      <label v-for="r in unknownRoles" :key="'u' + r" class="checkbox-row"><input type="checkbox" checked
             :disabled="readOnly || !canShare" @change="toggleRole(r, $event.target.checked)" /><span>{{ r }} (no such role)</span></label>
      <p v-if="!roles.length && !shared.length" class="muted small">No roles to choose from.</p>
    </fieldset>

    <div class="sf-row">
      <div v-for="k in ['retries', 'questions', 'depth']" :key="k" class="field">
        <label :for="'fs-' + k">Limit: {{ k }}</label>
        <input :id="'fs-' + k" type="number" min="0" :value="limit(k)" :readonly="readOnly" placeholder="default"
               @change="setLimit(k, $event)" /></div>
      <label class="checkbox-row"><input type="checkbox" :checked="!!doc.unattended" :disabled="readOnly"
             @change="setOpt(doc, 'unattended', $event.target.checked ? true : '')" />
        <span>unattended (nobody can answer questions)</span></label>
    </div>
    <div class="field"><label for="fs-helper">Helper (answers refused requests)</label>
      <select id="fs-helper" :value="doc.helper || ''" :disabled="readOnly" @change="setOpt(doc, 'helper', $event.target.value)">
        <option value="">—</option>
        <option v-if="doc.helper && !catalog.actions.some(a => a.name === doc.helper)" :value="doc.helper">{{ doc.helper }} (unknown)</option>
        <option v-for="a in catalog.actions" :key="a.name" :value="a.name">{{ a.name }}</option>
      </select></div>
    <div class="field"><label>Customer requests accepted at every stage</label>
      <AcceptsEditor :model-value="doc.accepts || null" :steps="doc.steps || {}" :read-only="readOnly"
                     @update:model-value="setOpt(doc, 'accepts', $event)" /></div>
  </div>
`,
};
