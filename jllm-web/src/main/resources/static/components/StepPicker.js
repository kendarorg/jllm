import {computed} from '../vendor/vue.esm-browser.prod.js';

/** Picks a step id of `steps`; "new step…" asks the parent to create one (emits `create`). */
export default {
  props: {
    modelValue: {type: String, default: ''},
    steps: {type: Object, required: true},
    allowEmpty: {type: Boolean, default: false},
    readOnly: {type: Boolean, default: false},
    label: {type: String, default: 'Step'},
    exclude: {type: String, default: ''},
  },
  emits: ['update:modelValue', 'create'],
  setup(props, {emit}) {
    const ids = computed(() => Object.keys(props.steps).filter(i => i !== props.exclude));
    const missing = computed(() => props.modelValue && !props.steps[props.modelValue]);
    function change(e) {
      const v = e.target.value;
      if (v === '__new__') { e.target.value = props.modelValue; emit('create'); return; }
      emit('update:modelValue', v);
    }
    return {ids, missing, change};
  },
  template: `
  <select :value="modelValue" :disabled="readOnly" :aria-label="label" @change="change">
    <option v-if="allowEmpty || !modelValue" value="">—</option>
    <option v-if="missing" :value="modelValue">{{ modelValue }} (missing)</option>
    <option v-for="i in ids" :key="i" :value="i">{{ i }}</option>
    <option v-if="!readOnly" value="__new__">+ new step…</option>
  </select>
`,
};
