import {computed, onMounted, ref, watch} from 'vue';
import {store, toast} from '../store.js';

async function call(path, opts = {}) {
  const r = await fetch(path, opts);
  if (!r.ok) {
    let msg = 'HTTP ' + r.status;
    try { const j = await r.json(); msg = j.message || j.error || msg; } catch (e) { /* not json */ }
    throw new Error(msg);
  }
  const ct = r.headers.get('content-type') || '';
  return ct.includes('json') ? r.json() : null;
}
const q = p => encodeURIComponent(p);
const fs = () => '/api/chat/' + encodeURIComponent(store.chatId) + '/fs';
const json = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export default {
  setup() {
    const path = ref('');
    const entries = ref([]);
    // dialog: { kind: 'edit'|'new-file'|'new-dir'|'rename'|'delete', entry?, value, content }
    const dlg = ref(null);
    const uploader = ref(null);

    const crumbs = computed(() => {
      const parts = path.value ? path.value.split('/') : [];
      return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join('/') }));
    });

    const notify = (text, type = 'success') => toast(text, type === 'success' ? '' : type);
    const fail = e => notify(e.message, 'error');

    async function load(p = path.value) {
      try {
        const r = await call(fs() + '?path=' + q(p));
        path.value = r.path;
        entries.value = r.entries;
      } catch (e) { fail(e); }
    }

    const size = n => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
    const when = ms => ms ? new Date(ms).toLocaleString() : '';
    const download = p => { location.href = fs() + '/download?path=' + q(p); };

    async function openEdit(e) {
      try {
        const r = await call(fs() + '/content?path=' + q(e.path));
        dlg.value = { kind: 'edit', entry: e, content: r.content, value: '' };
      } catch (err) { fail(err); }
    }
    const startNew = kind => { dlg.value = { kind, value: '', content: '' }; };
    const startRename = e => { dlg.value = { kind: 'rename', entry: e, value: e.name, content: '' }; };
    const startDelete = e => { dlg.value = { kind: 'delete', entry: e, value: '', content: '' }; };

    async function confirm() {
      const d = dlg.value;
      try {
        if (d.kind === 'edit') {
          await call(fs() + '/content', json('PUT', { path: d.entry.path, content: d.content }));
        } else if (d.kind === 'new-file') {
          await call(fs(), json('POST', { path: path.value, name: d.value.trim(), type: 'file', content: d.content }));
        } else if (d.kind === 'new-dir') {
          await call(fs(), json('POST', { path: path.value, name: d.value.trim(), type: 'dir' }));
        } else if (d.kind === 'rename') {
          await call(fs() + '/rename', json('POST', { path: d.entry.path, name: d.value.trim() }));
        } else if (d.kind === 'delete') {
          await call(fs() + '?path=' + q(d.entry.path), { method: 'DELETE' });
        }
        dlg.value = null;
        notify('Done');
        await load();
      } catch (e) { fail(e); }
    }

    async function upload(ev) {
      const files = ev.target.files;
      if (!files.length) return;
      const fd = new FormData();
      fd.append('path', path.value);
      for (const f of files) fd.append('files', f);
      try {
        await call(fs() + '/upload', { method: 'POST', body: fd });
        notify('Uploaded ' + files.length + ' file(s)');
        await load();
      } catch (e) { fail(e); }
      ev.target.value = '';
    }

    const titles = { edit: 'Edit', 'new-file': 'New file', 'new-dir': 'New directory', rename: 'Rename', delete: 'Delete' };
    onMounted(() => load(''));
    watch(() => store.chatId, () => load(''));
    return { path, entries, crumbs, dlg, uploader, titles, load, size, when, download, openEdit,
             startNew, startRename, startDelete, confirm, upload };
  },
  template: `
  <div class="files-main">
    <nav class="crumbs" aria-label="Breadcrumb">
      <button @click="load('')">root</button>
      <template v-for="c in crumbs" :key="c.path">
        <span class="sep">/</span>
        <span v-if="c.path === path" class="cur">{{ c.name }}</span>
        <button v-else @click="load(c.path)">{{ c.name }}</button>
      </template>
    </nav>
    <div class="fbar">
      <button class="btn-sm" @click="startNew('new-file')">+ File</button>
      <button class="btn-sm" @click="startNew('new-dir')">+ Directory</button>
      <button class="btn-sm" @click="uploader.click()">Upload</button>
      <input ref="uploader" type="file" multiple hidden @change="upload" />
      <button class="btn-sm" @click="download(path)">Download this dir (zip)</button>
      <button class="btn-sm" @click="load()">Refresh</button>
    </div>
    <table class="flist">
      <thead><tr><th>Name</th><th class="num">Size</th><th class="num">Modified</th><th></th></tr></thead>
      <tbody>
        <tr v-for="e in entries" :key="e.path">
          <td class="name">
            <span v-if="e.dir" class="dirchain">📁
              <template v-for="(c, i) in (e.chain && e.chain.length ? e.chain : [{ name: e.name, path: e.path }])" :key="c.path">
                <span v-if="i" class="sep">/</span><button class="dir" @click="load(c.path)">{{ c.name }}</button>
              </template>
            </span>
            <button v-else @click="openEdit(e)">📄 {{ e.name }}</button>
          </td>
          <td class="num">{{ e.dir ? '' : size(e.size) }}</td>
          <td class="num">{{ when(e.modified) }}</td>
          <td class="acts">
            <button v-if="!e.dir" class="btn-sm" @click="openEdit(e)">Edit</button>
            <button class="btn-sm" @click="download(e.path)">{{ e.dir ? 'Zip' : 'Download' }}</button>
            <button class="btn-sm" @click="startRename(e)">Rename</button>
            <button class="btn-danger" @click="startDelete(e)">Delete</button>
          </td>
        </tr>
        <tr v-if="!entries.length"><td colspan="4" class="empty">Empty directory</td></tr>
      </tbody>
    </table>
  </div>
  <div v-if="dlg" class="fs-overlay" role="dialog" aria-modal="true" @keydown.esc="dlg = null">
    <div class="fs-dialog">
      <h2>{{ titles[dlg.kind] }}<template v-if="dlg.entry"> — {{ dlg.entry.path }}</template></h2>
      <div class="fs-body">
        <p v-if="dlg.kind === 'delete'">Delete <b>{{ dlg.entry.name }}</b>{{ dlg.entry.dir ? ' and all its content' : '' }}? This cannot be undone.</p>
        <template v-else>
          <input v-if="dlg.kind !== 'edit'" type="text" v-model="dlg.value" placeholder="Name" autofocus
                 @keydown.enter="dlg.kind !== 'new-file' && confirm()" />
          <textarea v-if="dlg.kind === 'edit' || dlg.kind === 'new-file'" v-model="dlg.content" spellcheck="false"
                    :placeholder="dlg.kind === 'new-file' ? 'Content (optional)' : ''"></textarea>
        </template>
      </div>
      <div class="fs-foot">
        <button class="btn-sm" @click="dlg = null">Cancel</button>
        <button :class="dlg.kind === 'delete' ? 'btn-danger' : 'btn-primary'" @click="confirm">
          {{ dlg.kind === 'delete' ? 'Delete' : dlg.kind === 'edit' ? 'Save' : 'OK' }}</button>
      </div>
    </div>
  </div>
`,
};
