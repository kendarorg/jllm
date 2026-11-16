import {computed, ref, watch} from 'vue';

/**
 * One open question of a run, shown by its kind: a text box, a radio list (single) or a checkbox list (multi).
 * Emits `answer` with a string (text, single) or an array of values (multi); the server validates it.
 */
export default {
  props: {question: {type: Object, required: true}, busy: {type: Boolean, default: false}},
  emits: ['answer'],
  setup(props, {emit}) {
    const text = ref('');
    const one = ref('');
    const many = ref([]);
    watch(() => props.question.key, () => { text.value = ''; one.value = ''; many.value = []; });
    const kind = computed(() => props.question.kind);
    const facts = computed(() => {
      const f = props.question.facts;
      return f && typeof f === 'object' ? Object.entries(f) : [];
    });
    const ready = computed(() => {
      if (!props.question.required) return true;
      if (kind.value === 'TEXT') return text.value.trim() !== '';
      if (kind.value === 'SINGLE') return one.value !== '';
      return many.value.length >= Math.max(props.question.min || 0, 1);
    });
    function submit() {
      emit('answer', kind.value === 'TEXT' ? text.value : kind.value === 'SINGLE' ? one.value : [...many.value]);
    }
    return {text, one, many, kind, facts, ready, submit};
  },
  template: `
  <form class="wf-question" @submit.prevent="submit">
    <h4>{{ question.title }}</h4>
    <p v-if="question.text && question.text !== question.title">{{ question.text }}</p>
    <p class="wf-who">Asked of: {{ question.who }}</p>
    <dl v-if="facts.length" class="wf-facts">
      <template v-for="f in facts" :key="f[0]"><dt>{{ f[0] }}</dt><dd>{{ f[1] }}</dd></template>
    </dl>
    <textarea v-if="kind === 'TEXT'" v-model="text" rows="3" :aria-label="question.title"></textarea>
    <fieldset v-else-if="kind === 'SINGLE'">
      <legend class="sr-only">{{ question.title }}</legend>
      <label v-for="o in question.options" :key="o.value" class="wf-option">
        <input type="radio" :name="question.key" :value="o.value" v-model="one" /> {{ o.label }}
      </label>
    </fieldset>
    <fieldset v-else>
      <legend class="sr-only">{{ question.title }}</legend>
      <label v-for="o in question.options" :key="o.value" class="wf-option">
        <input type="checkbox" :value="o.value" v-model="many" /> {{ o.label }}
      </label>
      <small v-if="question.max">Pick {{ question.min || 0 }} to {{ question.max }}.</small>
    </fieldset>
    <button class="btn-primary" type="submit" :disabled="busy || !ready">Answer</button>
  </form>
`,
};
