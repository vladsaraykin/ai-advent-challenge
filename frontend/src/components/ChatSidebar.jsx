import { strategyNames } from './StrategyPanel'
import { useEffect, useState } from 'react'

export default function ChatSidebar({ agents, agentId, chats, chatId, disabled, activeView, onView, onAgent, onChat, onCreate, onDelete, searchChats, unified = false }) {
  const [query, setQuery] = useState('')
  const [touched, setTouched] = useState(false)
  const [search, setSearch] = useState({ items: null, loading: false, error: '' })
  useEffect(() => {
    if (!touched || !searchChats) return
    const controller = new AbortController()
    let active = true
    setSearch(current => ({ ...current, loading: true, error: '' }))
    const timer = setTimeout(() => {
      searchChats(agentId, query, controller.signal).then(items => {
        if (active) setSearch({ items, loading: false, error: '' })
      }).catch(error => {
        if (active) setSearch({ items: [], loading: false, error: error.message || 'Поиск недоступен.' })
      })
    }, 300)
    return () => { active = false; clearTimeout(timer); controller.abort() }
  }, [query, touched, agentId, searchChats, chats])
  const visible = search.items ?? chats
  const ordered = []
  const visit = (chat, depth) => {
    ordered.push({ chat, depth })
    visible.filter(item => item.parentChatId === chat.id).forEach(child => visit(child, depth + 1))
  }
  visible.filter(chat => !chat.parentChatId || !visible.some(item => item.id === chat.parentChatId))
    .forEach(chat => visit(chat, 0))
  return <aside className="sidebar">
      {!unified && <><a className="brand" href="/">AI Advent<span>Агенты · MCP · RAG</span></a>
    <div className="product-nav" role="tablist" aria-label="Разделы приложения">
      <button type="button" role="tab" aria-selected={activeView === 'chat'} className={activeView === 'chat' ? 'selected' : ''}
        onClick={() => onView('chat')}>Агенты</button>
      <button type="button" role="tab" aria-selected={activeView === 'mcp'} className={activeView === 'mcp' ? 'selected' : ''}
        onClick={() => onView('mcp')}>MCP</button>
      <button type="button" role="tab" aria-selected={activeView === 'rag'} className={activeView === 'rag' ? 'selected' : ''}
        onClick={() => onView('rag')}>RAG</button>
    </div></>}
    {activeView === 'chat' && <>
    <label className="agent-select">Ваш помощник
      <select value={agentId} onChange={event => onAgent(event.target.value)} disabled={disabled}>
        {!agents.length && <option value="">Загрузка агентов…</option>}
        {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
      </select>
    </label>
    <button className="new-chat" onClick={onCreate} disabled={disabled || !agentId}>+ Новый чат</button>
    <h2>История чатов</h2>
    {searchChats && <label className="chat-search">Поиск чатов по названию
      <input type="search" value={query} maxLength={100} disabled={disabled} placeholder="Название диалога"
        onChange={event => { setTouched(true); setQuery(event.target.value) }} />
    </label>}
    {search.loading && <p role="status" className="sidebar-empty">Ищем чаты…</p>}
    {search.error && <p role="alert" className="sidebar-empty">{search.error}</p>}
    <nav aria-label="Чаты выбранного агента" aria-busy={search.loading}>
      {ordered.map(({ chat, depth }) => <div className="chat-row" key={chat.id}><button className={chat.id === chatId ? 'chat-link selected' : 'chat-link'}
        style={{ paddingLeft: 12 + Math.min(depth, 6) * 12 }}
        aria-current={chat.id === chatId ? 'page' : undefined} disabled={disabled} onClick={() => onChat(chat.id)}>
        <span>{chat.parentChatId ? '↳ ' : ''}{chat.title}</span><small>{strategyNames[chat.strategy] || 'Summary'} · {chat.messageCount} сообщ. · {new Date(chat.updatedAt).toLocaleDateString('ru-RU')}</small>
      </button><button type="button" className="delete-chat" aria-label={`Удалить чат «${chat.title}»`}
        title="Удалить чат" disabled={disabled} onClick={event => onDelete(chat, event.currentTarget)}>×</button></div>)}
      {!visible.length && !search.loading && !search.error && <p className="sidebar-empty">{touched ? 'Чаты не найдены.' : 'Здесь появятся ваши диалоги с этим агентом.'}</p>}
    </nav>
    <p className="sidebar-footer">Новая тема — новый чат.<br />Предыдущие диалоги останутся в истории.</p>
    </>}
  </aside>
}
