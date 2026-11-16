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
    const form = reactive({tools: [], readOnly: false, approval: 'ask', perms: [], dirty: false});
    watch(() => props.role, r => {
      form.tools = [...r.tools];
      form.readOnly = r.readOnly;
      form.approval = r.approval;
      form.perms = Object.entries(r.toolPermissions || {}).map(([pattern, decision]) => ({pattern, decision}));
      form.dirty = false;
    }, {immediate: true});
    const touch = () => { form.dirty = true; };
    const onTools = v => { form.tools = v; touch(); };
    const addPerm = () => { form.perms.push({pattern: '', decision: 'allow'}); touch(); };
    const removePerm = i => { form.perms.splice(i, 1); touch(); };
    const save = () => emit('save', {
      tools: form.tools, readOnly: form.readOnly, approval: form.approval,
      toolPermissions: Object.fromEntries(form.perms.filter(p => p.pattern.trim()).map(p => [p.pattern.trim(), p.decision])),
    });
    return {form, touch, onTools, addPerm, removePerm, save, emit};
  },
  template: `
  <div class="role-editor">
    <h3>Role <code>{{ role.name }}</code> <small class="muted">· {{ role.users }} user(s)</small></h3>
    <p v-if="role.fixed" class="banner banner-info">The admin role always has every tool; only its approval can be set.</p>
    <div class="form-row">
      <label class="tp-check"><input type="checkbox" v-model="form.readOnly" :disabled="role.fixed" @change="touch" />
        Read-only tools only</label>
      <div class="field-sm">
        <label for="role-approval">Approval</label>
        <select id="role-approval" v-model="form.approval" @change="touch">
          <option value="ask">ask (harness policy)</option>
          <option value="off">off (no prompts)</option>
        </select>
      </div>
    </div>
    <fieldset class="tool-permissions">
      <legend>Tool permissions</legend>
      <p class="muted">Per tool name pattern (<code>*</code> = any text). <b>allow</b>: runs with no question, also in
        unattended flows. <b>ask</b>: always asked. <b>deny</b>: never runs. Deny wins over ask over allow.</p>
      <div v-for="(p, i) in form.perms" :key="i" class="form-row">
        <input type="text" v-model.trim="p.pattern" placeholder="tool or pattern, e.g. mcp__docs__*"
               :aria-label="'Tool pattern ' + (i + 1)" @input="touch" />
        <select v-model="p.decision" :aria-label="'Permission of ' + p.pattern" @change="touch">
          <option value="allow">allow (unattended)</option>
          <option value="ask">ask</option>
          <option value="deny">deny</option>
        </select>
        <button type="button" class="btn-icon" :aria-label="'Remove ' + p.pattern" @click="removePerm(i)">✕</button>
      </div>
      <button type="button" class="btn-sm" @click="addPerm">+ Add</button>
    </fieldset>
    <ToolPicker :catalog="catalog" :model-value="form.tools" mode="grant" :readonly="role.fixed" :permissions="form.perms"
                @update:model-value="onTools" />
    <div class="row-actions sticky-actions">
      <button class="btn-primary" :disabled="!form.dirty" @click="save">Save role</button>
      <button v-if="!role.fixed" class="btn-danger" :disabled="role.users > 0" :title="role.users > 0 ? 'Users still hold this role' : ''"
              @click="emit('delete')">Delete role</button>
    </div>
  </div>
`,
};
