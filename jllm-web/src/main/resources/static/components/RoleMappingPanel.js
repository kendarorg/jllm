import {onMounted, reactive, ref} from '../vendor/vue.esm-browser.prod.js';
import {api, del, post, put} from '../api.js';
import {toast} from '../store.js';

/**
 * The identity provider role mapping: the global options (stored in the settings store, else the
 * jllm.auth.oidc.role-mapping.* properties) and a preview of the role a list of groups gives.
 */
export default {
  emits: ['changed'],
  setup(_, {emit}) {
    const state = ref(null);   // what GET /api/admin/auth/role-mapping answered
    const form = reactive({enabled: false, claim: 'groups', onNoMatch: 'default', allowAdmin: false, maxAge: '',
      dirty: false});
    const groups = ref('');
    const preview = ref(null);

    const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };
    const fill = s => {
      state.value = s;
      Object.assign(form, {enabled: s.enabled, claim: s.claim, onNoMatch: s.onNoMatch, allowAdmin: s.allowAdmin,
        maxAge: s.maxAge || '', dirty: false});
    };
    const load = guard(async () => { fill(await api('/api/admin/auth/role-mapping')); });
    const touch = () => { form.dirty = true; };
    const save = guard(async () => {
      fill(await put('/api/admin/auth/role-mapping', {enabled: form.enabled, claim: form.claim.trim(),
        onNoMatch: form.onNoMatch, allowAdmin: form.allowAdmin, maxAge: form.maxAge.trim() || null}));
      toast('Role mapping saved', 'success');
      emit('changed');
    });
    const reset = guard(async () => {
      if (!confirm('Drop the stored options? The jllm.auth.oidc.role-mapping.* properties apply again.')) return;
      fill(await del('/api/admin/auth/role-mapping'));
      emit('changed');
    });
    const run = guard(async () => {
      preview.value = await post('/api/admin/auth/role-mapping/preview', {groups: groups.value});
    });
    onMounted(load);
    return {state, form, touch, save, reset, groups, preview, run};
  },
  template: `
  <div class="role-mapping">
    <p v-if="!state" class="spinner"></p>
    <template v-else>
      <p class="muted">At each identity provider sign-in, the groups in the ID token pick the user's role: each role lists
        its groups under <b>Sign-in mapping</b> in the Roles tab. One role per user: the lowest priority wins, ties by
        role name. It applies at every identity provider sign-in, also for users with a password; a password sign-in never
        changes the role. The last admin is never demoted.</p>
      <p v-if="!state.editable" class="banner banner-info">Read-only: no settings store. Set the
        <code>jllm.auth.oidc.role-mapping.*</code> properties.</p>
      <p v-else-if="state.stored" class="banner banner-info">Stored options: they win over the
        <code>jllm.auth.oidc.role-mapping.*</code> properties.</p>
      <fieldset :disabled="!state.editable">
        <legend>Role mapping</legend>
        <div class="form-row">
          <label class="tp-check"><input type="checkbox" v-model="form.enabled" @change="touch" /> Enabled</label>
          <label class="tp-check"><input type="checkbox" v-model="form.allowAdmin" @change="touch" />
            Groups may grant the admin role</label>
        </div>
        <div class="form-row">
          <div class="field-sm">
            <label for="rm-claim">Claim</label>
            <input id="rm-claim" type="text" v-model="form.claim" placeholder="groups" @input="touch" />
          </div>
          <div class="field-sm">
            <label for="rm-nomatch">No match</label>
            <select id="rm-nomatch" v-model="form.onNoMatch" @change="touch">
              <option value="default">default role{{ state.defaultRole ? ' (' + state.defaultRole + ')' : '' }}</option>
              <option value="keep">keep the current role</option>
              <option value="refuse">refuse the sign-in</option>
            </select>
          </div>
          <div class="field-sm">
            <label for="rm-maxage">Max session age</label>
            <input id="rm-maxage" type="text" v-model="form.maxAge" placeholder="none, e.g. 8h" @input="touch" />
          </div>
        </div>
        <p class="muted small">Claim: a dotted path walks nested objects (Keycloak <code>realm_access.roles</code>).
          Max session age: an identity provider session older than this must sign in again, so a group removed at the
          provider takes effect; empty = the normal session lifetime.</p>
        <p v-if="form.enabled" class="banner banner-info">While enabled, a role set by hand on a user who signs in
          through the identity provider is overwritten at the next sign-in{{ form.onNoMatch === 'keep' ? ' when a group matches' : '' }}.</p>
        <div class="row-actions">
          <button class="btn-primary" :disabled="!form.dirty" @click="save">Save</button>
          <button v-if="state.stored" class="btn-sm" @click="reset">Use the properties</button>
        </div>
      </fieldset>
      <fieldset>
        <legend>Preview</legend>
        <p class="muted">Groups as the token would carry them; works whether the mapping is enabled or not.</p>
        <form class="inline-form" @submit.prevent="run">
          <input type="text" v-model="groups" placeholder="engineering, team-a" aria-label="Groups to preview" />
          <button type="submit">Preview</button>
        </form>
        <div v-if="preview" class="card">
          <p><b>{{ preview.outcome }}</b></p>
          <p v-if="preview.note" class="muted">{{ preview.note }}</p>
          <table v-if="preview.matches.length" class="grid">
            <thead><tr><th>Role</th><th>Priority</th><th>Group</th><th>Pattern</th><th></th></tr></thead>
            <tbody>
              <tr v-for="m in preview.matches" :key="m.role">
                <td><code>{{ m.role }}</code></td><td>{{ m.priority }}</td><td>{{ m.group }}</td>
                <td><code>{{ m.pattern }}</code></td>
                <td><span v-if="m.chosen" class="badge">chosen</span><small v-else-if="m.skipped" class="muted">{{ m.skipped }}</small></td>
              </tr>
            </tbody>
          </table>
          <p v-for="n in preview.notes" :key="n" class="muted small">{{ n }}</p>
          <p class="muted small">{{ preview.rule }}</p>
        </div>
      </fieldset>
    </template>
  </div>
`,
};
