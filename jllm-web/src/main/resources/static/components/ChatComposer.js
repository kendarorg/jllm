import {computed, nextTick, onMounted, ref, watch} from 'vue';
import {searchFiles, send, store} from '../store.js';

export default {
  setup() {
    const ta = ref(null);
    const text = ref('');
    const popup = ref({ items: [], idx: 0, start: 0 });
    let fileTimer;

    const disabled = computed(() => !store.chatId);
    const canSend = computed(() => store.chatId && !store.running);

    const hide = () => { popup.value = { items: [], idx: 0, start: 0 }; };

    function onInput() {
      const upto = text.value.slice(0, ta.value.selectionStart);
      const slash = /^\/(\S*)$/.exec(upto);
      if (slash) {
        const q = '/' + slash[1];
        popup.value = {
          idx: 0, start: 0,
          items: store.commands.filter(c => c.name.startsWith(q))
            .map(c => ({ label: c.name, hint: c.description, insert: c.name })),
        };
        return;
      }
      const at = /(^|\s)@([^\s@]*)$/.exec(upto);
      if (at && store.chatId) {
        const start = upto.length - at[2].length - 1;
        clearTimeout(fileTimer);
        fileTimer = setTimeout(async () => {
          const files = await searchFiles(at[2]);
          popup.value = { idx: 0, start, items: files.map(f => ({ label: f, insert: '@' + f })) };
        }, 150);
        return;
      }
      hide();
    }

    function pick(i) {
      const p = popup.value, it = p.items[i];
      if (!it) return;
      const caret = ta.value.selectionStart;
      text.value = text.value.slice(0, p.start) + it.insert + ' ' + text.value.slice(caret);
      const pos = p.start + it.insert.length + 1;
      hide();
      nextTick(() => { ta.value.setSelectionRange(pos, pos); ta.value.focus(); });
    }

    function move(d) {
      const p = popup.value;
      p.idx = (p.idx + d + p.items.length) % p.items.length;
    }

    function onKey(e) {
      const open = popup.value.items.length > 0;
      if (open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); move(e.key === 'ArrowDown' ? 1 : -1); return; }
      if (open && (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey))) { e.preventDefault(); pick(popup.value.idx); return; }
      if (e.key === 'Escape') { hide(); return; }
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
    }

    async function submit() {
      const t = text.value.trim();
      if (!t) return;
      text.value = '';
      hide();
      const back = await send(t); // text to restore: prompt-template command, or a failed send
      if (back) { text.value = back; ta.value.focus(); }
    }
    // entering the chat view (new chat, first open, tab switch back): the prompt is ready to type in
    const focusPrompt = () => nextTick(() => {
      if (store.chatId && store.tab === 'chat' && ta.value && !ta.value.disabled) ta.value.focus();
    });
    watch(() => [store.chatId, store.tab], focusPrompt, { flush: 'post' });
    onMounted(focusPrompt);   // the composer only exists once a chat is open, so the watcher alone misses the first open
    return { ta, text, popup, disabled, canSend, onInput, onKey, submit, pick };
  },
  template: `
  <div class="composer">
    <div class="popup" :class="{ show: popup.items.length }" role="listbox" aria-label="Suggestions">
      <div v-for="(it, i) in popup.items" :key="it.label" role="option" :aria-selected="i === popup.idx"
           @mousedown.prevent="pick(i)">{{ it.label }}<small>{{ it.hint }}</small></div>
    </div>
    <div class="composer-row">
      <textarea ref="ta" v-model="text" rows="2" aria-label="Message" :disabled="disabled"
                placeholder="Message — / for commands, @ to reference a file, Enter to send, Shift+Enter for newline"
                @input="onInput" @keydown="onKey"></textarea>
      <button class="btn-primary" :disabled="!canSend" @click="submit">Send</button>
    </div>
  </div>
`,
};
