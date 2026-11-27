import {computed, onMounted, reactive, ref} from '../vendor/vue.esm-browser.prod.js';
import {api, del, put} from '../api.js';
import {toast} from '../store.js';

const enc = encodeURIComponent;
const SCOPES = [['system', 'System prompt'], ['global', 'Global'], ['role', 'Role'], ['user', 'User']];
const SYSTEM_NAMES = ['replace', 'append'];

/**
 * Administration of the virtual configuration (/api/admin/config): instructions per scope (system prompt, global,
 * role, user) and sub-agents with an inline prompt. Admins only; nothing here names a file.
 */
export default {
  setup() {
    const section = ref('instructions');
    const scope = ref('global');
    const owner = ref('');
    const instructions = ref([]);
    const agents = ref([]);
    const editing = ref(null);   // {kind: 'instruction' | 'agent', isNew, form}
    const needsOwner = computed(() => scope.value === 'role' || scope.value === 'user');

    const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };
    const ownerQuery = () => needsOwner.value ? '?owner=' + enc(owner.value.trim()) : '';
    const loadInstructions = guard(async () => {
      if (needsOwner.value && !owner.value.trim()) { instructions.value = []; return; }
      instructions.value = await api('/api/admin/config/instructions/' + scope.value + ownerQuery());
    });
    const loadAgents = guard(async () => { agents.value = await api('/api/admin/config/agents'); });
    const setScope = s => { scope.value = s; editing.value = null; loadInstructions(); };

    const newInstruction = () => {
      editing.value = {kind: 'instruction', isNew: true,
        form: reactive({name: scope.value === 'system' ? 'append' : '', content: ''})};
    };
    const editInstruction = i => {
      editing.value = {kind: 'instruction', isNew: false, form: reactive({name: i.name, content: i.content})};
    };
    const saveInstruction = guard(async () => {
      const f = editing.value.form;
      await put('/api/admin/config/instructions/' + scope.value + '/' + enc(f.name.trim()) + ownerQuery(), {content: f.content});
      editing.value = null;
      toast('Instruction saved', 'success');
      await loadInstructions();
    });
    const removeInstruction = guard(async i => {
      if (!confirm('Delete the instruction ' + i.name + '?')) return;
      await del('/api/admin/config/instructions/' + scope.value + '/' + enc(i.name) + ownerQuery());
      await loadInstructions();
    });

    const newAgent = () => {
      editing.value = {kind: 'agent', isNew: true,
        form: reactive({name: '', description: '', tools: '', prompt: '', readOnly: false, inheritContext: false})};
    };
    const editAgent = a => {
      editing.value = {kind: 'agent', isNew: false, form: reactive({
        name: a.name, description: a.description || '', tools: (a.tools || []).join(', '), prompt: a.prompt || '',
        readOnly: !!a.readOnly, inheritContext: !!a.inheritContext})};
    };
    const saveAgent = guard(async () => {
      const f = editing.value.form;
      const body = {description: f.description, prompt: f.prompt, readOnly: f.readOnly, inheritContext: f.inheritContext,
        tools: f.tools.split(',').map(t => t.trim()).filter(Boolean)};
      await put('/api/admin/config/agents/' + enc(f.name.trim()), body);
      editing.value = null;
      toast('Agent saved', 'success');
      await loadAgents();
    });
    const removeAgent = guard(async a => {
      if (!confirm('Delete the agent ' + a.name + '?')) return;
      await del('/api/admin/config/agents/' + enc(a.name));
      await loadAgents();
    });
    const pick = s => { section.value = s; editing.value = null; };
    onMounted(() => { loadInstructions(); loadAgents(); });
    return {SCOPES, SYSTEM_NAMES, section, scope, owner, needsOwner, instructions, agents, editing, pick, setScope,
      loadInstructions, newInstruction, editInstruction, saveInstruction, removeInstruction, newAgent, editAgent,
      saveAgent, removeAgent};
  },
  template: `
  <div class="config-panel">
    <p class="muted">The configuration is kept by the server (database or server-only files); chat directories are never read.
      Instructions of a role or user are added to the system prompt of their chats.</p>
    <div class="row-actions">
      <button class="btn-sm" :class="{ active: section === 'instructions' }" @click="pick('instructions')">Instructions</button>
      <button class="btn-sm" :class="{ active: section === 'agents' }" @click="pick('agents')">Agents</button>
    </div>

    <template v-if="section === 'instructions'">
      <div class="row-actions">
        <button v-for="s in SCOPES" :key="s[0]" class="btn-sm" :class="{ active: scope === s[0] }" @click="setScope(s[0])">{{ s[1] }}</button>
        <input v-if="needsOwner" type="text" v-model="owner" @change="loadInstructions"
               :placeholder="scope === 'role' ? 'role name' : 'user id'" :aria-label="scope + ' owner'" />
      </div>
      <form v-if="editing && editing.kind === 'instruction'" class="card" @submit.prevent="saveInstruction">
        <label for="ins-name">Name</label>
        <select v-if="scope === 'system'" id="ins-name" v-model="editing.form.name" :disabled="!editing.isNew">
          <option v-for="n in SYSTEM_NAMES" :key="n" :value="n">{{ n }}</option>
        </select>
        <input v-else id="ins-name" type="text" v-model="editing.form.name" :disabled="!editing.isNew"
               pattern="[A-Za-z0-9_.-]{1,64}" required />
        <label for="ins-body">Text</label>
        <textarea id="ins-body" v-model="editing.form.content" rows="14"></textarea>
        <div class="row-actions">
          <button class="btn-primary" type="submit" :disabled="!editing.form.name">Save</button>
          <button class="btn-sm" type="button" @click="editing = null">Cancel</button>
        </div>
      </form>
      <template v-else>
        <table class="grid">
          <thead><tr><th>Name</th><th>Text</th><th></th></tr></thead>
          <tbody>
            <tr v-for="i in instructions" :key="i.name">
              <td><code>{{ i.name }}</code></td>
              <td class="muted">{{ i.content.slice(0, 100) }}</td>
              <td class="row-actions">
                <button class="btn-sm" @click="editInstruction(i)">Edit</button>
                <button class="btn-danger" @click="removeInstruction(i)">Delete</button>
              </td>
            </tr>
            <tr v-if="!instructions.length"><td colspan="3" class="empty">{{ needsOwner && !owner.trim() ? 'Enter the ' + scope + '.' : 'No instructions.' }}</td></tr>
          </tbody>
        </table>
        <button class="btn-primary" :disabled="needsOwner && !owner.trim()" @click="newInstruction">New instruction</button>
      </template>
    </template>

    <template v-if="section === 'agents'">
      <form v-if="editing && editing.kind === 'agent'" class="card" @submit.prevent="saveAgent">
        <label for="ag-name">Name</label>
        <input id="ag-name" type="text" v-model="editing.form.name" :disabled="!editing.isNew"
               pattern="[a-z0-9_-]{1,64}" placeholder="lowercase, digits, _ and -" required />
        <label for="ag-desc">Description</label>
        <input id="ag-desc" type="text" v-model="editing.form.description" />
        <label for="ag-tools">Tools (comma separated, empty = all)</label>
        <input id="ag-tools" type="text" v-model="editing.form.tools" />
        <label for="ag-prompt">Prompt</label>
        <textarea id="ag-prompt" v-model="editing.form.prompt" rows="10"></textarea>
        <label><input type="checkbox" v-model="editing.form.readOnly" /> Read-only tools</label>
        <label><input type="checkbox" v-model="editing.form.inheritContext" /> Inherit the context</label>
        <div class="row-actions">
          <button class="btn-primary" type="submit" :disabled="!editing.form.name">Save</button>
          <button class="btn-sm" type="button" @click="editing = null">Cancel</button>
        </div>
      </form>
      <template v-else>
        <table class="grid">
          <thead><tr><th>Name</th><th>Description</th><th></th></tr></thead>
          <tbody>
            <tr v-for="a in agents" :key="a.name">
              <td><code>{{ a.name }}</code></td>
              <td class="muted">{{ a.description }}</td>
              <td class="row-actions">
                <button class="btn-sm" @click="editAgent(a)">Edit</button>
                <button class="btn-danger" @click="removeAgent(a)">Delete</button>
              </td>
            </tr>
            <tr v-if="!agents.length"><td colspan="3" class="empty">No agents.</td></tr>
          </tbody>
        </table>
        <button class="btn-primary" @click="newAgent">New agent</button>
      </template>
    </template>
  </div>
`,
};
