import KeyValueTable from './KeyValueTable.js';
import StepPicker from './StepPicker.js';

/**
 * Editor of an `accepts` map (customer request kinds): request kind -> {action: cancel | update | redirect, ...}.
 * `update` takes key, kind and required; `redirect` takes the step to jump to.
 */
export default {
  components: {StepPicker},
  props: {
    modelValue: {type: Object, default: null},
    steps: {type: Object, required: true},
    readOnly: {type: Boolean, default: false},
  },
  emits: ['update:modelValue'],
  setup(props, {emit}) {
    const entries = () => Object.entries(props.modelValue || {});
    const push = o => emit('update:modelValue', Object.keys(o).length ? o : undefined);
    const copy = () => JSON.parse(JSON.stringify(props.modelValue || {}));
    const add = () => {
      const o = copy();
      let k = 'request';
      for (let n = 2; k in o; n++) k = 'request' + n;
      o[k] = {action: 'cancel'};
      push(o);
    };
    const remove = k => { const o = copy(); delete o[k]; push(o); };
    const rename = (k, to) => {
      to = to.trim();
      if (!to || to === k) return;
      const o = {};
      for (const [a, b] of Object.entries(copy())) o[a === k ? to : a] = b;
      push(o);
    };
    const field = (k, f, v) => {
      const o = copy();
      if (v === '' || v === false) delete o[k][f]; else o[k][f] = v;
      push(o);
    };
    const setAction = (k, a) => {
      const o = copy();
      o[k] = {action: a};
      if (a === 'update') { o[k].key = k; o[k].kind = 'text'; }
      push(o);
    };
    return {entries, add, remove, rename, field, setAction};
  },
  template: `
  <div class="accepts">
    <div v-for="[k, a] in entries()" :key="k" class="accepts-row">
      <input type="text" :value="k" :readonly="readOnly" aria-label="Request kind" @change="rename(k, $event.target.value)" />
      <select :value="a.action" :disabled="readOnly" aria-label="Action" @change="setAction(k, $event.target.value)">
        <option value="cancel">cancel</option><option value="update">update</option><option value="redirect">redirect</option>
      </select>
      <template v-if="a.action === 'update'">
        <input type="text" :value="a.key" :readonly="readOnly" placeholder="key" aria-label="Key" @change="field(k, 'key', $event.target.value)" />
        <select :value="a.kind || 'text'" :disabled="readOnly" aria-label="Kind" @change="field(k, 'kind', $event.target.value)">
          <option>text</option><option>single</option><option>multi</option>
        </select>
        <label class="checkbox-row"><input type="checkbox" :checked="a.required" :disabled="readOnly"
               @change="field(k, 'required', $event.target.checked)" /><span>required</span></label>
      </template>
      <StepPicker v-if="a.action === 'redirect'" :model-value="a.to || ''" :steps="steps" :read-only="readOnly"
                  label="Redirect to" @update:model-value="field(k, 'to', $event)" />
      <button v-if="!readOnly" type="button" class="btn-icon" :aria-label="'Remove ' + k" @click="remove(k)">✕</button>
    </div>
    <button v-if="!readOnly" type="button" class="btn-sm" @click="add">+ Request kind</button>
  </div>
`,
};
