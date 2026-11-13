import {ref} from 'vue';
import {post} from '../api.js';

/** Sign-in with login and password. */
export default {
  emits: ['signed-in'],
  setup(_, {emit}) {
    const id = ref('');
    const password = ref('');
    const error = ref('');
    const busy = ref(false);
    async function submit() {
      if (!id.value || !password.value || busy.value) return;
      error.value = '';
      busy.value = true;
      try {
        emit('signed-in', await post('/api/login', {id: id.value.trim(), password: password.value}));
      } catch (e) {
        error.value = e.message;
        password.value = '';
      } finally {
        busy.value = false;
      }
    }
    return {id, password, error, busy, submit};
  },
  template: `
  <section class="signin card" aria-label="Sign in">
    <h2>Sign in</h2>
    <form @submit.prevent="submit">
      <label for="signin-id">Login</label>
      <input id="signin-id" type="text" v-model="id" autocomplete="username" autofocus required />
      <label for="signin-password">Password</label>
      <input id="signin-password" type="password" v-model="password" autocomplete="current-password" required />
      <button class="btn-primary signin-submit" type="submit" :disabled="busy || !id || !password">Sign in</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
`,
};
