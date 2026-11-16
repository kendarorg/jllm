import {onMounted, reactive, ref} from 'vue';
import {api, del, post, put} from '../api.js';
import {connectSource, toast} from '../store.js';
import ToolPicker from './ToolPicker.js';

const TABS = [['password', 'Password'], ['tools', 'Tools'], ['connections', 'Connections']];

/** The signed-in user's own settings. Every change applies to all their chats from the next turn. */
export default {
  components: {ToolPicker},
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

    // connections: OAuth logins of REST APIs and MCP servers (never a token here)
    const connections = ref(null);
    async function loadConnections() {
      try { connections.value = await api('/api/oauth'); } catch (e) { toast(e.message, 'error'); }
    }
    async function connect(c, shared = false) {
      await connectSource(c.source, shared);
      toast('Finish the login in the new tab, then refresh this list');
    }
    async function disconnect(c, shared = false) {
      try {
        await del('/api/oauth/' + encodeURIComponent(c.source) + (shared ? '?shared=true' : ''));
        await loadConnections();
      } catch (e) { toast(e.message, 'error'); }
    }
    const expires = s => s ? new Date(s * 1000).toLocaleString() : '';

    function select(t) {
      tab.value = t;
      if (t === 'connections') loadConnections();
      if (t === 'tools' && !catalog.value) loadTools();
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
      connections, loadConnections, connect, disconnect, expires, me: props.me};
  },
  template: `
  <section class="panel" aria-label="Settings">
    <div class="tabs" role="tablist" aria-label="Settings" @keydown="onKey">
      <button v-for="t in TABS" :key="t[0]" role="tab" class="tab" :class="{ active: tab === t[0] }"
              :aria-selected="tab === t[0]" :tabindex="tab === t[0] ? 0 : -1" @click="select(t[0])">{{ t[1] }}</button>
    </div>
    <div class="panel-body">
      <div v-if="tab === 'password' && me.source === 'oidc'" role="tabpanel">
        <p class="card narrow">You sign in through the identity provider; there is no password to change here.</p>
      </div>
      <div v-if="tab === 'password' && me.source !== 'oidc'" role="tabpanel">
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

      <div v-if="tab === 'connections'" role="tabpanel">
        <p>Your logins to the REST APIs and MCP servers that need one. Each call of your chats uses your own login.
          After connecting, resend your message or steer the chat with "retry".</p>
        <template v-if="connections">
          <table class="grid">
            <thead><tr><th>Source</th><th>State</th><th></th></tr></thead>
            <tbody>
              <tr v-for="c in connections.sources" :key="c.source">
                <td><code>{{ c.source }}</code></td>
                <td>
                  <span v-if="c.serviceAccount" class="muted">service account (no login)</span>
                  <template v-else>
                    <span v-if="c.connected">connected<span v-if="c.expiresAt" class="muted"> (expires {{ expires(c.expiresAt) }})</span></span>
                    <span v-else class="muted">not connected</span>
                    <span v-if="!c.perUser" class="badge">shared</span>
                    <span v-if="c.perUser && me.role === 'admin'" class="muted"> · shared login: {{ c.sharedConnected ? 'connected' : 'none' }}</span>
                  </template>
                </td>
                <td class="row-actions">
                  <template v-if="!c.serviceAccount">
                    <button class="btn-sm btn-primary" @click="connect(c, !c.perUser)">{{ c.connected ? 'Reconnect' : 'Connect' }}</button>
                    <button v-if="c.connected" class="btn-danger" @click="disconnect(c, !c.perUser)">Disconnect</button>
                  </template>
                </td>
              </tr>
              <tr v-if="!connections.sources.length"><td colspan="3" class="empty">No source needs a login.</td></tr>
            </tbody>
          </table>
          <p class="muted">Tokens are kept in: {{ connections.store }}</p>
          <div class="row-actions"><button class="btn-sm" @click="loadConnections">Refresh</button></div>
        </template>
        <div v-else class="spinner"></div>
      </div>
    </div>
  </section>
`,
};
