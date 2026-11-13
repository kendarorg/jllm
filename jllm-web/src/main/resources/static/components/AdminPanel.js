import {computed, onMounted, reactive, ref} from 'vue';
import {api, del, post, put} from '../api.js';
import {toast} from '../store.js';
import RoleEditor from './RoleEditor.js';
import SkillEditor from './SkillEditor.js';

const TABS = [['users', 'Users'], ['roles', 'Roles'], ['skills', 'Skills']];
const enc = encodeURIComponent;

/** Administration: users, roles (with everything they can be granted) and global skills. Admins only. */
export default {
  components: {RoleEditor, SkillEditor},
  props: {me: {type: Object, required: true}},
  setup(props) {
    const tab = ref('users');
    const users = ref([]);
    const roles = ref([]);
    const catalog = ref(null);
    const skills = ref([]);

    const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };
    const loadUsers = guard(async () => { users.value = await api('/api/admin/users'); });
    const loadRoles = guard(async () => { roles.value = await api('/api/admin/roles'); });
    const loadCatalog = guard(async () => { catalog.value = await api('/api/admin/catalog'); });
    const loadSkills = guard(async () => { skills.value = await api('/api/admin/skills'); });

    // users
    const nu = reactive({id: '', name: '', role: '', password: ''});
    const admins = computed(() => users.value.filter(u => u.role === 'admin').length);
    const createUser = guard(async () => {
      await post('/api/admin/users', {...nu, id: nu.id.trim()});
      toast('User ' + nu.id + ' created' + (nu.password ? '' : ' with the default password'), 'success');
      Object.assign(nu, {id: '', name: '', password: ''});
      await loadUsers();
    });
    const setRole = guard(async (u, role) => {
      try { await put('/api/admin/users/' + enc(u.id), {role}); } finally { await loadUsers(); }
    });
    const rename = guard(async u => {
      const name = prompt('Display name of ' + u.id, u.name);
      if (name === null || !name.trim()) return;
      await put('/api/admin/users/' + enc(u.id), {name});
      await loadUsers();
    });
    const pwFor = ref(null);   // user id whose password row is open
    const pwValue = ref('');
    const setPassword = guard(async u => {
      await post('/api/admin/users/' + enc(u.id) + '/password', pwValue.value ? {password: pwValue.value} : {});
      toast(pwValue.value ? 'Password set' : 'Password reset to the default', 'success');
      pwFor.value = null; pwValue.value = '';
      await loadUsers();
    });
    const removeUser = guard(async u => {
      if (!confirm('Delete user ' + u.id + '? Their own skills are deleted too; their chats stay.')) return;
      await del('/api/admin/users/' + enc(u.id));
      await loadUsers();
    });
    const lastAdmin = u => u.role === 'admin' && admins.value <= 1;

    // roles
    const current = ref(null);   // selected role name
    const newRole = ref('');
    const selectedRole = computed(() => roles.value.find(r => r.name === current.value) || null);
    const pick = name => { current.value = name; if (!catalog.value) loadCatalog(); };
    const createRole = guard(async () => {
      const name = newRole.value.trim();
      await post('/api/admin/roles', {name, tools: [], readOnly: false, approval: 'ask'});
      newRole.value = '';
      await loadRoles();
      pick(name);
    });
    const saveRole = guard(async body => {
      await put('/api/admin/roles/' + enc(current.value), body);
      toast('Role saved', 'success');
      await loadRoles();
    });
    const deleteRole = guard(async () => {
      if (!confirm('Delete role ' + current.value + '?')) return;
      await del('/api/admin/roles/' + enc(current.value));
      current.value = null;
      await loadRoles();
    });

    // skills
    const editing = ref(null);   // {skill, editable}
    const openSkill = guard(async s => {
      const full = await api('/api/admin/skills/' + enc(s.name));
      editing.value = {skill: full, editable: s.source === 'global'};
    });
    const saveSkill = guard(async form => {
      if (editing.value.skill) await put('/api/admin/skills/' + enc(form.name), form);
      else await post('/api/admin/skills', form);
      editing.value = null;
      toast('Skill saved', 'success');
      await loadSkills();
    });
    const removeSkill = guard(async s => {
      if (!confirm('Delete the global skill ' + s.name + '?')) return;
      await del('/api/admin/skills/' + enc(s.name));
      await loadSkills();
    });

    function select(t) {
      tab.value = t;
      if (t === 'users') { loadUsers(); loadRoles(); }
      if (t === 'roles') { loadRoles(); loadCatalog(); }
      if (t === 'skills') loadSkills();
    }
    function onKey(e) {
      const i = TABS.findIndex(t => t[0] === tab.value);
      const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
      if (n === null) return;
      select(TABS[(n + TABS.length) % TABS.length][0]);
      e.preventDefault();
    }
    onMounted(() => select('users'));

    return {TABS, tab, select, onKey, users, roles, catalog, skills, nu, createUser, setRole, rename,
      pwFor, pwValue, setPassword, removeUser, lastAdmin, current, newRole, selectedRole, pick, createRole, saveRole,
      deleteRole, editing, openSkill, saveSkill, removeSkill};
  },
  template: `
  <section class="panel" aria-label="Administration">
    <div class="tabs" role="tablist" aria-label="Administration" @keydown="onKey">
      <button v-for="t in TABS" :key="t[0]" role="tab" class="tab" :class="{ active: tab === t[0] }"
              :aria-selected="tab === t[0]" :tabindex="tab === t[0] ? 0 : -1" @click="select(t[0])">{{ t[1] }}</button>
    </div>
    <div class="panel-body">

      <div v-if="tab === 'users'" role="tabpanel">
        <table class="grid">
          <thead><tr><th>Login</th><th>Name</th><th>Role</th><th>Password</th><th>Own skills</th><th></th></tr></thead>
          <tbody>
            <template v-for="u in users" :key="u.id">
              <tr>
                <td><code>{{ u.id }}</code></td>
                <td>{{ u.name }} <button class="copy-btn" @click="rename(u)" :aria-label="'Rename ' + u.id">✎</button></td>
                <td>
                  <select :value="u.role" @change="setRole(u, $event.target.value)" :disabled="lastAdmin(u)"
                          :title="lastAdmin(u) ? 'The last admin keeps the admin role' : ''" :aria-label="'Role of ' + u.id">
                    <option v-for="r in roles" :key="r.name" :value="r.name">{{ r.name }}</option>
                  </select>
                </td>
                <td><span v-if="u.defaultPassword" class="badge warn">default</span><span v-else class="badge">set</span></td>
                <td>{{ u.ownSkills }}
                  <small v-if="u.disabledTools.length" class="muted" :title="u.disabledTools.join(', ')">
                    · {{ u.disabledTools.length }} tool(s) off</small>
                  <small v-if="u.disabledSkills.length" class="muted" :title="u.disabledSkills.join(', ')">
                    · {{ u.disabledSkills.length }} skill(s) off</small></td>
                <td class="row-actions">
                  <button class="btn-sm" @click="pwFor = pwFor === u.id ? null : u.id; pwValue = ''">Password</button>
                  <button class="btn-danger" @click="removeUser(u)" :disabled="u.id === me.id || lastAdmin(u)"
                          :title="u.id === me.id ? 'You cannot delete your own account' : ''">Delete</button>
                </td>
              </tr>
              <tr v-if="pwFor === u.id" class="subrow">
                <td colspan="6">
                  <form class="inline-form" @submit.prevent="setPassword(u)">
                    <input type="password" v-model="pwValue" placeholder="New password; empty = reset to the default"
                           autocomplete="new-password" :aria-label="'New password of ' + u.id" />
                    <button type="submit">{{ pwValue ? 'Set password' : 'Reset to default' }}</button>
                  </form>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <form class="card new-user" @submit.prevent="createUser">
          <h3>New user</h3>
          <div class="form-row">
            <div class="field"><label for="nu-id">Login</label>
              <input id="nu-id" type="text" v-model="nu.id" pattern="[a-z0-9_-]{1,64}" required /></div>
            <div class="field"><label for="nu-name">Name</label>
              <input id="nu-name" type="text" v-model="nu.name" /></div>
            <div class="field"><label for="nu-role">Role</label>
              <select id="nu-role" v-model="nu.role" required>
                <option v-for="r in roles" :key="r.name" :value="r.name">{{ r.name }}</option>
              </select></div>
            <div class="field"><label for="nu-pw">Password</label>
              <input id="nu-pw" type="password" v-model="nu.password" placeholder="empty = default"
                     autocomplete="new-password" /></div>
            <button class="btn-primary" type="submit" :disabled="!nu.id || !nu.role">Create</button>
          </div>
          <p class="muted small">The default password is the SHA-256 (lowercase hex) of the login.</p>
        </form>
      </div>

      <div v-if="tab === 'roles'" role="tabpanel" class="roles-layout">
        <aside class="role-list">
          <button v-for="r in roles" :key="r.name" class="role-item" :class="{ active: current === r.name }"
                  @click="pick(r.name)">
            <code>{{ r.name }}</code> <small class="muted">{{ r.users }}</small>
            <span v-if="r.fixed" class="badge">fixed</span>
          </button>
          <form class="inline-form" @submit.prevent="createRole">
            <input type="text" v-model="newRole" placeholder="new role" pattern="[a-z0-9_-]{1,64}" aria-label="New role name" />
            <button type="submit" :disabled="!newRole.trim()">Add</button>
          </form>
        </aside>
        <div class="role-main">
          <RoleEditor v-if="selectedRole && catalog" :role="selectedRole" :catalog="catalog"
                      @save="saveRole" @delete="deleteRole" />
          <p v-else-if="selectedRole" class="spinner"></p>
          <p v-else class="empty">Select a role.</p>
        </div>
      </div>

      <div v-if="tab === 'skills'" role="tabpanel">
        <p>Global skills are offered to every user whose role has the skill tool; each user can switch them off for
          themselves. Skills of the settings file are read-only here.</p>
        <SkillEditor v-if="editing" :skill="editing.skill" :editable="editing.editable"
                     @save="saveSkill" @cancel="editing = null" />
        <template v-else>
          <table class="grid">
            <thead><tr><th>Name</th><th>Source</th><th>Description</th><th></th></tr></thead>
            <tbody>
              <tr v-for="s in skills" :key="s.source + s.name">
                <td><code>{{ s.name }}</code></td>
                <td><span class="badge" :class="s.source">{{ s.source }}</span>
                  <span v-if="s.shadows.length" class="badge warn" :title="'Hides the own skill of: ' + s.shadows.join(', ')">
                    hides {{ s.shadows.length }}</span></td>
                <td class="muted">{{ s.description }}</td>
                <td class="row-actions">
                  <button class="btn-sm" @click="openSkill(s)">{{ s.source === 'global' ? 'Edit' : 'View' }}</button>
                  <button v-if="s.source === 'global'" class="btn-danger" @click="removeSkill(s)">Delete</button>
                </td>
              </tr>
              <tr v-if="!skills.length"><td colspan="4" class="empty">No skills.</td></tr>
            </tbody>
          </table>
          <div class="row-actions"><button class="btn-primary" @click="editing = {skill: null, editable: true}">New global skill</button></div>
        </template>
      </div>
    </div>
  </section>
`,
};
