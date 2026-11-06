import {computed} from 'vue';
import {cancelRun, setMode, store} from '../store.js';

export default {
  setup() {
    const fmt = n => Number(n || 0).toLocaleString();
    const mode = computed({ get: () => store.mode, set: v => setMode(v) });
    const u = computed(() => store.usage);
    const cost = computed(() => (u.value?.estimatedCostUsd || 0).toFixed(4));
    return { store, fmt, mode, u, cost, cancelRun };
  },
  template: `
  <div class="toolbar">
    <label for="mode">Mode</label>
    <select id="mode" v-model="mode" :disabled="!store.chatId" aria-label="Permission mode">
      <option value="DEFAULT">Default (ask)</option>
      <option value="EDIT">Edit (auto-accept edits)</option>
      <option value="PLAN">Plan (read-only)</option>
      <option value="BYPASS">Bypass (no approvals)</option>
    </select>
    <button class="btn-sm" :disabled="!store.running" aria-label="Cancel the running turn" @click="cancelRun()">Cancel</button>
    <span class="status" aria-live="polite" style="color:var(--text-muted)">{{ store.running ? 'Running…' : '' }}</span>
    <span v-if="u" class="meter" aria-label="Token usage">
      in <b>{{ fmt(u.inputTokens) }}</b> · out <b>{{ fmt(u.outputTokens) }}</b> ·
      cache read <b>{{ fmt(u.cacheReadTokens) }}</b> · cache write <b>{{ fmt(u.cacheCreationTokens) }}</b> ·
      est. cost <b>\${{ cost }}</b>
    </span>
  </div>
`,
};
