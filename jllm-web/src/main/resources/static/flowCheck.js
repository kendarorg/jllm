import {reachable} from './flowModel.js';

const TYPES = ['task', 'action', 'decide', 'ask', 'call', 'finish'];
const KINDS = ['text', 'single', 'multi'];

/** Problems keyed `which/stepId`; `flow` holds the ones about the document as a whole (tasks, settings). */
export function emptyProblems() {
  return {byStep: {}, flow: []};
}

function add(p, which, id, msg) {
  (p.byStep[which + '/' + id] ||= []).push(msg);
}

/**
 * Quick checks done while editing, mirroring the obvious part of the server's save-time checks. The server stays the
 * authority (registered actions, roles, unattended rules): see mapServerErrors.
 */
export function checkFlow(doc) {
  const p = emptyProblems();
  if (!doc.name || !String(doc.name).trim()) p.flow.push('The flow has no name');
  for (const which of ['main', 'handler']) {
    const part = which === 'main' ? doc : (doc.onError || {start: '', steps: {}});
    const steps = part.steps || {};
    const ids = Object.keys(steps);
    if (!ids.length) { p.flow.push((which === 'main' ? 'Main flow' : 'Error handler') + ' has no steps'); continue; }
    if (!steps[part.start]) p.flow.push((which === 'main' ? 'The start step' : 'The handler start step') + " '" + (part.start || '') + "' does not exist");
    const need = (id, label, to) => { if (!steps[to]) add(p, which, id, label + (to ? " '" + to + "' does not exist" : ' is not set')); };
    for (const id of ids) {
      const s = steps[id];
      if (!TYPES.includes(s.type)) { add(p, which, id, "unknown type '" + s.type + "'"); continue; }
      switch (s.type) {
        case 'task':
          if (!s.task) add(p, which, id, 'no task chosen');
          else if (!(doc.tasks || {})[s.task]) add(p, which, id, "unknown task '" + s.task + "'");
          need(id, 'next', s.next);
          break;
        case 'action':
          if (!s.action) add(p, which, id, 'no action chosen');
          need(id, 'next', s.next);
          break;
        case 'decide': {
          if (!['java', 'llm', 'user'].includes(s.by)) add(p, which, id, "'by' must be java, llm or user");
          if (s.by === 'java' && !s.chooser) add(p, which, id, 'no chooser');
          if (s.by === 'llm' && !s.agent) add(p, which, id, 'llm decisions need an agent');
          const out = Object.entries(s.outcomes || {});
          if (!out.length) add(p, which, id, 'no outcomes');
          out.forEach(([k, v]) => { if (!steps[v]) add(p, which, id, "outcome '" + k + "' leads to " + (v ? "unknown step '" + v + "'" : 'nothing')); });
          if (s.default && !steps[s.default]) add(p, which, id, "default leads to unknown step '" + s.default + "'");
          break;
        }
        case 'ask':
          if (!KINDS.includes(s.kind)) add(p, which, id, 'kind must be text, single or multi');
          if (s.kind && s.kind !== 'text' && !(s.options && Object.keys(s.options).length)) add(p, which, id, 'needs options');
          if (s.onTimeout && !steps[s.onTimeout]) add(p, which, id, "onTimeout leads to unknown step '" + s.onTimeout + "'");
          need(id, 'next', s.next);
          break;
        case 'call':
          if (!s.flow && !(s.chooser && Array.isArray(s.allowed) && s.allowed.length)) add(p, which, id, "needs 'flow', or a chooser with allowed flows");
          need(id, 'next', s.next);
          break;
        default:
          if (which === 'handler' && !['recovered', 'retry', 'rethrow'].includes(s.outcome)) add(p, which, id, 'handler finish needs an outcome');
          if (which === 'main' && s.outcome) add(p, which, id, "'outcome' is only for error handlers");
          if (which === 'handler' && s.outcome === 'retry' && !(doc.steps || {})[s.retryStep]) add(p, which, id, 'retry points to an unknown main step');
      }
      for (const [k, a] of Object.entries(s.accepts || {})) {
        if (a && a.action === 'redirect' && !steps[a.to]) add(p, which, id, "request '" + k + "' redirects to an unknown step");
      }
      for (const k of ['retries', 'questions']) {
        if (k in s && !(Number.isInteger(s[k]) && s[k] >= 0)) add(p, which, id, k + ' must be a whole number >= 0');
      }
    }
    const seen = reachable(part);
    for (const id of ids) if (!seen.has(id)) add(p, which, id, 'not reachable from the start step (warning)');
    if (steps[part.start] && ![...seen].some(id => steps[id].type === 'finish')) {
      p.flow.push((which === 'main' ? 'No finish step can be reached from the start' : 'The handler can never finish'));
    }
  }
  for (const [name, t] of Object.entries(doc.tasks || {})) {
    if (!t.prompt) p.flow.push("task '" + name + "': no prompt");
    if (!t.instead && !t.agent) p.flow.push("task '" + name + "': no agent");
  }
  for (const k of ['retries', 'questions', 'depth']) {
    const v = (doc.limits || {})[k];
    if (v !== undefined && !(Number.isInteger(v) && v >= 0)) p.flow.push("limit '" + k + "' must be a whole number >= 0");
  }
  return p;
}

/** Spreads the validator's messages over steps ("step 'x': ..." / "onError.step 'x': ...") and the flow. */
export function mapServerErrors(errors) {
  const p = emptyProblems();
  for (const e of errors || []) {
    const m = /^(onError\.)?step '([^']*)': (.*)$/.exec(e);
    if (m) add(p, m[1] ? 'handler' : 'main', m[2], m[3]);
    else p.flow.push(e);
  }
  return p;
}

/** Merges problem sets into one (client first, server after, no duplicates). */
export function mergeProblems(...sets) {
  const out = emptyProblems();
  for (const s of sets) {
    for (const [k, v] of Object.entries(s.byStep)) {
      const l = out.byStep[k] ||= [];
      v.forEach(m => { if (!l.includes(m)) l.push(m); });
    }
    s.flow.forEach(m => { if (!out.flow.includes(m)) out.flow.push(m); });
  }
  return out;
}

export const isWarning = m => m.endsWith('(warning)');
