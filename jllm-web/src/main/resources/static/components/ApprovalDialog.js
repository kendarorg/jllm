import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { store, resolveApproval } from '../store.js';

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

    const approve = (remember = false, rememberDir = false) =>
      resolveApproval(a.value, true, ['context', 'continue'].includes(a.value.type) ? edited.value : null, remember, rememberDir);
    const reject = () => resolveApproval(a.value, false);

    const onEsc = e => { if (e.key === 'Escape' && a.value) reject(); };
    onMounted(() => document.addEventListener('keydown', onEsc));
    onUnmounted(() => document.removeEventListener('keydown', onEsc));
    return { a, edited, approveBtn, diffLines, approve, reject };
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
      <div class="dialog-foot">
        <button class="btn-danger" @click="reject">Reject</button>
        <button class="btn-sm" v-if="a.writeDir" @click="approve(false, true)">Allow writes in {{ a.writeDir }}/ and subfolders</button>
        <button class="btn-sm" v-if="a.type === 'tool'" @click="approve(true)">Allow for this chat</button>
        <button class="btn-primary" ref="approveBtn" @click="approve(false)">Approve</button>
      </div>
    </div>
  </div>
`,
};
