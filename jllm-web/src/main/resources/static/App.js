import {onMounted} from 'vue';
import {init, store} from './store.js';
import SessionList from './components/SessionList.js';
import ChatToolbar from './components/ChatToolbar.js';
import MessageStream from './components/MessageStream.js';
import ChatComposer from './components/ChatComposer.js';
import ApprovalDialog from './components/ApprovalDialog.js';
import FilesPanel from './components/FilesPanel.js';
import HistoryPanel from './components/HistoryPanel.js';

const TABS = [['chat', 'Chat'], ['files', 'Files'], ['history', 'History']];

export default {
  components: { SessionList, ChatToolbar, MessageStream, ChatComposer, ApprovalDialog, FilesPanel, HistoryPanel },
  setup() {
    onMounted(init);
    function onKey(e) {
      const i = TABS.findIndex(t => t[0] === store.tab);
      const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
      if (n === null) return;
      store.tab = TABS[(n + TABS.length) % TABS.length][0];
      e.preventDefault();
    }
    return { store, TABS, onKey };
  },
  template: `
  <header>
    <h1><span class="logo">⬡</span> jllm</h1>
  </header>
  <div class="chat-layout">
    <SessionList />
    <section class="chat-main" aria-label="Workspace">
      <div v-if="!store.chatId" class="empty">Create or select a chat to begin.</div>
      <template v-else>
        <div class="tabs" role="tablist" aria-label="Chat views" @keydown="onKey">
          <button v-for="t in TABS" :key="t[0]" role="tab" class="tab" :class="{ active: store.tab === t[0] }"
                  :aria-selected="store.tab === t[0]" :tabindex="store.tab === t[0] ? 0 : -1"
                  @click="store.tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-show="store.tab === 'chat'" class="tabpane" role="tabpanel">
          <ChatToolbar />
          <MessageStream />
          <ChatComposer />
        </div>
        <div v-if="store.tab === 'files'" class="tabpane" role="tabpanel"><FilesPanel /></div>
        <div v-if="store.tab === 'history'" class="tabpane" role="tabpanel"><HistoryPanel /></div>
      </template>
    </section>
  </div>
  <ApprovalDialog />
  <div id="toast" :class="store.toast.text ? ['show', store.toast.type] : ''">{{ store.toast.text }}</div>
`,
};
