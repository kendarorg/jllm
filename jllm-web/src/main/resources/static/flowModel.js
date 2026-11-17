/**
 * Pure helpers over a flow document (the JSON of docs/guides/workflow.md). No Vue, no DOM: the designer edits the document in
 * place, these functions only know where its references are. A "part" is the object holding `start` and `steps`: the
 * document itself for the main flow, `doc.onError` for the error handler.
 */

export const STEP_TYPES = ['task', 'action', 'decide', 'ask', 'call', 'finish'];

export const TYPE_LABEL = {
  task: 'Task', action: 'Action', decide: 'Decide', ask: 'Ask', call: 'Call', finish: 'Finish',
};

/** Single letter shown next to the shape so the type never relies on colour alone. */
export const TYPE_MARK = {task: 'T', action: 'A', decide: '?', ask: 'Q', call: 'C', finish: '■'};

export function newFlow() {
  return {name: '', start: 'end', steps: {end: {type: 'finish'}}};
}

/** The part for `which` ('main' or 'handler'); the handler part is created when missing. */
export function partOf(doc, which) {
  if (which === 'handler') {
    if (!doc.onError) doc.onError = {start: 'fail', steps: {fail: {type: 'finish', outcome: 'rethrow'}}};
    if (!doc.onError.steps) doc.onError.steps = {};
    return doc.onError;
  }
  if (!doc.steps) doc.steps = {};
  return doc;
}

/** @returns {object[]} one edge per reference to another step; `to` may name a step that does not exist */
export function edgesOf(part) {
  const out = [];
  const steps = part.steps || {};
  for (const [id, s] of Object.entries(steps)) {
    if (!s || typeof s !== 'object') continue;
    const add = (to, label, kind) => { if (to) out.push({from: id, to: String(to), label, kind}); };
    if (s.type === 'decide') {
      for (const [k, v] of Object.entries(s.outcomes || {})) add(v, k, 'outcome');
      add(s.default, 'default', 'default');
    } else if (s.type !== 'finish') {
      add(s.next, '', 'next');
    }
    if (s.type === 'ask') add(s.onTimeout, 'timeout', 'timeout');
    for (const [k, a] of Object.entries(s.accepts || {})) if (a && a.action === 'redirect') add(a.to, k, 'redirect');
  }
  return out;
}

/** Ids reachable from the start step. */
export function reachable(part) {
  const edges = edgesOf(part);
  const seen = new Set();
  const todo = part.start ? [part.start] : [];
  while (todo.length) {
    const id = todo.pop();
    if (seen.has(id) || !(part.steps || {})[id]) continue;
    seen.add(id);
    edges.filter(e => e.from === id).forEach(e => todo.push(e.to));
  }
  return seen;
}

/** Ids of the steps that can run before `id` (used for the `{{steps.x}}` slot suggestions). */
export function preceding(part, id) {
  const edges = edgesOf(part);
  const seen = new Set();
  const todo = [id];
  while (todo.length) {
    const cur = todo.pop();
    edges.filter(e => e.to === cur).forEach(e => {
      if (!seen.has(e.from)) { seen.add(e.from); todo.push(e.from); }
    });
  }
  seen.delete(id);
  return seen;
}

export function uniqueId(steps, base) {
  const b = base.replace(/[^A-Za-z0-9_-]/g, '') || 'step';
  if (!steps[b]) return b;
  for (let i = 2; ; i++) if (!steps[b + i]) return b + i;
}

/** A new step with the fields its type needs, not connected to anything. */
export function newStep(type, handler = false) {
  switch (type) {
    case 'task': return {type, task: '', next: ''};
    case 'action': return {type, action: '', next: ''};
    case 'decide': return {type, by: 'user', question: '', outcomes: {}};
    case 'ask': return {type, kind: 'text', text: '', next: ''};
    case 'call': return {type, flow: '', next: ''};
    default: return handler ? {type: 'finish', outcome: 'recovered'} : {type: 'finish'};
  }
}

/**
 * Adds a step of `type`. When `afterId` names a step with a single `next`, the new step is spliced in between (it
 * takes the old `next`); otherwise it is added unconnected and the check lists what is missing.
 * @returns {string} the id of the new step
 */
export function insertStep(part, type, afterId, handler = false) {
  const steps = part.steps;
  const id = uniqueId(steps, type);
  const step = newStep(type, handler);
  const after = afterId ? steps[afterId] : null;
  if (after && after.type !== 'finish' && after.type !== 'decide' && 'next' in after) {
    if (type !== 'finish' && type !== 'decide') step.next = after.next || '';
    after.next = id;
  } else if (after && (after.type === 'task' || after.type === 'action' || after.type === 'call' || after.type === 'ask')) {
    after.next = id;
  }
  steps[id] = step;
  if (!part.start || !steps[part.start]) part.start = id;
  return id;
}

/** Removes a step; references to it are left in place so the check reports them. */
export function removeStep(part, id) {
  delete part.steps[id];
  if (part.start === id) part.start = '';
}

/** Renames a step id and every reference to it (start, next, outcomes, default, onTimeout, redirects, retryStep). */
export function renameStep(doc, which, from, to) {
  const part = partOf(doc, which);
  if (!to || from === to || part.steps[to]) return false;
  const steps = {};
  for (const [k, v] of Object.entries(part.steps)) steps[k === from ? to : k] = v;
  part.steps = steps;
  if (part.start === from) part.start = to;
  const fix = o => { if (o && o === from) return to; return o; };
  const fixAccepts = acc => {
    for (const a of Object.values(acc || {})) if (a && a.action === 'redirect') a.to = fix(a.to);
  };
  for (const s of Object.values(part.steps)) {
    if (s.next === from) s.next = to;
    if (s.default === from) s.default = to;
    if (s.onTimeout === from) s.onTimeout = to;
    for (const [k, v] of Object.entries(s.outcomes || {})) if (v === from) s.outcomes[k] = to;
    fixAccepts(s.accepts);
  }
  if (which === 'main') {
    fixAccepts(doc.accepts);
    for (const s of Object.values((doc.onError && doc.onError.steps) || {})) {
      if (s.retryStep === from) s.retryStep = to;
    }
  }
  return true;
}

/** Sets or removes (empty value) an optional field. */
export function setOpt(obj, key, value) {
  if (value === '' || value === null || value === undefined) delete obj[key];
  else obj[key] = value;
}

/** Parses a whole number field; empty removes the key, anything else invalid is left for the check to report. */
export function setNum(obj, key, text) {
  if (text === '' || text === null || text === undefined) { delete obj[key]; return; }
  const n = Number(text);
  obj[key] = Number.isInteger(n) ? n : text;
}

/** Slot suggestions for text fields of a step: inputs of the flow, earlier steps, requests, error info. */
export function slotsFor(doc, which, id) {
  const out = [];
  const part = partOf(doc, which);
  const inputs = new Set();
  const m = JSON.stringify(doc).matchAll(/\{\{\s*input\.([A-Za-z0-9_]+)/g);
  for (const x of m) inputs.add(x[1]);
  inputs.forEach(i => out.push('{{input.' + i + '}}'));
  for (const p of preceding(part, id)) out.push('{{steps.' + p + '}}');
  if (which === 'handler') out.push('{{error.kind}}', '{{error.message}}');
  out.push('{{user}}', '{{runId}}');
  return out;
}

/** Names of the `{{input.x}}` slots used anywhere in the document. */
export function inputsOf(doc) {
  const out = new Set();
  for (const x of JSON.stringify(doc).matchAll(/\{\{\s*input\.([A-Za-z0-9_]+)/g)) out.add(x[1]);
  return [...out].sort();
}

/** Global flows called by `doc` (for the "who calls this flow" warning before deleting a global flow). */
export function calledFlows(doc) {
  const out = new Set();
  const scan = steps => {
    for (const s of Object.values(steps || {})) {
      if (s.type !== 'call') continue;
      if (s.flow) out.add((s.scope || 'personal') + '/' + s.flow);
      (s.allowed || []).forEach(n => out.add((s.scope || 'personal') + '/' + n));
    }
  };
  scan(doc.steps);
  scan(doc.onError && doc.onError.steps);
  return out;
}
