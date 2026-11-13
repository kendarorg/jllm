import {onMounted, reactive, ref} from 'vue';
import {api, del, post, put} from '../api.js';
import {toast} from '../store.js';
import ToolPicker from './ToolPicker.js';
import SkillEditor from './SkillEditor.js';

const TABS = [['password', 'Password'], ['tools', 'Tools'], ['skills', 'Skills']];

/** The signed-in user's own settings. Every change applies to all their chats from the next turn. */
export default {
  components: {ToolPicker, SkillEditor},
  props: {me: {type: Object, required: true}},
  emits: ['password-changed'],
  setup(props, {emit}) {
    const tab = ref('password');

    // password
    const pw = reactive({current: '', next: '', repeat: '', error: ''});
    async function changePassword() {
      pw.error = '';
      if (pw.next !== pw.repeat) { pw.error = 'The two new passwords differ'; return; }
      try {
        await post('/api/me/password', {current: pw.current, next: pw.next});
        pw.current = pw.next = pw.repeat = '';
        toast('Password changed; other sessions were signed out', 'success');
        emit('password-changed');
      } catch (e) { pw.error = e.message; }
    }

    // tools
    const catalog = ref(null);
    const disabled = ref([]);
    const toolsDirty = ref(false);
    async function loadTools() {
      try {
        const r = await api('/api/me/tools');
        catalog.value = r;
        disabled.value = r.disabled || [];
        toolsDirty.value = false;
      } catch (e) { toast(e.message, 'error'); }
    }
    function onTools(v) { disabled.value = v; toolsDirty.value = true; }
    async function saveTools() {
      try {
        await put('/api/me/tools', {disabled: disabled.value});
        toast('Tools saved', 'success');
        await loadTools();
      } catch (e) { toast(e.message, 'error'); }
    }

    // skills
    const skills = ref([]);
    const usable = ref(false);
    const editing = ref(null);   // {skill, editable, isNew}
    async function loadSkills() {
      try {
        const r = await api('/api/me/skills');
        skills.value = r.skills;
        usable.value = r.usable;
      } catch (e) { toast(e.message, 'error'); }
    }
    async function toggleSkill(s) {
      s.enabled = !s.enabled;
      try {
        await put('/api/me/skills/enabled', {disabled: skills.value.filter(x => !x.enabled).map(x => x.name)});
      } catch (e) { s.enabled = !s.enabled; toast(e.message, 'error'); }
    }
    async function open(s) {
      try {
        const full = await api('/api/me/skills/' + encodeURIComponent(s.name));
        editing.value = {skill: full, editable: s.source === 'own' && usable.value};
      } catch (e) { toast(e.message, 'error'); }
    }
    const create = () => { editing.value = {skill: null, editable: true}; };
    async function save(form) {
      try {
        if (editing.value.skill) {
          await put('/api/me/skills/' + encodeURIComponent(form.name), form);
        } else {
          await post('/api/me/skills', form);
        }
        editing.value = null;
        toast('Skill saved', 'success');
        await loadSkills();
      } catch (e) { toast(e.message, 'error'); }
    }
    async function remove(s) {
      if (!confirm('Delete the skill ' + s.name + '?')) return;
      try { await del('/api/me/skills/' + encodeURIComponent(s.name)); await loadSkills(); } catch (e) { toast(e.message, 'error'); }
    }

    function select(t) {
      tab.value = t;
      if (t === 'tools' && !catalog.value) loadTools();
      if (t === 'skills') loadSkills();
    }
    function onKey(e) {
      const i = TABS.findIndex(t => t[0] === tab.value);
      const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
      if (n === null) return;
      select(TABS[(n + TABS.length) % TABS.length][0]);
      e.preventDefault();
    }
    onMounted(() => { if (props.me.defaultPassword) tab.value = 'password'; });

    return {TABS, tab, select, onKey, pw, changePassword, catalog, disabled, toolsDirty, onTools, saveTools,
      skills, usable, editing, toggleSkill, open, create, save, remove};
  },
  template: `
  <section class="panel" aria-label="Settings">
    <div class="tabs" role="tablist" aria-label="Settings" @keydown="onKey">
      <button v-for="t in TABS" :key="t[0]" role="tab" class="tab" :class="{ active: tab === t[0] }"
              :aria-selected="tab === t[0]" :tabindex="tab === t[0] ? 0 : -1" @click="select(t[0])">{{ t[1] }}</button>
    </div>
    <div class="panel-body">
      <div v-if="tab === 'password'" role="tabpanel">
        <form class="card narrow" @submit.prevent="changePassword">
          <p v-if="me.defaultPassword" class="banner banner-info">You still have the default password. Choose your own.</p>
          <label for="pw-current">Current password</label>
          <input id="pw-current" type="password" v-model="pw.current" autocomplete="current-password" required />
          <label for="pw-next">New password (at least 8 characters)</label>
          <input id="pw-next" type="password" v-model="pw.next" autocomplete="new-password" minlength="8" required />
          <label for="pw-repeat">Repeat the new password</label>
          <input id="pw-repeat" type="password" v-model="pw.repeat" autocomplete="new-password" required />
          <p v-if="pw.error" class="error" role="alert">{{ pw.error }}</p>
          <div class="row-actions"><button class="btn-primary" type="submit">Change password</button></div>
        </form>
      </div>

      <div v-if="tab === 'tools'" role="tabpanel">
        <p>Tools your role allows. Switch off the ones you do not want; it applies to all your chats from their next turn.
          Tools your role does not allow are not listed and cannot be switched on.</p>
        <template v-if="catalog">
          <ToolPicker :catalog="catalog" :model-value="disabled" mode="toggle" @update:model-value="onTools" />
          <div class="row-actions sticky-actions">
            <button class="btn-primary" :disabled="!toolsDirty" @click="saveTools">Save</button>
            <button class="btn-sm" :disabled="!toolsDirty" @click="loadTools">Discard</button>
          </div>
        </template>
        <div v-else class="spinner"></div>
      </div>

      <div v-if="tab === 'skills'" role="tabpanel">
        <p>All the skills you can use. A switch applies to all your chats from their next turn.</p>
        <p v-if="!usable" class="banner banner-info">Your role does not include the skill tool (or you switched it off):
          skills are listed but not used, and you cannot add your own.</p>
        <SkillEditor v-if="editing" :skill="editing.skill" :editable="editing.editable"
                     @save="save" @cancel="editing = null" />
        <template v-else>
          <table class="grid">
            <thead><tr><th>On</th><th>Name</th><th>Source</th><th>Description</th><th></th></tr></thead>
            <tbody>
              <tr v-for="s in skills" :key="s.source + s.name">
                <td><input type="checkbox" :checked="s.enabled && !s.shadowed" :disabled="s.shadowed" @change="toggleSkill(s)" :aria-label="'Use ' + s.name" /></td>
                <td><code>{{ s.name }}</code></td>
                <td><span class="badge" :class="s.source">{{ s.source }}</span>
                  <span v-if="s.shadowed" class="badge warn" title="A shared skill has this name; rename yours">hidden</span></td>
                <td class="muted">{{ s.description }}</td>
                <td class="row-actions">
                  <button class="btn-sm" @click="open(s)">{{ s.source === 'own' && usable ? 'Edit' : 'View' }}</button>
                  <button v-if="s.source === 'own'" class="btn-danger" @click="remove(s)">Delete</button>
                </td>
              </tr>
              <tr v-if="!skills.length"><td colspan="5" class="empty">No skills.</td></tr>
            </tbody>
          </table>
          <div class="row-actions"><button class="btn-primary" :disabled="!usable" @click="create">New skill</button></div>
        </template>
      </div>
    </div>
  </section>
`,
};
