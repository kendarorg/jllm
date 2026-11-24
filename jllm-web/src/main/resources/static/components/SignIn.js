import {ref, onMounted} from '../vendor/vue.esm-browser.prod.js';
import {api, post} from '../api.js';

const AUTH_ERRORS = {
  denied: 'The sign-in was refused.',
  invalid_token: 'The identity provider answer could not be verified.',
  unknown_user: 'No user here matches that account. Ask an administrator to add you.',
  domain_not_allowed: 'The email domain of that account is not allowed.',
  email_not_verified: 'The email of that account is not verified.',
  expired: 'The sign-in took too long or was already used. Try again.',
};

/** Sign-in with login and password, with the identity provider, or both, as the server offers. */
export default {
  emits: ['signed-in'],
  setup(_, {emit}) {
    const id = ref('');
    const password = ref('');
    const error = ref('');
    const busy = ref(false);
    const config = ref({password: true});
    onMounted(async () => {
      const params = new URLSearchParams(location.search);
      const code = params.get('authError');
      if (code) {
        error.value = AUTH_ERRORS[code] || AUTH_ERRORS.denied;
        history.replaceState(null, '', location.pathname);
      }
      try { config.value = await api('/api/auth/config'); } catch (e) { /* keep the password form */ }
    });
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
    return {id, password, error, busy, config, submit};
  },
  template: `
  <section class="signin card" aria-label="Sign in">
    <h2>Sign in</h2>
    <form v-if="config.password" @submit.prevent="submit">
      <label for="signin-id">Login</label>
      <input id="signin-id" type="text" v-model="id" autocomplete="username" autofocus required />
      <label for="signin-password">Password</label>
      <input id="signin-password" type="password" v-model="password" autocomplete="current-password" required />
      <button class="btn-primary signin-submit" type="submit" :disabled="busy || !id || !password">Sign in</button>
    </form>
    <p v-if="config.password && config.oidc" class="muted small">or</p>
    <a v-if="config.oidc" class="btn-primary signin-submit" :href="config.oidc.loginUrl">Sign in with {{ config.oidc.name }}</a>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
`,
};
