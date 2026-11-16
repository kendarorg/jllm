import {computed, onMounted, ref} from 'vue';
import {api, del, post, put} from '../api.js';
import {toast} from '../store.js';
import SkillEditor from './SkillEditor.js';

const enc = encodeURIComponent;

/**
 * The Skills screen, for every user: all the skills they can use with a switch each, their own skills (create, edit,
 * delete) and the global ones. Administrators also create, edit and delete global skills here; skills of the settings
 * file are read-only for everybody.
 */
export default {
  components: {SkillEditor},
  props: {me: {type: Object, required: true}},
  setup(props) {
    const skills = ref([]);
    const usable = ref(false);
    const editing = ref(null);   // {skill, editable, scope: 'own' | 'global'}
    const isAdmin = computed(() => props.me.role === 'admin');

    const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };
    const base = scope => scope === 'global' ? '/api/admin/skills' : '/api/me/skills';
    const scopeOf = s => s.source === 'global' ? 'global' : 'own';
    const canEdit = s => s.source === 'global' ? isAdmin.value : s.source === 'own' && usable.value;
    const canDelete = s => s.source === 'global' ? isAdmin.value : s.source === 'own';

    const load = guard(async () => {
      const r = await api('/api/me/skills');
      skills.value = r.skills;
      usable.value = r.usable;
    });
    const toggle = guard(async s => {
      s.enabled = !s.enabled;
      try {
        await put('/api/me/skills/enabled', {disabled: skills.value.filter(x => !x.enabled).map(x => x.name)});
      } catch (e) { s.enabled = !s.enabled; throw e; }
    });
    const open = guard(async s => {
      const scope = scopeOf(s);
      editing.value = {skill: await api(base(scope) + '/' + enc(s.name)), editable: canEdit(s), scope};
    });
    const create = scope => { editing.value = {skill: null, editable: true, scope}; };
    const save = guard(async form => {
      const e = editing.value;
      if (e.skill) await put(base(e.scope) + '/' + enc(form.name), form);
      else await post(base(e.scope), form);
      editing.value = null;
      toast('Skill saved', 'success');
      await load();
    });
    const remove = guard(async s => {
      const scope = scopeOf(s);
      if (!confirm(scope === 'global' ? 'Delete the global skill ' + s.name + '? It disappears for every user.'
          : 'Delete the skill ' + s.name + '?')) return;
      await del(base(scope) + '/' + enc(s.name));
      await load();
    });
    onMounted(load);
    return {skills, usable, editing, isAdmin, canEdit, canDelete, toggle, open, create, save, remove};
  },
  template: `
  <section class="panel" aria-label="Skills">
    <div class="panel-body">
      <p>All the skills you can use. A switch applies to all your chats from their next turn.
        <template v-if="isAdmin">Global skills are offered to every user whose role has the skill tool.</template></p>
      <p v-if="!usable" class="banner banner-info">Your role does not include the skill tool (or you switched it off):
        skills are listed but not used, and you cannot add your own.</p>
      <SkillEditor v-if="editing" :skill="editing.skill" :editable="editing.editable"
                   @save="save" @cancel="editing = null" />
      <template v-else>
        <table class="grid">
          <thead><tr><th>On</th><th>Name</th><th>Source</th><th>Description</th><th></th></tr></thead>
          <tbody>
            <tr v-for="s in skills" :key="s.source + s.name">
              <td><input type="checkbox" :checked="s.enabled && !s.shadowed" :disabled="s.shadowed" @change="toggle(s)"
                         :aria-label="'Use ' + s.name" /></td>
              <td><code>{{ s.name }}</code></td>
              <td><span class="badge" :class="s.source">{{ s.source }}</span>
                <span v-if="s.shadowed" class="badge warn" title="A shared skill has this name; rename yours">hidden</span></td>
              <td class="muted">{{ s.description }}</td>
              <td class="row-actions">
                <button class="btn-sm" @click="open(s)">{{ canEdit(s) ? 'Edit' : 'View' }}</button>
                <button v-if="canDelete(s)" class="btn-danger" @click="remove(s)">Delete</button>
              </td>
            </tr>
            <tr v-if="!skills.length"><td colspan="5" class="empty">No skills.</td></tr>
          </tbody>
        </table>
        <div class="row-actions">
          <button class="btn-primary" :disabled="!usable" @click="create('own')">New skill</button>
          <button v-if="isAdmin" class="btn-sm" @click="create('global')">New global skill</button>
        </div>
      </template>
    </div>
  </section>
`,
};
