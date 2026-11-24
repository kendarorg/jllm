import {reactive} from './vendor/vue.esm-browser.prod.js';
import {api, del, post, put} from './api.js';

const BASE = '/api/plugins/jllm-workflow-web';

/**
 * State of the workflow screens. `available` is false when the jllm-workflow-web plugin is not loaded (the first
 * request fails), and then no workflow control is shown anywhere.
 */
export const workflows = reactive({
  available: false,
  count: 0,            // questions waiting for the signed-in user
  waiting: [],         // run views that wait for an answer
});

export const wf = {
  flows: () => api(BASE + '/flows'),
  catalog: () => api(BASE + '/catalog'),
  validate: (scope, flow) => post(BASE + '/flows/validate', {scope, flow}),
  flow: (scope, name) => api(`${BASE}/flows/${scope}/${encodeURIComponent(name)}`),
  inputs: (scope, name) => api(`${BASE}/flows/${scope}/${encodeURIComponent(name)}/inputs`),
  save: (scope, flow) => put(`${BASE}/flows/${scope}`, flow),
  remove: (scope, name) => del(`${BASE}/flows/${scope}/${encodeURIComponent(name)}`),
  copy: (name, newName) => post(`${BASE}/flows/${encodeURIComponent(name)}/copy`, {newName}),
  start: (name, scope, input) => post(BASE + '/runs', {name, scope, input}),
  runs: () => api(BASE + '/runs'),
  run: id => api(`${BASE}/runs/${id}`),
  history: id => api(`${BASE}/runs/${id}/history`),
  answer: (id, answer) => post(`${BASE}/runs/${id}/answer`, {answer}),
  request: (id, kind, payload) => post(`${BASE}/runs/${id}/requests`, {kind, payload}),
  cancel: id => post(`${BASE}/runs/${id}/cancel`, {}),
};

export async function refreshWaiting() {
  try {
    workflows.waiting = await api(BASE + '/waiting');
    workflows.count = workflows.waiting.length;
    workflows.available = true;
  } catch (e) {
    workflows.available = false;
    workflows.count = 0;
    workflows.waiting = [];
  }
}

let es = null;
const listeners = new Set();

/** Calls fn when a run of the user changes; returns the function that stops listening. */
export function onRunChanged(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** Probes the plugin and follows its live events (a question is waiting, a run changed state). */
export async function initWorkflows() {
  await refreshWaiting();
  if (!workflows.available || es) return;
  es = new EventSource(BASE + '/events');
  es.addEventListener('waiting', e => {
    try { workflows.count = JSON.parse(e.data).count; } catch (x) { /* ignore */ }
    refreshWaiting();
  });
  es.addEventListener('run', e => {
    let d = null;
    try { d = JSON.parse(e.data); } catch (x) { /* ignore */ }
    listeners.forEach(fn => fn(d));
  });
}
