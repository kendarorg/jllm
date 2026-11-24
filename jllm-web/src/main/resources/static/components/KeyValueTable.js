import {computed} from '../vendor/vue.esm-browser.prod.js';

/**
 * Edits a map of text to text as rows. `valueOptions` (array of strings, or of {value,label}) turns the value column
 * into a select (outcome -> step, answer field -> type). Emits `update:modelValue` with a new object; edits apply when
 * a field loses focus so typing is never interrupted.
 */
export default {
  props: {
    modelValue: {type: Object, default: () => ({})},
    keyLabel: {type: String, default: 'Name'},
    valueLabel: {type: String, default: 'Value'},
    valueOptions: {type: Array, default: null},
    suggestions: {type: Array, default: () => []},
    readOnly: {type: Boolean, default: false},
    newKey: {type: String, default: 'item'},
    listId: {type: String, default: ''},
  },
  emits: ['update:modelValue'],
  setup(props, {emit}) {
    const rows = computed(() => Object.entries(props.modelValue || {}).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)]));
    const opts = computed(() => (props.valueOptions || []).map(o => typeof o === 'string' ? {value: o, label: o} : o));
    const rebuild = list => emit('update:modelValue', Object.fromEntries(list));
    const setKey = (i, k) => {
      const l = rows.value.map(r => [...r]);
      l[i][0] = k.trim();
      rebuild(l.filter(r => r[0]));
    };
    const setValue = (i, v) => {
      const l = rows.value.map(r => [...r]);
      l[i][1] = v;
      rebuild(l);
    };
    const remove = i => rebuild(rows.value.filter((_, j) => j !== i));
    const add = () => {
      const have = new Set(rows.value.map(r => r[0]));
      let k = props.newKey;
      for (let n = 2; have.has(k); n++) k = props.newKey + n;
      rebuild([...rows.value, [k, opts.value.length ? opts.value[0].value : '']]);
    };
    const known = v => opts.value.some(o => o.value === v);
    return {rows, opts, setKey, setValue, remove, add, known};
  },
  template: `
  <div class="kv">
    <table v-if="rows.length" class="kv-table">
      <thead><tr><th>{{ keyLabel }}</th><th>{{ valueLabel }}</th><th></th></tr></thead>
      <tbody>
        <tr v-for="(r, i) in rows" :key="i + ':' + r[0]">
          <td><input type="text" :value="r[0]" :readonly="readOnly" :aria-label="keyLabel + ' ' + (i + 1)"
                     @change="setKey(i, $event.target.value)" /></td>
          <td>
            <select v-if="opts.length" :value="r[1]" :disabled="readOnly" :aria-label="valueLabel + ' of ' + r[0]"
                    @change="setValue(i, $event.target.value)">
              <option v-if="!known(r[1])" :value="r[1]">{{ r[1] ? r[1] + ' (missing)' : '—' }}</option>
              <option v-for="o in opts" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
            <input v-else type="text" :value="r[1]" :readonly="readOnly" :list="listId || null"
                   :aria-label="valueLabel + ' of ' + r[0]" @change="setValue(i, $event.target.value)" />
          </td>
          <td><button v-if="!readOnly" type="button" class="btn-icon" :aria-label="'Remove ' + r[0]" @click="remove(i)">✕</button></td>
        </tr>
      </tbody>
    </table>
    <button v-if="!readOnly" type="button" class="btn-sm" @click="add">+ Add</button>
  </div>
`,
};
