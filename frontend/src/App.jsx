import { useEffect, useRef, useState } from 'react'
import { agentApi } from './api'
import { newMessageId } from './messageId'
import MessageList from './components/MessageList'
import ChatSidebar from './components/ChatSidebar'
import MessageComposer from './components/MessageComposer'

const remember = (key, value) => { try { localStorage.setItem(key, value) } catch { /* Optional storage. */ } }
const recalled = key => { try { return localStorage.getItem(key) || '' } catch { return '' } }

export default function App({ api = agentApi }) {
  const [agents, setAgents] = useState([])
  const [agentId, setAgentId] = useState('')
  const [chats, setChats] = useState([])
  const [chat, setChat] = useState(null)
  const [drafts, setDrafts] = useState({})
  const [pending, setPending] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  const retry = useRef(null)
  const busyRef = useRef(false)
  const agent = agents.find(item => item.id === agentId)
  const draftKey = chat ? `${agentId}/${chat.id}` : agentId
  const draft = drafts[draftKey] || ''

  useEffect(() => {
    let active = true
    setLoading(true)
    api.agents().then(items => {
      if (!active) return
      setAgents(items)
      const selected = items.find(item => item.id === recalled('agent-lab.agent')) || items[0]
      if (selected) setAgentId(selected.id)
      else { setError('Нет настроенных агентов'); setLoading(false) }
    }).catch(exception => { if (active) { setError(exception.message); setLoading(false) } })
    return () => { active = false }
  }, [api, reload])

  useEffect(() => {
    if (!agentId) return
    let active = true
    setLoading(true); setError(''); setChats([]); setChat(null)
    remember('agent-lab.agent', agentId)
    api.chats(agentId).then(async items => {
      if (!active) return
      setChats(items)
      const selected = items.find(item => item.id === recalled(`agent-lab.chat.${agentId}`)) || items[0]
      if (selected) {
        const loaded = await api.chat(agentId, selected.id)
        if (active) setChat(loaded)
      }
    }).catch(exception => { if (active) setError(exception.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [agentId, api, reload])

  async function openChat(id) {
    if (busyRef.current || loading) return
    setLoading(true); setError('')
    try { setChat(await api.chat(agentId, id)); remember(`agent-lab.chat.${agentId}`, id) }
    catch (exception) { setError(exception.message) }
    finally { setLoading(false) }
  }

  function updateChat(updated) {
    setChat(updated)
    remember(`agent-lab.chat.${agentId}`, updated.id)
    setChats(current => [{ id: updated.id, title: updated.title, updatedAt: updated.updatedAt,
      messageCount: updated.messages.length }, ...current.filter(item => item.id !== updated.id)])
  }

  async function createChat() {
    if (busyRef.current || loading) return
    setLoading(true); setError('')
    try { updateChat(await api.create(agentId)) }
    catch (exception) { setError(exception.message) }
    finally { setLoading(false) }
  }

  async function send(event) {
    event.preventDefault()
    if (busyRef.current || loading || !agent) return
    const content = draft.trim()
    if (!content) { setError('Введите сообщение'); return }
    busyRef.current = true; setPending(true); setError('')
    try {
      const current = chat || await api.create(agentId)
      if (!chat) updateChat(current)
      const key = `${agentId}/${current.id}`
      setDrafts(values => ({ ...values, [draftKey]: '', [key]: draft }))
      if (!retry.current || retry.current.chatId !== current.id || retry.current.content !== content) {
        retry.current = { chatId: current.id, content, messageId: newMessageId() }
      }
      updateChat(await api.send(agentId, current.id, { messageId: retry.current.messageId, content }))
      setDrafts(values => ({ ...values, [key]: '' }))
      retry.current = null
    } catch (exception) { setError(exception.message) }
    finally { busyRef.current = false; setPending(false) }
  }

  return <main className="app-shell">
    <ChatSidebar agents={agents} agentId={agentId} chats={chats} chatId={chat?.id}
      disabled={pending || loading} onAgent={setAgentId} onChat={openChat} onCreate={createChat} />
    <section className="conversation" aria-label="Диалог с агентом">
      <header className="conversation-header"><div><h1>{agent?.name || 'Мои агенты'}</h1>
        <p>{agent?.description || 'Выберите помощника для своей задачи'}</p></div>
        {agent && <span className="model-name">{agent.model}</span>}</header>
      {loading ? <div className="loading-state" role="status">Загружаем чаты…</div>
        : <MessageList messages={chat?.messages || []} agent={agent} pending={pending} draft={draft} />}
      {error && <div className="error-banner" role="alert"><span>{error}</span>
        {!pending && <button type="button" onClick={() => { setError(''); setReload(value => value + 1) }}>Обновить</button>}</div>}
      <MessageComposer draft={draft} onChange={value => setDrafts(current => ({ ...current, [draftKey]: value }))}
        onSubmit={send} pending={pending} disabled={loading || !agent} />
      <p className="context-note">Каждый чат хранит отдельную историю. Агент учитывает только текущий диалог.</p>
    </section>
  </main>
}
