import {reactive, watch} from 'vue';
import ToolPicker from './ToolPicker.js';

/** Edits one role: read-only flag, approval, granted tools / MCP servers / REST APIs. The admin role is read-only. */
export default {
  components: {ToolPicker},
  props: {
    role: {type: Object, required: true},     // {name, tools, readOnly, approval, fixed, users}
    catalog: {type: Object, required: true},
  },
  emits: ['save', 'delete'],
  setup(props, {emit}) {
    const form = reactive({tools: [], readOnly: false, approval: 'ask', dirty: false});
    watch(() => props.role, r => {
      form.tools = [...r.tools];
      form.readOnly = r.readOnly;
      form.approval = r.approval;
      form.dirty = false;
    }, {immediate: true});
    const touch = () => { form.dirty = true; };
    const onTools = v => { form.tools = v; touch(); };
    const save = () => emit('save', {tools: form.tools, readOnly: form.readOnly, approval: form.approval});
    return {form, touch, onTools, save, emit};
  },
  template: `
  <div class="role-editor">
    <h3>Role <code>{{ role.name }}</code> <small class="muted">· {{ role.users }} user(s)</small></h3>
    <p v-if="role.fixed" class="banner banner-info">The admin role is fixed: every tool, no approval prompts.</p>
    <div class="form-row">
      <label class="tp-check"><input type="checkbox" v-model="form.readOnly" :disabled="role.fixed" @change="touch" />
        Read-only tools only</label>
      <div class="field-sm">
        <label for="role-approval">Approval</label>
        <select id="role-approval" v-model="form.approval" :disabled="role.fixed" @change="touch">
          <option value="ask">ask (harness policy)</option>
          <option value="off">off (no prompts)</option>
        </select>
      </div>
    </div>
    <ToolPicker :catalog="catalog" :model-value="form.tools" mode="grant" :readonly="role.fixed"
                @update:model-value="onTools" />
    <div v-if="!role.fixed" class="row-actions sticky-actions">
      <button class="btn-primary" :disabled="!form.dirty" @click="save">Save role</button>
      <button class="btn-danger" :disabled="role.users > 0" :title="role.users > 0 ? 'Users still hold this role' : ''"
              @click="emit('delete')">Delete role</button>
    </div>
  </div>
`,
};
