import {computed, onUnmounted, ref} from 'vue';
import {cancelRun, copyId, scriptRunning, setMode, store} from '../store.js';

export default {
  setup() {
    const fmt = n => Number(n || 0).toLocaleString();
    const mode = computed({ get: () => store.mode, set: v => setMode(v) });
    const u = computed(() => store.usage);
    const cost = computed(() => (u.value?.estimatedCostUsd || 0).toFixed(4));
    // while a script runs, Cancel asks how to stop it; no answer in 30 s kills it
    const askStop = ref(false);
    let timer = null;
    const clear = () => { askStop.value = false; if (timer) { clearTimeout(timer); timer = null; } };
    const stop = kill => { clear(); cancelRun(kill); };
    const cancel = () => {
      if (!scriptRunning()) return cancelRun(false);
      askStop.value = true;
      timer = setTimeout(() => stop(true), 30000);
    };
    onUnmounted(clear);
    return { store, fmt, mode, u, cost, cancel, copyId, askStop, stop, clear };
  },
  template: `
  <div class="toolbar">
    <span class="chat-id" :title="'Chat id ' + store.chatId">chat <code>{{ store.chatId }}</code></span>
    <button class="btn-sm" aria-label="Copy the chat id" @click="copyId(store.chatId)">Copy id</button>
    <label for="mode">Mode</label>
    <select id="mode" v-model="mode" :disabled="!store.chatId" aria-label="Permission mode">
      <option value="DEFAULT">Default (ask)</option>
      <option value="EDIT">Edit (auto-accept edits)</option>
      <option value="PLAN">Plan (read-only)</option>
      <option value="BYPASS">Bypass (no approvals)</option>
    </select>
    <button class="btn-sm" :disabled="!store.running" aria-label="Cancel the running turn" @click="cancel()">Cancel</button>
    <span v-if="askStop" role="alertdialog" aria-label="Stop the running script">
      A script is running:
      <button class="btn-danger btn-sm" @click="stop(true)">Kill now</button>
      <button class="btn-sm" @click="stop(false)">Let it clean up</button>
      <button class="btn-sm" @click="clear()">Keep running</button>
      <span style="color:var(--text-muted)">(no answer in 30 s: kill)</span>
    </span>
    <span class="status" aria-live="polite" style="color:var(--text-muted)">{{ store.running ? 'Running…' : '' }}</span>
    <span v-if="u" class="meter" aria-label="Token usage">
      in <b>{{ fmt(u.inputTokens) }}</b> · out <b>{{ fmt(u.outputTokens) }}</b> ·
      cache read <b>{{ fmt(u.cacheReadTokens) }}</b> · cache write <b>{{ fmt(u.cacheCreationTokens) }}</b> ·
      est. cost <b>\${{ cost }}</b>
    </span>
  </div>
`,
};
