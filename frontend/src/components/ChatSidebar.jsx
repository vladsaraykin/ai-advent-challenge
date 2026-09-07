export default function ChatSidebar({ agents, agentId, chats, chatId, disabled, onAgent, onChat, onCreate }) {
  return <aside className="sidebar">
    <a className="brand" href="/">Мои агенты<span>AI Advent · День 6</span></a>
    <label className="agent-select">Ваш помощник
      <select value={agentId} onChange={event => onAgent(event.target.value)} disabled={disabled}>
        {!agents.length && <option value="">Загрузка агентов…</option>}
        {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
      </select>
    </label>
    <button className="new-chat" onClick={onCreate} disabled={disabled || !agentId}>+ Новый чат</button>
    <h2>История чатов</h2>
    <nav aria-label="Чаты выбранного агента">
      {chats.map(chat => <button className={chat.id === chatId ? 'chat-link selected' : 'chat-link'} key={chat.id}
        aria-current={chat.id === chatId ? 'page' : undefined} disabled={disabled} onClick={() => onChat(chat.id)}>
        <span>{chat.title}</span><small>{chat.messageCount} сообщ. · {new Date(chat.updatedAt).toLocaleDateString('ru-RU')}</small>
      </button>)}
      {!chats.length && <p className="sidebar-empty">Здесь появятся ваши диалоги с этим агентом.</p>}
    </nav>
    <p className="sidebar-footer">Новая тема — новый чат.<br />Предыдущие диалоги останутся в истории.</p>
  </aside>
}
