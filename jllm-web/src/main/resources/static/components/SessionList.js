import {deleteChat, newChat, openChat, store} from '../store.js';

export default {
  setup() {
    function remove(s) {
      if (confirm('Delete this chat?')) deleteChat(s.chatId);
    }
    return { store, newChat, openChat, remove };
  },
  template: `
  <aside class="sidebar" aria-label="Chat sessions">
    <div class="sidebar-head"><button class="btn-primary" style="width:100%" @click="newChat()">+ New chat</button></div>
    <ul class="session-list" role="listbox" aria-label="Sessions">
      <li v-for="s in store.sessions" :key="s.chatId" class="session-item"
          :class="{ active: s.chatId === store.chatId }" tabindex="0" role="option"
          :aria-selected="s.chatId === store.chatId"
          @click="openChat(s.chatId)" @keydown.enter="openChat(s.chatId)">
        <span class="session-title">{{ s.title || 'Chat' }}</span>
        <button class="del" :aria-label="'Delete chat ' + (s.title || '')" @click.stop="remove(s)">✕</button>
      </li>
    </ul>
  </aside>
`,
};
