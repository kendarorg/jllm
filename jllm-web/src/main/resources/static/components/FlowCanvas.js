import {computed, nextTick, onMounted, ref, watch} from '../vendor/vue.esm-browser.prod.js';
import {layout, NODE_H, NODE_W} from '../flowLayout.js';
import {STEP_TYPES, TYPE_LABEL, TYPE_MARK} from '../flowModel.js';

const short = (s, n = 22) => (s && s.length > n ? s.slice(0, n - 1) + '…' : s || '');

/**
 * The structure of one part of a flow (main flow or error handler) as an SVG graph, with a palette to add steps and a
 * plain outline of the same steps for keyboard and screen reader use. The document is not changed here: every action is
 * emitted (`select`, `insert`, `remove`, `start`).
 */
export default {
  props: {
    part: {type: Object, required: true},
    selected: {type: String, default: ''},
    marks: {type: Object, default: () => ({})},   // step id -> 'error' | 'warn'
    readOnly: {type: Boolean, default: false},
    fitKey: {type: String, default: ''},
    trail: {type: Array, default: () => []},      // step ids of a run, in the order they ran
    active: {type: String, default: ''},          // the step a run is at now
  },
  emits: ['select', 'insert', 'remove', 'start'],
  setup(props, {emit}) {
    const svg = ref(null);
    const z = ref(1), px = ref(0), py = ref(0);
    const g = computed(() => layout(props.part));
    const ids = computed(() => Object.keys(props.part.steps || {}));
    // run overlay: first visit order of each step, and the edges walked between consecutive steps
    const order = computed(() => {
      const o = {};
      props.trail.forEach(id => { if (!(id in o)) o[id] = Object.keys(o).length + 1; });
      return o;
    });
    const walked = computed(() => {
      const w = new Set();
      for (let i = 1; i < props.trail.length; i++) w.add(props.trail[i - 1] + '>' + props.trail[i]);
      return w;
    });

    function fit() {
      const el = svg.value;
      if (!el) return;
      const cw = el.clientWidth || 600, ch = el.clientHeight || 300;
      const nz = Math.min(1, cw / g.value.width, ch / g.value.height);
      z.value = Math.max(0.3, nz);
      px.value = Math.max(0, (cw - g.value.width * z.value) / 2);
      py.value = 0;
    }
    onMounted(() => nextTick(fit));
    watch(() => props.fitKey, () => nextTick(fit));

    function zoom(f, cx, cy) {
      const el = svg.value;
      const mx = cx ?? (el ? el.clientWidth / 2 : 0), my = cy ?? (el ? el.clientHeight / 2 : 0);
      const nz = Math.min(2.5, Math.max(0.25, z.value * f));
      px.value = mx - (mx - px.value) * (nz / z.value);
      py.value = my - (my - py.value) * (nz / z.value);
      z.value = nz;
    }
    function wheel(e) {
      const r = svg.value.getBoundingClientRect();
      zoom(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top);
    }
    let drag = null;
    function down(e) {
      if (e.target.closest('.fn')) return;
      drag = {x: e.clientX, y: e.clientY, px: px.value, py: py.value};
      svg.value.setPointerCapture(e.pointerId);
    }
    function move(e) {
      if (!drag) return;
      px.value = drag.px + e.clientX - drag.x;
      py.value = drag.py + e.clientY - drag.y;
    }
    function up() { drag = null; }

    function key(e, id) {
      if (e.key === 'Enter' || e.key === ' ') { emit('select', id); e.preventDefault(); return; }
      if ((e.key === 'Delete' || e.key === 'Backspace') && !props.readOnly) { emit('remove', id); e.preventDefault(); return; }
      const out = g.value.edges.filter(x => x.from === id && g.value.nodes[x.to]).map(x => x.to);
      const inn = g.value.edges.filter(x => x.to === id && g.value.nodes[x.from]).map(x => x.from);
      const me = g.value.nodes[id];
      const col = ids.value.filter(i => g.value.nodes[i] && g.value.nodes[i].col === me.col)
        .sort((a, b) => g.value.nodes[a].row - g.value.nodes[b].row);
      const at = col.indexOf(id);
      const to = e.key === 'ArrowRight' ? out[0] : e.key === 'ArrowLeft' ? inn[0]
        : e.key === 'ArrowDown' ? col[at + 1] : e.key === 'ArrowUp' ? col[at - 1] : null;
      if (to) {
        e.preventDefault();
        emit('select', to);
        nextTick(() => {
          const el = svg.value && svg.value.querySelector('[data-id="' + CSS.escape(to) + '"]');
          if (el) el.focus();
        });
      }
    }
    const sub = s => s.type === 'task' ? s.task : s.type === 'action' ? s.action
      : s.type === 'decide' ? 'by ' + (s.by || '?') + (s.chooser ? ': ' + s.chooser : '')
        : s.type === 'ask' ? s.kind + (s.who ? ' · ' + s.who : '')
          : s.type === 'call' ? (s.flow || s.chooser || '') : s.outcome || '';
    const tr = computed(() => 'translate(' + px.value + ',' + py.value + ') scale(' + z.value + ')');
    const diamond = n => (n.x + NODE_W / 2) + ',' + n.y + ' ' + (n.x + NODE_W) + ',' + (n.y + NODE_H / 2) + ' '
      + (n.x + NODE_W / 2) + ',' + (n.y + NODE_H) + ' ' + n.x + ',' + (n.y + NODE_H / 2);
    const entry = computed(() => {
      const n = g.value.nodes[props.part.start];
      return n ? 'M' + (n.x - 26) + ',' + (n.y + NODE_H / 2) + ' L' + (n.x - 2) + ',' + (n.y + NODE_H / 2) : '';
    });
    const radius = t => t === 'finish' ? 28 : t === 'action' ? 3 : 10;
    return {order, walked, svg, g, ids, tr, fit, zoom, wheel, down, move, up, key, sub, short, diamond, entry, radius, STEP_TYPES,
      TYPE_LABEL, TYPE_MARK, NODE_W, NODE_H};
  },
  template: `
  <div class="fc">
    <div class="fc-bar" role="toolbar" aria-label="Flow structure">
      <span class="fc-add">Add after the selected step:</span>
      <button v-for="t in STEP_TYPES" :key="t" type="button" class="btn-sm" :disabled="readOnly" @click="$emit('insert', t)">
        + {{ TYPE_LABEL[t] }}</button>
      <span class="fc-sep"></span>
      <button type="button" class="btn-sm" :disabled="readOnly || !selected || part.start === selected"
              @click="$emit('start', selected)">Set as start</button>
      <button type="button" class="btn-danger" :disabled="readOnly || !selected" @click="$emit('remove', selected)">Delete step</button>
      <span class="fc-sep"></span>
      <button type="button" class="btn-icon" aria-label="Zoom out" @click="zoom(1 / 1.2)">−</button>
      <button type="button" class="btn-icon" aria-label="Zoom in" @click="zoom(1.2)">+</button>
      <button type="button" class="btn-sm" @click="fit">Fit</button>
    </div>
    <svg ref="svg" class="fc-svg" role="group" aria-label="Flow graph" @wheel.prevent="wheel"
         @pointerdown="down" @pointermove="move" @pointerup="up" @pointercancel="up" @click.self="$emit('select', '')">
      <defs>
        <marker id="fc-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" class="fc-arrow" />
        </marker>
      </defs>
      <g :transform="tr">
        <path v-if="entry" class="fc-entry" :d="entry" marker-end="url(#fc-arrow)" />
        <g v-for="(e, i) in g.edges" :key="'e' + i" class="fc-edge" :class="[e.kind, {dashed: e.dashed, walked: walked.has(e.from + '>' + e.to)}]">
          <path v-if="e.d" :d="e.d" marker-end="url(#fc-arrow)" />
          <text v-if="e.d && e.label" :x="e.lx" :y="e.ly" text-anchor="middle">{{ short(e.label, 18) }}</text>
        </g>
        <g v-for="id in ids" :key="id" class="fn" :class="['fn-' + part.steps[id].type, {sel: id === selected, visited: order[id], active: id === active}, marks[id]]"
           :data-id="id" tabindex="0" role="button"
           :aria-label="id + ', ' + TYPE_LABEL[part.steps[id].type] + (part.start === id ? ', start' : '') + (order[id] ? ', run step ' + order[id] : '') + (id === active ? ', run is here' : '') + (marks[id] ? ', has problems' : '')"
           :aria-pressed="id === selected" @click.stop="$emit('select', id)" @keydown="key($event, id)">
          <template v-if="g.nodes[id]">
            <polygon v-if="part.steps[id].type === 'decide'" class="fn-shape" :points="diamond(g.nodes[id])" />
            <rect v-else class="fn-shape" :x="g.nodes[id].x" :y="g.nodes[id].y" :width="NODE_W" :height="NODE_H"
                  :rx="radius(part.steps[id].type)" />
            <rect v-if="part.steps[id].type === 'call'" class="fn-inner" :x="g.nodes[id].x + 5" :y="g.nodes[id].y + 5"
                  :width="NODE_W - 10" :height="NODE_H - 10" rx="6" />
            <text class="fn-mark" :x="g.nodes[id].x + (part.steps[id].type === 'decide' ? NODE_W / 2 : 14)"
                  :y="g.nodes[id].y + (part.steps[id].type === 'decide' ? 14 : 18)" text-anchor="middle">{{ TYPE_MARK[part.steps[id].type] || '' }}</text>
            <text class="fn-id" :x="g.nodes[id].x + NODE_W / 2" :y="g.nodes[id].y + NODE_H / 2 - 1" text-anchor="middle">{{ short(id, 16) }}</text>
            <text class="fn-sub" :x="g.nodes[id].x + NODE_W / 2" :y="g.nodes[id].y + NODE_H / 2 + 14" text-anchor="middle">{{ short(sub(part.steps[id]), 22) }}</text>
            <g v-if="order[id]" class="fn-trail">
              <circle :cx="g.nodes[id].x + NODE_W - 6" :cy="g.nodes[id].y + NODE_H - 6" r="9" />
              <text :x="g.nodes[id].x + NODE_W - 6" :y="g.nodes[id].y + NODE_H - 2" text-anchor="middle">{{ order[id] }}</text>
            </g>
            <circle v-if="marks[id]" class="fn-flag" :cx="g.nodes[id].x + NODE_W - 6" :cy="g.nodes[id].y + 6" r="5" />
          </template>
        </g>
      </g>
    </svg>
    <details class="fc-outline">
      <summary>Outline of the steps</summary>
      <ol>
        <li v-for="id in ids" :key="id">
          <button type="button" class="btn-link" :aria-current="id === selected ? 'true' : null" @click="$emit('select', id)">
            {{ id }}</button>
          <small class="muted"> {{ TYPE_LABEL[part.steps[id].type] }}<template v-if="part.start === id"> · start</template>
            <template v-if="marks[id]"> · {{ marks[id] === 'error' ? 'problems' : 'warnings' }}</template></small>
        </li>
      </ol>
    </details>
  </div>
`,
};
