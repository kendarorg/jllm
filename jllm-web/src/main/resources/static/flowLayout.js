import {edgesOf} from './flowModel.js';

export const NODE_W = 150;
export const NODE_H = 56;
const GAP_X = 80;
const GAP_Y = 34;
const PAD = 24;

/**
 * Layered left to right layout of one part of a flow. Pure: same part, same drawing.
 * Column = shortest distance from the start step; order inside a column = order of first visit. Steps the start cannot
 * reach are placed after the others, each group starting its own column band. Edges that go back (or stay in the
 * column) loop below the drawing.
 * @returns {{nodes: Object, edges: object[], width: number, height: number}}
 */
export function layout(part) {
  const steps = part.steps || {};
  const ids = Object.keys(steps);
  const edges = edgesOf(part).filter(e => steps[e.from]);
  const next = {};
  ids.forEach(id => { next[id] = []; });
  edges.forEach(e => { if (steps[e.to]) next[e.from].push(e.to); });

  const col = {};
  const order = [];
  const bfs = root => {
    const queue = [root];
    col[root] = col[root] ?? 0;
    while (queue.length) {
      const id = queue.shift();
      order.push(id);
      for (const to of next[id]) {
        if (col[to] === undefined) { col[to] = col[id] + 1; queue.push(to); }
      }
    }
  };
  if (part.start && steps[part.start]) bfs(part.start);
  for (const id of ids) if (col[id] === undefined) bfs(id);

  const rows = {};     // column -> count
  const nodes = {};
  for (const id of order) {
    const c = col[id];
    const r = rows[c] = (rows[c] ?? -1) + 1;
    nodes[id] = {x: PAD + c * (NODE_W + GAP_X), y: PAD + r * (NODE_H + GAP_Y), w: NODE_W, h: NODE_H, col: c, row: r};
  }
  const maxCol = Math.max(0, ...Object.values(col));
  const maxRow = Math.max(0, ...Object.values(rows));
  const bodyH = PAD + (maxRow + 1) * (NODE_H + GAP_Y);

  // merge parallel edges (same source, target and dashed-ness) into one drawn edge with joined labels
  const merged = new Map();
  for (const e of edges) {
    const dashed = e.kind === 'default' || e.kind === 'timeout' || e.kind === 'redirect';
    const key = e.from + '>' + e.to + '>' + dashed;
    const m = merged.get(key);
    if (m) { if (e.label) m.label = m.label ? m.label + ' / ' + e.label : e.label; } else merged.set(key, {...e, dashed});
  }

  let loops = 0;
  const out = [];
  for (const e of merged.values()) {
    const a = nodes[e.from];
    const b = nodes[e.to];
    if (!b) { out.push({...e, d: '', lx: 0, ly: 0, missing: true}); continue; }
    if (b.col > a.col) {
      const x1 = a.x + a.w, y1 = a.y + a.h / 2, x2 = b.x, y2 = b.y + b.h / 2;
      const mx = (x1 + x2) / 2;
      out.push({...e, d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, lx: mx, ly: (y1 + y2) / 2 - 4});
    } else {
      const off = 18 + loops++ * 12;
      const x1 = a.x + a.w / 2, y1 = a.y + a.h, x2 = b.x + b.w / 2, y2 = b.y + b.h;
      const low = bodyH + off;
      out.push({...e, d: `M${x1},${y1} C${x1},${low} ${x2},${low} ${x2},${y2}`, lx: (x1 + x2) / 2, ly: low * 0.75 + y1 * 0.25 + 6, back: true});
    }
  }
  return {nodes, edges: out, width: PAD * 2 + (maxCol + 1) * NODE_W + maxCol * GAP_X,
    height: bodyH + 18 + loops * 12 + PAD};
}
