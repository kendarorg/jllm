import {computed, ref} from 'vue';

/** Case-insensitive match where '*' stands for any run of characters (the server's Wildcard). */
export function wildcard(pattern, text) {
  const re = '^' + pattern.toLowerCase().split('*').map(p => p.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$';
  return new RegExp(re).test(text.toLowerCase());
}
const matchesAny = (list, name) => list.some(p => wildcard(p, name));

/**
 * The grouped tree of tools, MCP servers and REST APIs of a catalog ({tools, mcp, api}).
 * mode 'grant' (role editor): v-model is the list of granted patterns, a checkbox means granted, '*' grants all.
 * mode 'toggle' (user settings): v-model is the list of disabled patterns, a checkbox means enabled.
 * A whole source is its pattern (mcp__<server>__*, <api>__*); unchecking one of its tools while the whole source is
 * selected replaces the pattern with the source's other tools.
 */
export default {
  props: {
    catalog: {type: Object, required: true},
    modelValue: {type: Array, required: true},
    mode: {type: String, default: 'grant'},
    readonly: {type: Boolean, default: false},
  },
  emits: ['update:modelValue'],
  setup(props, {emit}) {
    const filter = ref('');
    const grant = computed(() => props.mode === 'grant');
    const all = computed(() => grant.value && props.modelValue.includes('*'));
    const shown = name => !filter.value || name.toLowerCase().includes(filter.value.toLowerCase());

    const groups = computed(() => {
      const out = {};
      for (const t of props.catalog.tools || []) (out[t.group || 'Other'] ||= []).push(t);
      return Object.keys(out).sort().map(g => ({name: g, tools: out[g]}));
    });
    const sources = computed(() => [
      ...(props.catalog.mcp || []).map(s => ({...s, kind: 'MCP server'})),
      ...(props.catalog.api || []).map(s => ({...s, kind: 'REST API'})),
    ]);

    /** Selected: granted (grant mode) or disabled (toggle mode). */
    const selected = name => matchesAny(props.modelValue, name);
    const checked = name => all.value || (grant.value ? selected(name) : !selected(name));
    const sourceChecked = s => all.value || (grant.value ? props.modelValue.includes(s.pattern)
        : !props.modelValue.includes(s.pattern));
    /** Selected only through a pattern that is not a source pattern: cannot be changed per tool here. */
    const lockedBy = name => {
      if (props.modelValue.includes(name)) return null;
      const srcPatterns = sources.value.map(s => s.pattern);
      return props.modelValue.find(p => p !== name && !srcPatterns.includes(p) && p !== '*' && wildcard(p, name)) || null;
    };

    function emitList(list) { emit('update:modelValue', [...new Set(list)]); }

    function setTool(name, wantChecked, source) {
      const select = grant.value ? wantChecked : !wantChecked;
      let list = [...props.modelValue];
      if (select) {
        list.push(name);
      } else {
        list = list.filter(p => p !== name);
        if (source && list.includes(source.pattern)) {
          list = list.filter(p => p !== source.pattern);
          list.push(...source.tools.map(t => t.name).filter(n => n !== name));
        }
      }
      emitList(list);
    }

    function setSource(s, wantChecked) {
      const select = grant.value ? wantChecked : !wantChecked;
      const names = s.tools.map(t => t.name);
      let list = props.modelValue.filter(p => p !== s.pattern && !names.includes(p));
      if (select) list.push(s.pattern);
      emitList(list);
    }

    function setGroup(g, wantChecked) {
      const names = g.tools.map(t => t.name);
      let list = props.modelValue.filter(p => !names.includes(p));
      if (grant.value === wantChecked) list.push(...names);
      emitList(list);
    }

    function setAll(on) {
      emitList(on ? ['*', ...props.modelValue.filter(p => p !== '*')] : props.modelValue.filter(p => p !== '*'));
    }

    /** Entries that name nothing in the catalog (kept unless removed). */
    const unknown = computed(() => {
      const names = [];
      (props.catalog.tools || []).forEach(t => names.push(t.name));
      sources.value.forEach(s => { names.push(s.pattern); s.tools.forEach(t => names.push(t.name)); });
      return props.modelValue.filter(p => p !== '*' && !names.includes(p) && !names.some(n => wildcard(p, n)));
    });
    const remove = p => emitList(props.modelValue.filter(x => x !== p));

    return {filter, grant, all, groups, sources, shown, checked, sourceChecked, lockedBy, setTool, setSource,
      setGroup, setAll, unknown, remove};
  },
  template: `
  <div class="toolpicker">
    <div class="tp-head">
      <input type="text" v-model="filter" placeholder="Filter tools" aria-label="Filter tools" />
      <label v-if="grant" class="tp-check tp-all">
        <input type="checkbox" :checked="all" :disabled="readonly" @change="setAll($event.target.checked)" />
        All tools (*)
      </label>
    </div>
    <fieldset class="tp-section" :disabled="readonly || all">
      <legend>Tools</legend>
      <div v-for="g in groups" :key="g.name" class="tp-group" v-show="g.tools.some(t => shown(t.name))">
        <div class="tp-group-head">
          <span>{{ g.name }}</span>
          <button type="button" class="btn-muted" @click="setGroup(g, true)">{{ grant ? 'grant all' : 'all on' }}</button>
          <button type="button" class="btn-muted" @click="setGroup(g, false)">{{ grant ? 'none' : 'all off' }}</button>
        </div>
        <label v-for="t in g.tools" :key="t.name" v-show="shown(t.name)" class="tp-check" :title="t.description">
          <input type="checkbox" :checked="checked(t.name)" :disabled="!!lockedBy(t.name)"
                 @change="setTool(t.name, $event.target.checked)" />
          <code>{{ t.name }}</code> <small class="tp-kind">{{ t.kind }}</small>
          <small v-if="lockedBy(t.name)" class="tp-note">via {{ lockedBy(t.name) }}</small>
        </label>
      </div>
      <p v-if="!groups.length" class="empty">No tools.</p>
    </fieldset>
    <fieldset class="tp-section" :disabled="readonly || all">
      <legend>MCP servers and REST APIs</legend>
      <div v-for="s in sources" :key="s.pattern" class="tp-group">
        <div class="tp-group-head">
          <label class="tp-check tp-source">
            <input type="checkbox" :checked="sourceChecked(s)" @change="setSource(s, $event.target.checked)" />
            <b>{{ s.name }}</b> <small class="tp-kind">{{ s.kind }}</small> <code class="tp-pattern">{{ s.pattern }}</code>
          </label>
          <span class="tp-status" :class="s.error ? 'err' : s.enabled ? 'on' : 'off'">
            {{ s.error ? 'error: ' + s.error : s.enabled ? 'enabled' : 'disabled' }}</span>
        </div>
        <label v-for="t in s.tools" :key="t.name" v-show="shown(t.name)" class="tp-check tp-indent" :title="t.description">
          <input type="checkbox" :checked="checked(t.name)"
                 @change="setTool(t.name, $event.target.checked, s)" />
          <code>{{ t.name }}</code> <small class="tp-kind">{{ t.kind }}</small>
        </label>
        <p v-if="!s.tools.length" class="tp-indent empty">No tools known (source off or unreachable); the whole-source entry still applies.</p>
      </div>
      <p v-if="!sources.length" class="empty">None configured.</p>
    </fieldset>
    <div v-if="unknown.length" class="tp-unknown">
      <span>Unknown entries:</span>
      <span v-for="p in unknown" :key="p" class="chip"><code>{{ p }}</code>
        <button v-if="!readonly" type="button" class="copy-btn" @click="remove(p)" :aria-label="'Remove ' + p">✕</button></span>
    </div>
  </div>
`,
};
