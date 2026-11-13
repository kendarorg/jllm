import {computed, nextTick, onMounted, onUnmounted, ref, watch} from 'vue';
import {resolveApproval, store, toast} from '../store.js';

export default {
  setup() {
    const a = computed(() => store.approvals[0] || null);
    const edited = ref('');
    const approveBtn = ref(null);

    watch(a, v => {
      edited.value = v?.proposed || '';
      if (v) nextTick(() => approveBtn.value && approveBtn.value.focus());
    }, { immediate: true });

    const diffLines = computed(() => String(a.value?.preview || '').split('\n').map(l => ({
      text: l || ' ',
      cls: l.startsWith('+ ') ? 'add' : l.startsWith('- ') ? 'del' : l.startsWith('--- ') ? 'hdr' : '',
    })));

    const otherDir = ref('');
    const error = ref('');   // the server's reason for refusing an answer (the dialog stays open)
    watch(a, () => { otherDir.value = ''; error.value = ''; });
    watch(otherDir, () => { error.value = ''; });
    const answer = async (...args) => {
      error.value = '';
      const refused = await resolveApproval(...args);
      if (refused) error.value = refused;
    };
    const approve = (remember = false, rememberDir = false) =>
      answer(a.value, true, ['context', 'continue'].includes(a.value.type) ? edited.value : null, remember, rememberDir);
    // A typed folder (relative to the project) replaces the offered one, as 'd <path>' in the CLI
    const approveDir = (typed = false) => {
      const t = typed ? otherDir.value.trim().replace(/\\/g, '/') : '';
      // same rules as the server: absolute, '..' and .git grant nothing; the dialog stays open for a new choice
      if (typed && (t.startsWith('/') || /^[A-Za-z]:/.test(t)
          || t.split('/').some(s => s === '..' || s.toLowerCase() === '.git'))) {
        error.value = 'Path not allowed (absolute, ".." or .git): no folder granted';
        toast(error.value, 'error');
        return;
      }
      return answer(a.value, true, typed ? otherDir.value.trim() : null, false, true);
    };
    const reject = () => answer(a.value, false);

    const onEsc = e => { if (e.key === 'Escape' && a.value) reject(); };
    onMounted(() => document.addEventListener('keydown', onEsc));
    onUnmounted(() => document.removeEventListener('keydown', onEsc));
    return { a, edited, approveBtn, diffLines, approve, approveDir, otherDir, reject, error };
  },
  template: `
  <div class="overlay" :class="{ show: a }" role="dialog" aria-modal="true" aria-labelledby="dlg-title">
    <div v-if="a" class="dialog">
      <h2 id="dlg-title">{{ a.title || 'Approval required' }}</h2>
      <div class="dialog-body">
        <div v-if="a.type === 'context'" class="two-col">
          <div><h3>Current (old)</h3><pre>{{ a.preview }}</pre></div>
          <div><h3>Proposed (new) — editable</h3>
            <textarea v-model="edited" aria-label="Proposed replacement context"></textarea></div>
        </div>
        <div v-else-if="a.type === 'continue'">
          <p>{{ a.preview }}</p>
          <label>Turns to allow <input type="number" min="1" v-model="edited" aria-label="Turns to allow"></label>
        </div>
        <div v-else class="diff"><div v-for="(l, i) in diffLines" :key="i" :class="l.cls">{{ l.text }}</div></div>
      </div>
      <p v-if="error" class="dialog-error" role="alert" style="color:var(--red);margin:0.5rem 0 0">{{ error }}</p>
      <div class="dialog-foot">
        <button class="btn-danger" @click="reject">Reject</button>
        <input v-if="a.writeDir" v-model="otherDir" placeholder="other project folder" aria-label="Other project folder to allow writes in">
        <button class="btn-sm" v-if="a.writeDir && otherDir.trim()" @click="approveDir(true)">Allow writes in {{ otherDir.trim() }}/</button>
        <button class="btn-sm" v-if="a.writeDir" @click="approveDir(false)">Allow writes in {{ a.writeDir }}/ and subfolders</button>
        <button class="btn-sm" v-if="a.type === 'tool'" @click="approve(true)">Allow for this chat</button>
        <button class="btn-sm" v-if="a.type === 'host'" @click="approve(true)">Allow {{ a.host }} and subdomains for this chat</button>
        <button class="btn-primary" ref="approveBtn" @click="approve(false)">{{ a.type === 'host' ? 'Allow once' : a.type === 'insecure_cert' ? 'Continue anyway (this call)' : 'Approve' }}</button>
      </div>
    </div>
  </div>
`,
};
