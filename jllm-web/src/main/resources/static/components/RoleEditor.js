import {reactive, watch} from '../vendor/vue.esm-browser.prod.js';
import ToolPicker from './ToolPicker.js';

/**
 * Edits one role: sandbox, read-only flag, approval, sign-in mapping (identity provider groups), granted tools / MCP
 * servers / REST APIs. The admin role's tools are fixed.
 */
const MODES = ['default', 'plan', 'edit', 'auto'];

export default {
  components: {ToolPicker},
  props: {
    role: {type: Object, required: true},     // {name, tools, readOnly, approval, sandbox, fixed, users}
    catalog: {type: Object, required: true},
    mappingEnabled: {type: Boolean, default: false},   // whether the identity provider role mapping is on
  },
  emits: ['save', 'delete'],
  setup(props, {emit}) {
    const form = reactive({tools: [], readOnly: false, approval: 'ask', perms: [], dirty: false,
      sandbox: {kind: 'none', scope: '', network: '', localFallback: false}, idpGroups: '', idpPriority: 100,
      modes: [...MODES], unattended: false, scopedInstructions: false});
    watch(() => props.role, r => {
      form.tools = [...r.tools];
      form.readOnly = r.readOnly;
      form.approval = r.approval;
      const sb = r.sandbox || {};
      form.sandbox = {kind: sb.kind || 'none', scope: sb.scope || '', network: sb.network || '', localFallback: !!sb.localFallback};
      form.perms = Object.entries(r.toolPermissions || {}).map(([pattern, decision]) => ({pattern, decision}));
      const idp = (r.settings || {}).idp || {};
      form.idpGroups = (idp.groups || []).join('\n');
      form.idpPriority = idp.priority ?? 100;
      const allowed = (r.settings || {})['allowed-modes'];
      form.modes = Array.isArray(allowed) && allowed.length ? [...allowed] : [...MODES];
      form.unattended = !!(r.settings || {}).unattended;
      form.scopedInstructions = !!(r.settings || {})['scoped-instructions'];
      form.dirty = false;
    }, {immediate: true});
    const touch = () => { form.dirty = true; };
    const onTools = v => { form.tools = v; touch(); };
    const addPerm = () => { form.perms.push({pattern: '', decision: 'allow'}); touch(); };
    const removePerm = i => { form.perms.splice(i, 1); touch(); };
    const idpSettings = () => {
      const settings = {...(props.role.settings || {})};
      const groups = form.idpGroups.split(/[\n,]+/).map(g => g.trim()).filter(g => g);
      if (groups.length) settings.idp = {groups, priority: Number.isInteger(form.idpPriority) ? form.idpPriority : 100};
      else delete settings.idp;
      // all four modes = no limit: the setting is removed; the server refuses an empty list
      if (form.modes.length < MODES.length) settings['allowed-modes'] = MODES.filter(m => form.modes.includes(m));
      else delete settings['allowed-modes'];
      if (form.unattended) settings.unattended = true; else delete settings.unattended;
      if (form.scopedInstructions) settings['scoped-instructions'] = true; else delete settings['scoped-instructions'];
      return settings;
    };
    const save = () => emit('save', {
      tools: form.tools, readOnly: form.readOnly, approval: form.approval, settings: idpSettings(),
      sandbox: {kind: form.sandbox.kind, scope: form.sandbox.scope || null, network: form.sandbox.network || null,
                localFallback: !!form.sandbox.localFallback},
      toolPermissions: Object.fromEntries(form.perms.filter(p => p.pattern.trim()).map(p => [p.pattern.trim(), p.decision])),
    });
    return {MODES, form, touch, onTools, addPerm, removePerm, save, emit};
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
          <option value="always">always ask (no mode waives a question)</option>
          <option value="off">off (no prompts)</option>
        </select>
      </div>
    </div>
    <fieldset class="role-sandbox">
      <legend>Sandbox</legend>
      <p class="muted">Where this role's users get files and a shell. <b>none</b>: no file tools and no <code>bash</code>;
        every attempt fails with an error. <b>shared local</b>: processes on the server machine in the chat directory with a minimal
        environment, confined with bubblewrap on Linux (no network unless granted); on Windows and macOS <b>not confined</b>
        (testing only). <b>docker isolated</b>: a hardened container (no network unless granted, resource limits).
        <b>docker open</b>: a standard container with network. Files and <code>bash</code> run inside the container.</p>
      <div class="form-row">
        <div class="field-sm">
          <label for="role-sandbox-kind">Kind</label>
          <select id="role-sandbox-kind" v-model="form.sandbox.kind" @change="touch">
            <option value="none">none (default)</option>
            <option value="shared-local">shared local (confined on Linux)</option>
            <option value="docker-isolated">docker isolated (hardened)</option>
            <option value="docker-open">docker open (standard)</option>
          </select>
        </div>
        <template v-if="form.sandbox.kind === 'docker-isolated' || form.sandbox.kind === 'docker-open'">
          <div class="field-sm">
            <label for="role-sandbox-scope">Container per</label>
            <select id="role-sandbox-scope" v-model="form.sandbox.scope" @change="touch">
              <option value="">server default</option>
              <option value="chat">chat</option>
              <option value="user">user</option>
            </select>
          </div>
        </template>
        <div v-if="form.sandbox.kind === 'docker-isolated' || form.sandbox.kind === 'shared-local'" class="field-sm">
          <label for="role-sandbox-network">Network</label>
          <select id="role-sandbox-network" v-model="form.sandbox.network" @change="touch">
            <option value="">server default (none)</option>
            <option value="none">none</option>
            <option value="default">allow network</option>
          </select>
        </div>
      </div>
      <p v-if="form.sandbox.kind === 'docker-isolated' || form.sandbox.kind === 'docker-open'" class="field-sm">
        <label><input type="checkbox" v-model="form.sandbox.localFallback" @change="touch"> When docker is unavailable,
          continue in a shared-local sandbox (never as a full host shell)</label></p>
      <p v-if="form.sandbox.kind === 'shared-local'" class="banner banner-error">Shared local runs commands on the server
        machine. Outside Linux (or without bubblewrap) nothing confines them: use it for testing only.</p>
    </fieldset>
    <fieldset class="role-modes">
      <legend>Permission modes and unattended runs</legend>
      <p class="muted">The permission modes this role's users may select (all four = no limit). <b>Unattended</b>: nobody
        answers permission questions in this role's chats; anything that would ask is denied, only <code>allow</code>
        rules run. Only administrators can set it, and flows of this role may then be marked unattended.</p>
      <div class="form-row">
        <label v-for="m in MODES" :key="m" class="tp-check"><input type="checkbox" :value="m" v-model="form.modes"
          @change="touch" /> {{ m }}</label>
      </div>
      <p v-if="!form.modes.length" class="banner banner-error">Select at least one mode.</p>
      <label class="tp-check"><input type="checkbox" v-model="form.unattended" @change="touch" /> Unattended
        (permission requests are denied automatically)</label>
    </fieldset>
    <fieldset class="role-scoped">
      <legend>Scoped instructions</legend>
      <p class="muted">Off by default. When on, chats of this role also read the instruction files of their chat
        directory, so whoever can write files there can steer the model.</p>
      <label class="tp-check"><input type="checkbox" v-model="form.scopedInstructions" @change="touch" /> Read instruction
        files of the chat directory</label>
    </fieldset>
    <fieldset class="role-idp">
      <legend>Sign-in mapping</legend>
      <p class="muted">Identity provider groups that give this role at sign-in, one per line (<code>*</code> = any
        text, case-sensitive). When several roles match, the lowest priority wins, ties by role name.</p>
      <p v-if="!mappingEnabled" class="banner banner-info">Role mapping is disabled (Role mapping tab): these groups are
        kept but inactive.</p>
      <p v-if="role.fixed" class="banner banner-info">The admin role is only given by a group when the role mapping
        allows admin.</p>
      <div class="form-row">
        <div class="field">
          <label for="role-idp-groups">Groups</label>
          <textarea id="role-idp-groups" rows="3" v-model="form.idpGroups" placeholder="engineering&#10;team-*"
                    @input="touch"></textarea>
        </div>
        <div class="field-sm">
          <label for="role-idp-priority">Priority</label>
          <input id="role-idp-priority" type="number" step="1" v-model.number="form.idpPriority" @input="touch" />
        </div>
      </div>
    </fieldset>
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
      <button class="btn-primary" :disabled="!form.dirty || !form.modes.length" @click="save">Save role</button>
      <button v-if="!role.fixed" class="btn-danger" :disabled="role.users > 0" :title="role.users > 0 ? 'Users still hold this role' : ''"
              @click="emit('delete')">Delete role</button>
    </div>
  </div>
`,
};
