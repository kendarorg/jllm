import {nextTick, onMounted, ref, watch} from 'vue';
import {store} from '../store.js';

export default {
  setup() {
    const SYMBOL = { run: '…', err: '✕', ok: '✓' };
    const el = ref(null);

    const scrollDown = () => nextTick(() => { if (el.value) el.value.scrollTop = el.value.scrollHeight; });
    // follow new items and finished tool calls
    watch(() => [store.chatId, store.items.length,
      store.items.map(i => i.tools ? i.tools.length + i.tools.filter(t => t.status !== 'run').length : 0).join()],
      scrollDown, { flush: 'post' });
    onMounted(scrollDown);
    // History tab -> Show in Chat: scroll to the n-th user message
    watch(() => [store.focusTurn, store.tab], () => nextTick(() => {
      if (store.focusTurn === null || store.tab !== 'chat' || !el.value) return;
      const m = el.value.querySelectorAll('.msg.user')[store.focusTurn];
      store.focusTurn = null;
      if (m) m.scrollIntoView({ block: 'start' });
    }), { flush: 'post' });

    const running = g => g.tools.filter(t => t.status === 'run').length;
    const failed = g => g.tools.filter(t => t.status === 'err').length;
    const brief = t => JSON.stringify(t.input || {}).slice(0, 100);
    const pretty = t => JSON.stringify(t.input || {}, null, 2);
    // a task (sub-agent) result: one entry per run, status ok | failed | partial | refused | cancelled, maybe a warning
    const SUB_CLASS = { ok: 'status-ok', failed: 'status-err' };
    const subClass = s => SUB_CLASS[s] || 'status-warn';
    const subRuns = t => {
      if (t.name !== 'task' || !t.output) return [];
      try {
        const a = JSON.parse(t.output);
        if (Array.isArray(a)) return a.map(r => ({ status: String(r.status || '?'), warning: r.warning || '' }));
      } catch (e) { /* the event carries a cut output: read the statuses from the text */ }
      return [...t.output.matchAll(/"status"\s*:\s*"(\w+)"/g)].map(m => ({ status: m[1], warning: '' }));
    };
    return { store, SYMBOL, el, running, failed, brief, pretty, subRuns, subClass };
  },
  template: `
  <div class="stream" ref="el" role="log" aria-live="polite" aria-label="Messages">
    <div v-if="!store.chatId" class="empty">Create or select a chat to begin.</div>
    <template v-for="it in store.items" :key="it.key">
      <div v-if="it.kind === 'msg'" class="msg" :class="it.cls">{{ it.text }}</div>
      <details v-else class="toolgroup">
        <summary>Tool calls ({{ it.tools.length }})
          <span v-if="running(it)" class="gstatus-run">… {{ running(it) }} running</span>
          <span v-if="failed(it)" class="gstatus-err">✕ {{ failed(it) }} failed</span>
        </summary>
        <div class="group-body">
          <details v-for="t in it.tools" :key="t.id" class="tool">
            <summary><span :class="'status-' + t.status">{{ SYMBOL[t.status] }}</span> {{ t.name }}
              <span v-if="t.dropped" class="dropped" title="Replaced by a stub in the model's context; shown here for reference">not in context</span>
              <span style="color:var(--text-muted)">{{ brief(t) }}</span>
              <span v-for="(r, i) in subRuns(t)" :key="i" class="subrun" :class="subClass(r.status)"
                    :title="r.warning || ('sub-agent ' + r.status)">{{ r.status }}</span></summary>
            <template v-for="(r, i) in subRuns(t)" :key="'w' + i">
              <div v-if="r.warning" class="sub-warning">⚠ sub-agent {{ i + 1 }} ({{ r.status }}): {{ r.warning }}</div>
            </template>
            <pre class="tin">{{ pretty(t) }}</pre>
            <pre class="tout">{{ t.output }}</pre>
          </details>
        </div>
      </details>
    </template>
  </div>
`,
};
