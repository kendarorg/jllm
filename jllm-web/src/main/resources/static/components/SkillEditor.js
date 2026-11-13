import {reactive, watch} from 'vue';

/**
 * Edits (or shows) one skill: name (new skills only), description, markdown body.
 * skill: {name, description, content, source} or null for a new one.
 */
export default {
  props: {
    skill: {type: Object, default: null},
    editable: {type: Boolean, default: true},
  },
  emits: ['save', 'cancel'],
  setup(props, {emit}) {
    const form = reactive({name: '', description: '', content: ''});
    watch(() => props.skill, s => {
      form.name = s ? s.name : '';
      form.description = s ? s.description || '' : '';
      form.content = s ? s.content || '' : '';
    }, {immediate: true});
    const save = () => emit('save', {...form, name: form.name.trim()});
    return {form, save, emit};
  },
  template: `
  <form class="skill-editor card" @submit.prevent="save">
    <label for="skill-name">Name</label>
    <input id="skill-name" type="text" v-model="form.name" :disabled="!!skill || !editable"
           pattern="[a-z0-9_-]{1,64}" placeholder="lowercase, digits, _ and -" required />
    <label for="skill-desc">Description (when to use it)</label>
    <input id="skill-desc" type="text" v-model="form.description" :disabled="!editable" />
    <label for="skill-body">Instructions (markdown)</label>
    <textarea id="skill-body" v-model="form.content" rows="14" :readonly="!editable"></textarea>
    <div class="row-actions">
      <button v-if="editable" class="btn-primary" type="submit" :disabled="!form.name">Save</button>
      <button class="btn-sm" type="button" @click="emit('cancel')">{{ editable ? 'Cancel' : 'Close' }}</button>
    </div>
  </form>
`,
};
