import {reactive} from '../vendor/vue.esm-browser.prod.js';
import {post} from '../api.js';

/** Full-screen password change for a default or one-time password: the server refuses everything else until done. */
export default {
  emits: ['changed'],
  setup(props, {emit}) {
    const pw = reactive({current: '', next: '', repeat: '', error: ''});
    async function change() {
      pw.error = '';
      if (pw.next !== pw.repeat) { pw.error = 'The two new passwords differ'; return; }
      try {
        await post('/api/me/password', {current: pw.current, next: pw.next});
        pw.current = pw.next = pw.repeat = '';
        emit('changed');
      } catch (e) { pw.error = e.message; }
    }
    return {pw, change};
  },
  template: `
  <section class="panel" aria-label="Change password" role="alertdialog">
    <div class="panel-body">
      <form class="card narrow" @submit.prevent="change">
        <h2>Choose a new password</h2>
        <p class="banner banner-info">Your password is a default or one-time password. Nothing else is available until you change it.</p>
        <label for="fp-current">Current password</label>
        <input id="fp-current" type="password" v-model="pw.current" autocomplete="current-password" required autofocus />
        <label for="fp-next">New password (at least 8 characters)</label>
        <input id="fp-next" type="password" v-model="pw.next" autocomplete="new-password" minlength="8" required />
        <label for="fp-repeat">Repeat the new password</label>
        <input id="fp-repeat" type="password" v-model="pw.repeat" autocomplete="new-password" required />
        <p v-if="pw.error" class="error" role="alert">{{ pw.error }}</p>
        <div class="row-actions"><button class="btn-primary" type="submit">Change password</button></div>
      </form>
    </div>
  </section>`,
};
