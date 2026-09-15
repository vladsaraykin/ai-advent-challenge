import { strategyNames } from './StrategyPanel'

export default function ChatSidebar({ agents, agentId, chats, chatId, disabled, onAgent, onChat, onCreate, onDelete }) {
  const ordered = []
  const visit = (chat, depth) => {
    ordered.push({ chat, depth })
    chats.filter(item => item.parentChatId === chat.id).forEach(child => visit(child, depth + 1))
  }
  chats.filter(chat => !chat.parentChatId || !chats.some(item => item.id === chat.parentChatId))
    .forEach(chat => visit(chat, 0))
  return <aside className="sidebar">
      <a className="brand" href="/">Мои агенты<span>AI Advent · День 11</span></a>
    <label className="agent-select">Ваш помощник
      <select value={agentId} onChange={event => onAgent(event.target.value)} disabled={disabled}>
        {!agents.length && <option value="">Загрузка агентов…</option>}
        {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
      </select>
    </label>
    <button className="new-chat" onClick={onCreate} disabled={disabled || !agentId}>+ Новый чат</button>
    <h2>История чатов</h2>
    <nav aria-label="Чаты выбранного агента">
      {ordered.map(({ chat, depth }) => <div className="chat-row" key={chat.id}><button className={chat.id === chatId ? 'chat-link selected' : 'chat-link'}
        style={{ paddingLeft: 12 + Math.min(depth, 6) * 12 }}
        aria-current={chat.id === chatId ? 'page' : undefined} disabled={disabled} onClick={() => onChat(chat.id)}>
        <span>{chat.parentChatId ? '↳ ' : ''}{chat.title}</span><small>{strategyNames[chat.strategy] || 'Summary'} · {chat.messageCount} сообщ. · {new Date(chat.updatedAt).toLocaleDateString('ru-RU')}</small>
      </button><button type="button" className="delete-chat" aria-label={`Удалить чат «${chat.title}»`}
        title="Удалить чат" disabled={disabled} onClick={event => onDelete(chat, event.currentTarget)}>×</button></div>)}
      {!chats.length && <p className="sidebar-empty">Здесь появятся ваши диалоги с этим агентом.</p>}
    </nav>
    <p className="sidebar-footer">Новая тема — новый чат.<br />Предыдущие диалоги останутся в истории.</p>
  </aside>
}
