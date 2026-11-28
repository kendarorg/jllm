import {computed, onMounted, onUnmounted, ref} from '../vendor/vue.esm-browser.prod.js';
import {onRunChanged, refreshWaiting, wf} from '../workflows.js';
import {toast} from '../store.js';
import QuestionForm from './QuestionForm.js';

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED'];

/**
 * The runs screen: follow a run, answer its open question, read its history, cancel it. Flows are managed in the Flows
 * screen. Everything goes through the jllm-workflow-web endpoints; the engine decides what is allowed.
 */
export default {
  components: {QuestionForm},
  props: {me: {type: Object, required: true}, focusRun: {type: String, default: null}},
  setup(props) {
    const runs = ref([]);
    const busy = ref(false);
    const open = ref(null);             // the run being looked at: {run, history}

    const guard = fn => async (...a) => {
      busy.value = true;
      try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } finally { busy.value = false; }
    };
    const show = guard(async id => {
      open.value = {run: await wf.run(id), history: await wf.history(id)};
    });
    const load = guard(async () => {
      runs.value = await wf.runs();
      if (open.value) open.value = {run: await wf.run(open.value.run.runId), history: await wf.history(open.value.run.runId)};
      await refreshWaiting();
    });

    let stop = null, timer = null;
    const active = r => r && ['PENDING', 'RUNNING'].includes(String(r.status).toUpperCase());
    onMounted(async () => {
      await load();
      if (props.focusRun) await show(props.focusRun);
      stop = onRunChanged(() => load());
      // a run that is walking sends no event per step: look again while one is open
      timer = setInterval(() => { if (!busy.value && (runs.value.some(active))) load(); }, 3000);
    });
    onUnmounted(() => { if (stop) stop(); clearInterval(timer); });

    const answer = guard(async value => {
      await wf.answer(open.value.run.runId, value);
      await load();
    });
    const cancel = guard(async () => {
      await wf.cancel(open.value.run.runId);
      await load();
    });
    const canCancel = run => run && !TERMINAL.includes(String(run.status).toUpperCase());
    const pretty = v => (v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v));
    const when = t => (t ? new Date(t).toLocaleString() : '');
    // time taken; a run still open counts up to now
    const took = r => {
      if (!r || !r.startedAt) return '';
      const s = Math.max(0, Math.round(((r.endedAt || Date.now()) - r.startedAt) / 1000));
      return s < 60 ? s + 's' : s < 3600 ? Math.floor(s / 60) + 'm ' + (s % 60) + 's' : Math.floor(s / 3600) + 'h ' + Math.floor(s % 3600 / 60) + 'm';
    };
    const errText = r => (r && r.error ? pretty(r.error.message || r.error) : '');
    const deniedCount = computed(() => ((open.value && open.value.history) || []).filter(e => e.kind === 'denied').length);
    return {deniedCount, runs, busy, open, load, show, answer, cancel, canCancel, pretty, when, took, errText};
  },
  template: `
  <section class="wf" aria-label="Runs">
    <div class="wf-runs">
      <p v-if="!runs.length" class="empty">No runs yet. Start one from the Flows screen.</p>
      <table v-else class="wf-table">
        <thead><tr><th>Flow</th><th>Status</th><th>Started</th><th>Time</th><th>Run</th></tr></thead>
        <tbody>
          <template v-for="r in runs" :key="r.runId">
            <tr :class="{current: open && open.run.runId === r.runId}">
              <td><button class="btn-link" @click="show(r.runId)">{{ r.flow }}</button></td>
              <td><span class="wf-status" :class="r.status.toLowerCase()">{{ r.status }}</span></td>
              <td>{{ when(r.startedAt) }}</td>
              <td>{{ took(r) }}</td>
              <td><code :title="r.runId">{{ r.runId.slice(0, 8) }}</code></td>
            </tr>
            <tr v-if="errText(r)"><td colspan="5" class="banner banner-error">{{ errText(r) }}</td></tr>
          </template>
        </tbody>
      </table>

      <div v-if="open" class="card wf-run">
        <h4>{{ open.run.flow }} <span class="wf-status" :class="open.run.status.toLowerCase()">{{ open.run.status }}</span></h4>
        <div class="row-actions">
          <button v-if="canCancel(open.run)" class="btn-sm" :disabled="busy" @click="cancel">Cancel run</button>
          <button class="btn-sm" @click="open = null">Close</button>
        </div>
        <p class="wf-times">Started {{ when(open.run.startedAt) }}<span v-if="open.run.endedAt"> · ended {{ when(open.run.endedAt) }}</span> · took {{ took(open.run) }}</p>
        <QuestionForm v-if="String(open.run.status).toUpperCase() === 'WAITING' && open.run.pending" :question="open.run.pending"
                      :busy="busy" @answer="answer" />
        <div v-if="open.run.result" class="wf-result"><b>Result</b> <code>{{ pretty(open.run.result) }}</code></div>
        <div v-if="open.run.error" class="banner banner-error">{{ pretty(open.run.error.message || open.run.error) }}</div>
        <p v-if="deniedCount" class="banner banner-error" role="status">{{ deniedCount }} tool call{{ deniedCount === 1 ? '' : 's' }}
          denied (unattended run, nobody could approve): see the history.</p>
        <h5>History</h5>
        <ol class="wf-history">
          <li v-for="(e, i) in open.history" :key="i" :class="{ 'wf-denied': e.kind === 'denied' }">
            <b>{{ e.step || 'flow' }}</b> <span>{{ e.kind }}</span> <span>{{ e.detail }}</span>
          </li>
        </ol>
      </div>
    </div>
  </section>
`,
};
