import {api, post} from './api.js';

import {reactive} from 'vue';

const EVENTS = ['run_started', 'text', 'tool_call', 'tool_result', 'approval_request', 'usage', 'compaction',
  'warning', 'error', 'cancelled', 'run_finished', 'plan_ready'];

let uid = 0;
const nextKey = () => ++uid;

export const store = reactive({
  chatId: null,
  sessions: [],
  items: [],            // {key, kind:'msg', role, text, cls} | {key, kind:'tools', tools:[{id,name,input,status,output}]}
  mode: 'DEFAULT',
  running: false,
  usage: null,
  commands: [],
  approvals: [],        // queue; approvals[0] is shown
  toast: { text: '', type: '' },
  lastSeq: 0,
  msgCount: undefined,
  pendingUser: null,
  sending: false,
  tabs: {},             // chatId -> chat | files | history; a chat has an entry once it has been opened
  // the selected tab belongs to the open chat: reading/writing it goes through tabs[chatId]
  get tab() { return (this.chatId && this.tabs[this.chatId]) || 'chat'; },
  set tab(v) { if (this.chatId) this.tabs[this.chatId] = v; },
  focusTurn: null,      // turn index the History tab asked the Chat tab to scroll to
});

let es = null;
let group = null;       // current tool group item, ended by any non-tool message
let toastTimer = null;

export function toast(text, type = '') {
  store.toast = { text, type };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { store.toast = { text: '', type: '' }; }, 3000);
}

const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };

// ── rendering helpers ───────────────────────────────────────────────────────
export function addMsg(role, text, cls) {
  group = null;
  store.items.push({ key: nextKey(), kind: 'msg', role, text, cls: cls || role });
}

function addTool(id, name, input, status, output) {
  if (!group) {
    group = { key: nextKey(), kind: 'tools', tools: [] };
    store.items.push(group);
    group = store.items[store.items.length - 1]; // reactive proxy
  }
  group.tools.push({ id, name, input: input || {}, status, output: output || '' });
}

function findTool(id) {
  for (const it of store.items) {
    if (it.kind !== 'tools') continue;
    const t = it.tools.find(x => x.id === id);
    if (t) return t;
  }
  return null;
}

function renderHistory(messages) {
  const results = {};
  messages.forEach(m => (Array.isArray(m.content) ? m.content : []).forEach(b => {
    if (b.type === 'tool_result') results[b.tool_use_id] = b;
  }));
  messages.forEach(m => {
    const blocks = typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : (m.content || []);
    blocks.forEach(b => {
      if (b.type === 'text') addMsg(m.role, b.text);
      else if (b.type === 'tool_use') {
        const r = results[b.id];
        const out = r ? (typeof r.content === 'string' ? r.content : JSON.stringify(r.content)) : '';
        addTool(b.id, b.name, b.input, r && r.is_error ? 'err' : 'ok', out);
      }
    });
  });
}

// ── sessions ────────────────────────────────────────────────────────────────
export const loadSessions = guard(async () => { store.sessions = await api('/api/chat'); });

export function closeChat() {
  if (es) es.close();
  delete store.tabs[store.chatId];
  store.chatId = null; store.items = []; store.usage = null; group = null;
}

export const newChat = guard(async () => {
  const s = await post('/api/chat', {});
  await openChat(s.chatId);
});

export const deleteChat = guard(async id => {
  await api('/api/chat/' + id, { method: 'DELETE' });
  if (store.chatId === id) closeChat();
  await loadSessions();
});

export async function openChat(id) {
  if (es) es.close();
  if (!store.tabs[id]) store.tabs[id] = 'chat';   // never opened before: start on Chat; otherwise keep its own tab
  store.chatId = id; store.approvals = []; group = null; store.items = [];
  const s = await api('/api/chat/' + id);
  // idle chat: history is complete, so skip replaying buffered events (they would show twice)
  store.lastSeq = s.running ? 0 : (s.lastSeq || 0);
  store.msgCount = s.messages.length;
  store.mode = s.mode;
  renderHistory(s.messages);
  store.usage = s.usage;
  store.running = s.running;
  (s.pendingApprovals || []).forEach(enqueueApproval);
  connect();
  loadSessions();
}

// ── events ──────────────────────────────────────────────────────────────────
function handleEvent(ev) {
  const d = ev.data || {};
  switch (ev.type) {
    case 'run_started':
      store.running = true;
      if (store.pendingUser === d.text) store.pendingUser = null; else addMsg('user', d.text);
      break;
    case 'text': addMsg('assistant', d.text); break;
    case 'tool_call': addTool(d.id, d.name, d.input, 'run', ''); break;
    case 'tool_result': {
      const t = findTool(d.id);
      if (t) { t.status = d.isError ? 'err' : 'ok'; t.output = d.output || ''; }
      break;
    }
    case 'approval_request': enqueueApproval(d); break;
    case 'usage': refreshUsage(); break;
    case 'compaction': addMsg('notice', 'Context compacted (tier ' + (d.tier ?? d.kind ?? '?') + ')'); break;
    case 'plan_ready': addMsg('notice', d.message); break;
    case 'warning': addMsg('notice', d.message); break;
    case 'error': addMsg('error', d.message); break;
    case 'cancelled': addMsg('notice', 'Cancelled.'); break;
    case 'run_finished':
      store.approvals = []; store.running = false; refreshUsage(); loadSessions(); markSynced();
      break;
  }
}

function connect() {
  const id = store.chatId;
  const source = new EventSource(`/api/chat/${id}/events?after=${store.lastSeq}`);
  es = source;
  EVENTS.forEach(t => source.addEventListener(t, e => {
    if (store.chatId !== id) return;
    const ev = JSON.parse(e.data);
    if (ev.seq <= store.lastSeq) return;
    store.lastSeq = ev.seq; handleEvent(ev);
  }));
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED && store.chatId === id) {
      setTimeout(() => store.chatId === id && connect(), 2000);
    }
  };
}

// ── usage / polling ─────────────────────────────────────────────────────────
export async function refreshUsage() {
  if (!store.chatId) return;
  try { store.usage = await api(`/api/chat/${store.chatId}/usage`); } catch (e) { /* transient */ }
}

async function markSynced() {
  const id = store.chatId; if (!id) return;
  try { const s = await api('/api/chat/' + id); if (store.chatId === id) store.msgCount = s.messages.length; }
  catch (e) { /* transient */ }
}

let polling = false;
async function poll() {
  if (polling || document.hidden) return;
  polling = true;
  try {
    loadSessions();
    const id = store.chatId; if (!id) return;
    const s = await api('/api/chat/' + id);
    if (store.chatId !== id) return;
    store.usage = s.usage;
    if (es && es.readyState === EventSource.CLOSED) connect();
    if (s.running !== store.running && !store.sending) store.running = s.running;
    // idle on both sides but the saved history changed elsewhere: redraw it
    if (!s.running && !store.running && !store.sending && store.msgCount !== undefined
        && s.messages.length !== store.msgCount) await openChat(id);
  } catch (e) { /* next tick retries */ } finally { polling = false; }
}

export async function init() {
  try { store.commands = await api('/api/chat/commands'); } catch (e) { /* optional */ }
  await loadSessions();   // no chat is opened until the user picks one
  setInterval(poll, 5000);
}

// ── actions ─────────────────────────────────────────────────────────────────
/** Returns text to put back into the composer (prompt-template commands), or null. */
export async function send(text) {
  if (!text || !store.chatId) return null;
  const cmd = text.startsWith('/') && store.commands.find(c => c.name === text.split(/\s+/)[0]);
  if (cmd && cmd.action.startsWith('prompt:')) return cmd.action.slice(7);
  // slash commands run on the server through the same endpoint and are allowed during a run
  if (!cmd && store.running) return null;
  if (!cmd) {
    addMsg('user', text);
    store.pendingUser = text; store.running = true;
  }
  store.sending = true;
  try {
    const r = await post(`/api/chat/${store.chatId}/messages`, { text });
    if (r && r.command) {
      if (r.switchTo || r.refresh) await openChat(r.switchTo || store.chatId);
      if (r.output) addMsg('notice', r.output);
    }
  } catch (e) {
    if (!cmd) { store.items.pop(); store.pendingUser = null; store.running = false; }
    toast(e.message, 'error');
    return text;
  } finally { store.sending = false; }
  return null;
}

export const setMode = guard(async mode => {
  await post(`/api/chat/${store.chatId}/mode`, { mode });
  store.mode = mode; toast('Mode: ' + mode);
});

export const cancelRun = guard(async () => { if (store.chatId) await post(`/api/chat/${store.chatId}/cancel`); });

export const searchFiles = async q => {
  try { return await api(`/api/chat/${store.chatId}/files?q=${encodeURIComponent(q)}`); } catch (e) { return []; }
};

// ── approvals ───────────────────────────────────────────────────────────────
function enqueueApproval(a) {
  if (!store.approvals.some(x => x.id === a.id)) store.approvals.push(a);
}

export async function resolveApproval(a, approved, editedText, remember = false, rememberDir = false) {
  try {
    await post(`/api/chat/${store.chatId}/approvals/${a.id}`, { approved, editedText: approved ? editedText ?? null : null,
      remember: approved && remember, rememberDir: approved && rememberDir });
  } catch (e) { toast(e.message, 'error'); }
  store.approvals = store.approvals.filter(x => x.id !== a.id);
}
