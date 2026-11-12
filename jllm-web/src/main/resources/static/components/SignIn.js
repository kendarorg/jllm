import {onMounted, ref} from 'vue';
import {api, post} from '../api.js';

/** Sample sign-in: pick a user. No password; this is not authentication. */
export default {
  emits: ['signed-in'],
  setup(_, {emit}) {
    const users = ref([]);
    const error = ref('');
    onMounted(async () => {
      try { users.value = await api('/api/users'); } catch (e) { error.value = e.message; }
    });
    async function pick(u) {
      error.value = '';
      try { emit('signed-in', await post('/api/login', {id: u.id})); } catch (e) { error.value = e.message; }
    }
    return {users, error, pick};
  },
  template: `
  <section class="signin" aria-label="Sign in">
    <h2>Sign in</h2>
    <p class="muted">Sample picker, no password. It shows per-user tools and credentials; it is not authentication.</p>
    <ul class="signin-list">
      <li v-for="u in users" :key="u.id">
        <button class="btn-primary" @click="pick(u)">{{ u.name }} <small>({{ u.role }})</small></button>
      </li>
    </ul>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
`,
};
