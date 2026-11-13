import {api, post} from './api.js';

import {reactive} from 'vue';

const EVENTS = ['run_started', 'text', 'tool_call', 'tool_result', 'approval_request', 'usage', 'compaction',
  'warning', 'error', 'cancelled', 'run_finished', 'plan_ready', 'login'];

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
  menuOpen: false,      // a numbered slash-command menu is open on the server for this chat
  sending: false,
  tabs: {},             // chatId -> chat | files | history; a chat has an entry once it has been opened
  // the selected tab belongs to the open chat: reading/writing it goes through tabs[chatId]
  get tab() { return (this.chatId && this.tabs[this.chatId]) || 'chat'; },
  set tab(v) { if (this.chatId) this.tabs[this.chatId] = v; },
  focusTurn: null,      // turn index the History tab asked the Chat tab to scroll to
  resumeOffer: null,    // {chatId, title}: the chat this browser had open last time, offered instead of opened
});

const LAST_CHAT = 'jllm.lastChat';
function remember(id) {
  try { if (id) localStorage.setItem(LAST_CHAT, id); else localStorage.removeItem(LAST_CHAT); } catch (e) { /* private mode */ }
}
function remembered() {
  try { return localStorage.getItem(LAST_CHAT); } catch (e) { return null; }
}

export async function copyId(id) {
  try { await navigator.clipboard.writeText(id); toast('Chat id copied'); } catch (e) { toast(id); }
}

/** Short form of a chat id for lists; the full id is in the tooltip and in the header. */
export const shortId = id => (id || '').slice(0, 8);

const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { toast(e.message, 'error'); } };

export const continueOffer = guard(async () => {
  const o = store.resumeOffer; store.resumeOffer = null;
  if (o) await openChat(o.chatId);
});

export const dismissOffer = () => { store.resumeOffer = null; };

let es = null;
let group = null;       // current tool group item, ended by any non-tool message
let toastTimer = null;

export function toast(text, type = '') {
  store.toast = { text, type };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { store.toast = { text: '', type: '' }; }, 3000);
}

// ── rendering helpers ───────────────────────────────────────────────────────
export function addMsg(role, text, cls) {
  group = null;
  store.items.push({ key: nextKey(), kind: 'msg', role, text, cls: cls || role });
}

function addTool(id, name, input, status, output, dropped) {
  if (!group) {
    group = { key: nextKey(), kind: 'tools', tools: [] };
    store.items.push(group);
    group = store.items[store.items.length - 1]; // reactive proxy
  }
  group.tools.push({ id, name, input: input || {}, status, output: output || '', dropped: !!dropped });
}

function findTool(id) {
  for (const it of store.items) {
    if (it.kind !== 'tools') continue;
    const t = it.tools.find(x => x.id === id);
    if (t) return t;
  }
  return null;
}

/** The text a repaired log carries for a tool call that never finished (see InterruptedTurnRepair). */
const INTERRUPTED = 'interrupted: process stopped';

/** refs: the originals of calls replaced by a stub in the context; the chat shows the original, marked as dropped. */
function renderHistory(messages, refs) {
  let interrupted = false;
  const original = {};
  (refs || []).forEach(r => { original[r.replaces] = r.original || {}; });
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
        const o = original[b.id];
        const shown = o && o.tool_result ? o.tool_result : r;
        const out = shown ? (typeof shown.content === 'string' ? shown.content : JSON.stringify(shown.content)) : '';
        if (out === INTERRUPTED) interrupted = true;
        addTool(b.id, b.name, o && o.tool_use ? o.tool_use.input : b.input, shown && shown.is_error ? 'err' : 'ok', out, !!o);
      }
    });
  });
  if (interrupted) addMsg('notice', 'This run was interrupted: the process stopped before it finished. Send a message to go on.');
}

// ── sessions ────────────────────────────────────────────────────────────────
export const loadSessions = guard(async () => { store.sessions = await api('/api/chat'); });

// Each openChat takes a token; an older call that resumes after a newer one (or a close) started drops its result.
let openToken = 0;
let openAbort = null;

function cancelOpen() {
  openToken++;
  if (openAbort) { openAbort.abort(); openAbort = null; }
}

function closeStream() {
  if (es) { es.close(); es = null; }
}

export function closeChat() {
  cancelOpen();
  closeStream();
  remember(null);
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
  cancelOpen();
  const token = openToken;
  const ctl = new AbortController();
  openAbort = ctl;
  const current = () => token === openToken;
  closeStream();
  if (!store.tabs[id]) store.tabs[id] = 'chat';   // never opened before: start on Chat; otherwise keep its own tab
  store.chatId = id; store.approvals = []; store.menuOpen = false; group = null; store.items = []; store.resumeOffer = null;
  store.msgCount = undefined;   // the poll must not compare the new chat against the old count while loading
  remember(id);
  try {
    const s = await api('/api/chat/' + id, { signal: ctl.signal });
    if (!current()) return;
    let refs = [];
    try { refs = await api('/api/chat/' + id + '/references', { signal: ctl.signal }); } catch (e) { /* optional */ }
    if (!current()) return;
    // idle chat: history is complete, so skip replaying buffered events (they would show twice)
    store.lastSeq = s.running ? 0 : (s.lastSeq || 0);
    store.msgCount = s.messages.length;
    store.mode = s.mode;
    renderHistory(s.messages, refs);
    store.usage = s.usage;
    store.running = s.running;
    (s.pendingApprovals || []).forEach(enqueueApproval);
    connect();
    loadSessions();
  } catch (e) {
    if (!current() || e.name === 'AbortError') return;   // superseded by a newer openChat or a close
    throw e;
  } finally {
    if (openAbort === ctl) openAbort = null;
  }
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
    case 'login': addMsg(d.ok ? 'notice' : 'error', d.message); toast(d.message, d.ok ? '' : 'error'); break;
    case 'error': addMsg('error', d.message); break;
    case 'cancelled': addMsg('notice', 'Cancelled.'); break;
    case 'run_finished':
      store.approvals = []; store.running = false; refreshUsage(); loadSessions(); markSynced();
      break;
  }
}

function connect() {
  closeStream();   // at most one stream: a second connect must not leak the first
  const id = store.chatId;
  if (!id) return;
  const source = new EventSource(`/api/chat/${id}/events?after=${store.lastSeq}`);
  es = source;
  EVENTS.forEach(t => source.addEventListener(t, e => {
    if (es !== source || store.chatId !== id) return;
    const ev = JSON.parse(e.data);
    if (ev.seq <= store.lastSeq) return;
    // events were lost (buffer trimmed, server restarted): an approval_request may be among them
    if (ev.seq > store.lastSeq + 1) syncApprovals(id);
    store.lastSeq = ev.seq; handleEvent(ev);
  }));
  // every (re)connect: approvals asked while the stream was down are not replayed if their event is gone
  source.onopen = () => { if (es === source && store.chatId === id) syncApprovals(id); };
  source.onerror = () => {
    // only the live stream reconnects; a replaced or closed one stays dead
    if (source.readyState === EventSource.CLOSED && es === source && store.chatId === id) {
      setTimeout(() => { if (es === source && store.chatId === id) connect(); }, 2000);
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
  const last = remembered();
  const known = last && store.sessions.find(s => s.chatId === last);
  if (known) store.resumeOffer = { chatId: known.chatId, title: known.title || 'Chat' };
  setInterval(poll, 5000);
}

// ── actions ─────────────────────────────────────────────────────────────────
/** Returns text to put back into the composer (prompt-template commands), or null. */
export async function send(text) {
  if (!text || !store.chatId) return null;
  const cmd = text.startsWith('/') && store.commands.find(c => c.name === text.split(/\s+/)[0]);
  if (cmd && cmd.action.startsWith('prompt:')) return cmd.action.slice(7);
  // while a menu is open the server takes the input (number, b, /back) instead of the agent
  const isCmd = !!cmd || store.menuOpen;
  // slash commands run on the server through the same endpoint and are allowed during a run
  if (!isCmd && store.running) return null;
  if (!isCmd) {
    addMsg('user', text);
    store.pendingUser = text; store.running = true;
  }
  store.sending = true;
  try {
    const r = await post(`/api/chat/${store.chatId}/messages`, { text });
    if (r && r.command) {
      store.menuOpen = !!r.menu;
      if (r.switchTo || r.refresh) await openChat(r.switchTo || store.chatId);
      if (r.output) addMsg('notice', r.output);
    }
  } catch (e) {
    if (!isCmd) { store.items.pop(); store.pendingUser = null; store.running = false; }
    toast(e.message, 'error');
    return text;
  } finally { store.sending = false; }
  return null;
}

export const setMode = guard(async mode => {
  await post(`/api/chat/${store.chatId}/mode`, { mode });
  store.mode = mode; toast('Mode: ' + mode);
});

// a run_js call of the model is in flight: the toolbar asks how to stop the script
export const scriptRunning = () => store.items.some(it => it.kind === 'tools'
  && it.tools.some(t => t.name === 'run_js' && t.status === 'run'));
export const cancelRun = guard(async (kill = false) => {
  if (store.chatId) await post(`/api/chat/${store.chatId}/cancel${kill ? '?kill=true' : ''}`);
});

export const searchFiles = async q => {
  try { return await api(`/api/chat/${store.chatId}/files?q=${encodeURIComponent(q)}`); } catch (e) { return []; }
};

// ── approvals ───────────────────────────────────────────────────────────────
function enqueueApproval(a) {
  if (!store.approvals.some(x => x.id === a.id)) store.approvals.push(a);
}

/**
 * Brings the queue in line with the server's pending approvals: the ones answered elsewhere go, missed ones are
 * added. A queued entry is kept as it is (its event may carry more, e.g. writeDir).
 */
async function syncApprovals(id) {
  let pending;
  try { pending = await api(`/api/chat/${id}/approvals`); } catch (e) { return; /* next reconnect or gap retries */ }
  if (store.chatId !== id || !Array.isArray(pending)) return;
  const ids = new Set(pending.map(p => p.id));
  store.approvals = store.approvals.filter(x => ids.has(x.id));
  pending.forEach(enqueueApproval);
}

/**
 * Returns the server's error text when it refused the answer as invalid (400, e.g. a typed folder outside the
 * project): the approval then stays open for a new choice. Otherwise the approval leaves the queue and null is returned.
 */
export async function resolveApproval(a, approved, editedText, remember = false, rememberDir = false) {
  try {
    await post(`/api/chat/${store.chatId}/approvals/${a.id}`, { approved, editedText: approved ? editedText ?? null : null,
      remember: approved && remember, rememberDir: approved && rememberDir });
  } catch (e) {
    toast(e.message, 'error');
    if (e.status === 400) return e.message;
  }
  store.approvals = store.approvals.filter(x => x.id !== a.id);
  return null;
}
