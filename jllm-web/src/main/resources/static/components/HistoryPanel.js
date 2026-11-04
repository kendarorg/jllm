import { ref, reactive, computed, watch, onMounted } from 'vue';
import { api } from '../api.js';
import { store, toast } from '../store.js';

const ICON = { agent: '🤖', turn: '💬', text: '✎', tool: '⚙', sub: '🧩', note: '·' };

const textOf = c => typeof c === 'string' ? c
  : (c || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
const strOf = v => typeof v === 'string' ? v : JSON.stringify(v, null, 2);
const clip = (s, n = 70) => { s = (s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n) + '…' : s; };

let seq = 0;
const node = (kind, label, extra = {}) => ({ id: 'n' + (++seq), kind, label, children: [], ...extra });

/** assistant text and tool calls (with their results) of a message list, in order */
function events(messages, runsByTool) {
  const results = {};
  messages.forEach(m => (Array.isArray(m.content) ? m.content : []).forEach(b => {
    if (b.type === 'tool_result') results[b.tool_use_id] = b;
  }));
  const out = [];
  messages.forEach(m => {
    if (m.role !== 'assistant') return;
    const blocks = typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : (m.content || []);
    blocks.forEach(b => {
      if (b.type === 'text' && b.text && b.text.trim()) {
        out.push(node('text', clip(b.text), { text: b.text, raw: b }));
      } else if (b.type === 'tool_use') {
        const r = results[b.id];
        const t = node('tool', b.name + '  ' + clip(JSON.stringify(b.input || {}), 60), {
          tool: b.name, input: b.input || {}, output: r ? strOf(r.content) : '', error: !!(r && r.is_error),
          raw: { call: b, result: r },
        });
        (runsByTool[b.id] || []).forEach(run => t.children.push(subAgent(run)));
        out.push(t);
      }
    });
  });
  return out;
}

function subAgent(run) {
  const n = node('sub', 'sub-agent: ' + clip(run.prompt, 60), {
    run, error: run.status !== 'ok', raw: run,
    tokens: (run.inputTokens || 0) + (run.outputTokens || 0),
  });
  n.children = events(run.messages || [], {});
  return n;
}

/** A turn starts at a plain user message (not a tool_result carrier). */
const isTurnStart = m => m.role === 'user'
  && (typeof m.content === 'string' || (m.content || []).some(b => b.type === 'text'));

export function buildTree(chat, runs, completedOnly) {
  const runsByTool = {};
  const loose = [];
  (runs || []).forEach(r => (r.toolUseId ? (runsByTool[r.toolUseId] ||= []) : loose).push(r));
  const known = new Set();
  (chat.messages || []).forEach(m => (Array.isArray(m.content) ? m.content : [])
    .forEach(b => b.type === 'tool_use' && known.add(b.id)));
  Object.keys(runsByTool).forEach(k => { if (!known.has(k)) loose.push(...runsByTool[k]); });

  const root = node('agent', 'main agent', {
    agentId: chat.agentId, usage: chat.usage, raw: { chatId: chat.chatId, agentId: chat.agentId },
  });
  const groups = [];
  (chat.messages || []).forEach(m => {
    if (isTurnStart(m)) groups.push({ user: m, msgs: [] });
    else if (groups.length) groups[groups.length - 1].msgs.push(m);
  });
  if (completedOnly && groups.length) groups.pop();   // the turn still being worked on
  groups.forEach((g, i) => {
    const text = textOf(g.user.content);
    const t = node('turn', (i + 1) + '. ' + clip(text), { turn: i, text, raw: [g.user, ...g.msgs] });
    t.children = events(g.msgs, runsByTool);
    root.children.push(t);
  });
  if (loose.length) {
    const o = node('note', 'sub-agents without a parent call', {});
    o.children = loose.map(subAgent);
    root.children.push(o);
  }
  return root;
}

const count = (n, kind) => n.children.reduce((a, c) => a + (c.kind === kind ? 1 : 0) + count(c, kind), 0);

export default {
  setup() {
    const root = ref(null);
    const open = reactive({});      // node id -> expanded
    const selected = ref(null);
    const filter = ref('');
    const loading = ref(false);
    const treeEl = ref(null);

    async function load() {
      const id = store.chatId;
      if (!id) { root.value = null; selected.value = null; return; }
      loading.value = true;
      try {
        const [chat, runs] = await Promise.all([api('/api/chat/' + id), api(`/api/chat/${id}/subagents`)]);
        if (store.chatId !== id) return;
        root.value = buildTree(chat, runs, chat.running);
        open[root.value.id] = true;
        const kids = root.value.children;
        selected.value = kids.length ? kids[kids.length - 1] : root.value;
      } catch (e) { toast(e.message, 'error'); } finally { loading.value = false; }
    }

    const hay = n => (n.label + ' ' + (n.text || '') + ' ' + (n.output || '') + ' ' + JSON.stringify(n.input || '')).toLowerCase();
    const matches = (n, f) => hay(n).includes(f) || n.children.some(c => matches(c, f));

    const rows = computed(() => {
      const out = [];
      const f = filter.value.trim().toLowerCase();
      const walk = (n, depth, parent) => {
        if (f && !matches(n, f)) return;
        out.push({ n, depth, parent });
        if (n.children.length && (f || open[n.id])) n.children.forEach(c => walk(c, depth + 1, n));
      };
      if (root.value) walk(root.value, 0, null);
      return out;
    });

    const toggle = n => { if (n.children.length) open[n.id] = !open[n.id]; };
    const isOpen = n => !!(filter.value.trim() || open[n.id]);

    function key(e) {
      const i = rows.value.findIndex(r => r.n === selected.value);
      const cur = rows.value[i];
      if (e.key === 'ArrowDown') selected.value = rows.value[Math.min(i + 1, rows.value.length - 1)]?.n;
      else if (e.key === 'ArrowUp') selected.value = rows.value[Math.max(i - 1, 0)]?.n;
      else if (e.key === 'ArrowRight' && cur) {
        if (cur.n.children.length && !open[cur.n.id]) open[cur.n.id] = true;
        else if (cur.n.children.length) selected.value = cur.n.children[0];
      } else if (e.key === 'ArrowLeft' && cur) {
        if (open[cur.n.id] && cur.n.children.length) open[cur.n.id] = false;
        else if (cur.parent) selected.value = cur.parent;
      } else if (e.key === 'Home') selected.value = rows.value[0]?.n;
      else if (e.key === 'End') selected.value = rows.value[rows.value.length - 1]?.n;
      else return;
      e.preventDefault();
      requestAnimationFrame(() => treeEl.value?.querySelector('.hrow.sel')?.scrollIntoView({ block: 'nearest' }));
    }

    function expandAll(state) {
      const walk = n => { if (n.children.length) open[n.id] = state; n.children.forEach(walk); };
      if (!root.value) return;
      walk(root.value);
      open[root.value.id] = true;
    }
    const copy = async n => {
      try { await navigator.clipboard.writeText(JSON.stringify(n.raw, null, 2)); toast('Copied'); }
      catch (e) { toast('Copy failed', 'error'); }
    };
    const jump = n => { store.focusTurn = n.turn; store.tab = 'chat'; };
    const fmt = n => Number(n || 0).toLocaleString();
    const pretty = v => JSON.stringify(v, null, 2);
    const title = n => ({ agent: 'Main agent', turn: 'Turn ' + (n.turn + 1), tool: n.tool, sub: 'Sub-agent',
                          text: 'Assistant', note: 'Note' }[n.kind]);

    onMounted(load);
    watch(() => store.chatId, load);
    watch(() => store.running, (now, was) => { if (was && !now) load(); });
    return { rows, selected, filter, loading, treeEl, ICON, load, toggle, isOpen, key, expandAll, copy, jump, fmt,
             pretty, title, count, store };
  },
  template: `
  <div class="hist">
    <div v-if="!store.chatId" class="empty">Select a chat to see its history.</div>
    <template v-else>
    <div class="hist-tree">
      <div class="hist-bar">
        <input type="text" v-model="filter" placeholder="Filter (text, tool, input, output)" aria-label="Filter history" />
        <button class="btn-sm" @click="expandAll(true)" title="Expand all" aria-label="Expand all">⊞</button>
        <button class="btn-sm" @click="expandAll(false)" title="Collapse all" aria-label="Collapse all">⊟</button>
        <button class="btn-sm" :disabled="loading" @click="load()" title="Reload" aria-label="Reload">↻</button>
      </div>
      <div class="hist-rows" ref="treeEl" role="tree" tabindex="0" aria-label="Agent history" @keydown="key">
        <div v-if="!rows.length" class="empty">{{ loading ? 'Loading…' : 'No completed turns yet.' }}</div>
        <div v-for="r in rows" :key="r.n.id" class="hrow" :class="{ sel: r.n === selected, err: r.n.error }" role="treeitem"
             :aria-level="r.depth + 1" :aria-selected="r.n === selected"
             :aria-expanded="r.n.children.length ? isOpen(r.n) : undefined"
             :style="{ paddingLeft: (0.5 + r.depth * 1.1) + 'rem' }" @click="selected = r.n">
          <span class="twisty" @click.stop="toggle(r.n)">{{ r.n.children.length ? (isOpen(r.n) ? '▾' : '▸') : '' }}</span>
          <span class="hicon">{{ ICON[r.n.kind] }}</span>
          <span class="hlabel">{{ r.n.kind === 'agent' ? 'main agent ' + (r.n.agentId || '') : r.n.label }}</span>
          <span v-if="r.n.kind === 'sub' && r.n.tokens" class="hmeta">{{ fmt(r.n.tokens) }} tok</span>
        </div>
      </div>
    </div>
    <div class="hist-detail">
      <div v-if="!selected" class="empty">Select a node.</div>
      <template v-else>
        <div class="hd-head">
          <h3>{{ ICON[selected.kind] }} {{ title(selected) }}</h3>
          <span class="hd-actions">
            <button v-if="selected.kind === 'turn'" class="btn-sm" @click="jump(selected)">Show in Chat</button>
            <button v-if="selected.raw" class="btn-sm" @click="copy(selected)">Copy JSON</button>
          </span>
        </div>
        <template v-if="selected.kind === 'agent'">
          <p>Agent id: <code>{{ selected.agentId }}</code></p>
          <p>{{ selected.children.filter(c => c.kind === 'turn').length }} completed turns ·
             {{ count(selected, 'tool') }} tool calls · {{ count(selected, 'sub') }} sub-agent runs</p>
          <p v-if="selected.usage">in <b>{{ fmt(selected.usage.inputTokens) }}</b> · out <b>{{ fmt(selected.usage.outputTokens) }}</b> ·
             cache read <b>{{ fmt(selected.usage.cacheReadTokens) }}</b> · est. $<b>{{ (selected.usage.estimatedCostUsd || 0).toFixed(4) }}</b></p>
        </template>
        <template v-else-if="selected.kind === 'turn'">
          <h4>User</h4><pre>{{ selected.text }}</pre>
          <p>{{ count(selected, 'tool') }} tool calls · {{ count(selected, 'sub') }} sub-agent runs</p>
        </template>
        <template v-else-if="selected.kind === 'text'"><pre>{{ selected.text }}</pre></template>
        <template v-else-if="selected.kind === 'tool'">
          <h4>Input</h4><pre>{{ pretty(selected.input) }}</pre>
          <h4>Output <span v-if="selected.error" class="gstatus-err">(error)</span></h4><pre>{{ selected.output || '(none)' }}</pre>
        </template>
        <template v-else-if="selected.kind === 'sub'">
          <p>Status: <b :class="selected.error ? 'gstatus-err' : ''">{{ selected.run.status }}</b> ·
             in {{ fmt(selected.run.inputTokens) }} · out {{ fmt(selected.run.outputTokens) }} ·
             cache read {{ fmt(selected.run.cacheReadTokens) }}</p>
          <h4>Prompt</h4><pre>{{ selected.run.prompt }}</pre>
          <h4>Answer</h4><pre>{{ selected.run.answer || '(none)' }}</pre>
        </template>
      </template>
    </div>
    </template>
  </div>
`,
};
