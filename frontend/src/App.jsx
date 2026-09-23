import { useEffect, useRef, useState } from 'react'
import { agentApi } from './api'
import { newMessageId } from './messageId'
import MessageList from './components/MessageList'
import ChatSidebar from './components/ChatSidebar'
import MessageComposer from './components/MessageComposer'
import ChatUsageSummary from './components/ChatUsageSummary'
import ContextMemory from './components/ContextMemory'
import StrategyPanel from './components/StrategyPanel'
import MemoryLayers from './components/MemoryLayers'
import InvariantPanel from './components/InvariantPanel'
import AuthScreen from './components/AuthScreen'
import UserProfile from './components/UserProfile'
import McpCatalog from './components/McpCatalog'

const remember = (key, value) => { try { localStorage.setItem(key, value) } catch { /* Optional storage. */ } }
const recalled = key => { try { return localStorage.getItem(key) || '' } catch { return '' } }

export default function App({ api = agentApi }) {
  const supportsAuth = typeof api.me === 'function'
  const [profile, setProfile] = useState(supportsAuth ? null : { username: 'test', displayName: 'Test', version: 0, constraints: [] })
  const [authLoading, setAuthLoading] = useState(supportsAuth)
  const [agents, setAgents] = useState([])
  const [agentId, setAgentId] = useState('')
  const [chats, setChats] = useState([])
  const [chat, setChat] = useState(null)
  const [drafts, setDrafts] = useState({})
  const [pending, setPending] = useState(false)
  const [pendingMessage, setPendingMessage] = useState('')
  const [streamedAnswer, setStreamedAnswer] = useState('')
  const [streamPhase, setStreamPhase] = useState('')
  const [notice, setNotice] = useState('')
  const [strategy, setStrategy] = useState('SUMMARY')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [memoryBusy, setMemoryBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const [activeView, setActiveView] = useState('chat')
  const [mcpServers, setMcpServers] = useState([])
  const [mcpLoading, setMcpLoading] = useState(false)
  const [mcpError, setMcpError] = useState('')
  const [mcpEnabled, setMcpEnabled] = useState(false)
  const [mcpServerId, setMcpServerId] = useState('')
  const retry = useRef(null)
  const busyRef = useRef(false)
  const deleteTrigger = useRef(null)
  const agent = agents.find(item => item.id === agentId)
  const draftKey = chat ? `${agentId}/${chat.id}` : agentId
  const draft = drafts[draftKey] || ''

  useEffect(() => {
    if (!supportsAuth) return
    let active = true
    api.me().then(value => { if (active) setProfile(value) })
      .catch(() => { if (active) { api.clearCredentials(); setProfile(null) } })
      .finally(() => { if (active) setAuthLoading(false) })
    return () => { active = false }
  }, [api, supportsAuth])

  useEffect(() => {
    if (!deleteTarget && deleteTrigger.current) {
      const target = deleteTrigger.current.isConnected ? deleteTrigger.current : document.querySelector('.new-chat')
      target?.focus()
      deleteTrigger.current = null
    }
  }, [deleteTarget])

  useEffect(() => {
    if (!profile) return
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
  }, [api, reload, profile?.username])

  useEffect(() => {
    if (!profile || typeof api.mcpServers !== 'function') return
    let active = true
    setMcpLoading(true); setMcpError('')
    api.mcpServers().then(items => {
      if (!active) return
      setMcpServers(items)
      const available = items.filter(item => item.connected && !item.error && item.tools?.length)
      setMcpServerId(current => available.some(item => item.id === current) ? current : (available[0]?.id || ''))
      if (!available.length) setMcpEnabled(false)
    }).catch(exception => { if (active) { setMcpServers([]); setMcpError(exception.message); setMcpEnabled(false) } })
      .finally(() => { if (active) setMcpLoading(false) })
    return () => { active = false }
  }, [api, profile?.username, reload])

  useEffect(() => {
    if (!agentId) return
    let active = true
    setLoading(true); setError(''); setChats([]); setChat(null)
    setStrategy(agents.find(item => item.id === agentId)?.defaultStrategy || 'SUMMARY')
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
      strategy: updated.strategy, parentChatId: updated.parentChatId, checkpointId: updated.checkpointId,
      messageCount: updated.messages.length + Number(updated.summary?.summarizedMessages || 0)
        + Number(updated.memory?.discardedMessages || 0) },
    ...current.filter(item => item.id !== updated.id)])
  }

  async function createChat() {
    if (busyRef.current || loading) return
    setChat(null); setError(''); setNotice(''); retry.current = null
    setStrategy(agent?.defaultStrategy || 'SUMMARY')
  }

  async function forkChat() {
    if (busyRef.current || loading || !chat) return
    setLoading(true); setError('')
    try {
      const forked = await api.fork(agentId, chat.id, {
        firstBranchName: 'Вариант A', secondBranchName: 'Вариант B'
      })
      updateChat(forked)
      setChats(await api.chats(agentId))
    } catch (exception) { setError(exception.message) }
    finally { setLoading(false) }
  }

  async function deleteChat() {
    if (busyRef.current || loading || !deleteTarget) return
    busyRef.current = true; setDeleting(true); setError(''); setNotice('')
    const ids = new Set([deleteTarget.id])
    let count
    do {
      count = ids.size
      chats.filter(item => ids.has(item.parentChatId)).forEach(item => ids.add(item.id))
    } while (ids.size !== count)
    try {
      await api.delete(agentId, deleteTarget.id)
      setNotice('Чат удалён вместе с его дочерними ветками. Восстановление доступно только из резервной копии.')
      const remaining = chats.filter(item => !ids.has(item.id))
      setChats(remaining)
      setDrafts(current => Object.fromEntries(Object.entries(current)
        .filter(([key]) => ![...ids].some(id => key === `${agentId}/${id}`))))
      if (retry.current && ids.has(retry.current.chatId)) retry.current = null
      if (chat && ids.has(chat.id)) {
        setChat(null); remember(`agent-lab.chat.${agentId}`, '')
      } else if (chat) {
        // Refresh checkpoint/branch links after deleting a child.
        setChat(await api.chat(agentId, chat.id))
      }
      setDeleteTarget(null)
    } catch (exception) { setError(exception.message); setDeleteTarget(null) }
    finally { busyRef.current = false; setDeleting(false) }
  }

  async function send(event) {
    event.preventDefault()
    if (busyRef.current || loading || !agent) return
    const content = draft.trim()
    if (!content) { setError('Введите сообщение'); return }
    if (mcpEnabled && !mcpServerId) { setError('Выберите доступный MCP-сервер'); return }
    const selectedMcp = mcpEnabled ? mcpServerId : null
    busyRef.current = true; setPending(true); setStreamedAnswer(''); setStreamPhase('connecting')
    setPendingMessage(content)
    setError(''); setNotice('')
    try {
      const current = chat || await api.create(agentId, strategy)
      if (!chat) updateChat(current)
      const key = `${agentId}/${current.id}`
      setDrafts(values => ({ ...values, [draftKey]: '', [key]: '' }))
      if (!retry.current || retry.current.chatId !== current.id || retry.current.content !== content
          || retry.current.mcpServerId !== selectedMcp) {
        retry.current = { chatId: current.id, content, mcpServerId: selectedMcp, messageId: newMessageId() }
      }
      let completed
      await api.sendStream(agentId, current.id, {
        messageId: retry.current.messageId, content, mcpServerId: retry.current.mcpServerId
      }, {
        updating_memory: () => setStreamPhase('updating_memory'),
        syncing_questions: () => setStreamPhase('syncing_questions'),
        updating_facts: () => setStreamPhase('updating_facts'),
        summarizing: () => setStreamPhase('summarizing'),
        checking_invariants: () => setStreamPhase('checking_invariants'),
        checking_lifecycle: () => setStreamPhase('checking_lifecycle'),
        using_mcp: () => setStreamPhase('using_mcp'),
        generating: () => setStreamPhase('generating'),
        validating_lifecycle: () => setStreamPhase('validating_lifecycle'),
        validating_answer: () => setStreamPhase('validating_answer'),
        delta: part => { setStreamPhase('streaming'); setStreamedAnswer(value => value + (part.text || '')) },
        completed: event => { completed = event.chat; if (event.warning) setNotice(event.warning) }
      })
      if (!completed) throw new Error('Сервер закрыл поток до завершения ответа. Попробуйте ещё раз.')
      setPendingMessage('')
      updateChat(completed)
      setDrafts(values => ({ ...values, [key]: '' }))
      retry.current = null
    } catch (exception) {
      setDrafts(values => ({ ...values, [draftKey]: content,
        ...(retry.current?.chatId ? { [`${agentId}/${retry.current.chatId}`]: content } : {}) }))
      setError(exception.message)
    }
    finally { busyRef.current = false; setPending(false); setPendingMessage(''); setStreamedAnswer(''); setStreamPhase('') }
  }

  if (authLoading) return <main className="auth-shell"><div className="loading-state" role="status">Проверяем профиль…</div></main>
  if (!profile) return <AuthScreen api={api} onAuthenticated={value => { setProfile(value); setReload(current => current + 1) }} />

  function logout() {
    api.clearCredentials(); setProfile(null); setAgents([]); setAgentId(''); setChats([]); setChat(null)
    setMcpEnabled(false); setMcpServerId(''); setMcpServers([])
    setError(''); setNotice(''); retry.current = null
  }

  return <main className="app-shell">
    <ChatSidebar agents={agents} agentId={agentId} chats={chats} chatId={chat?.id}
      activeView={activeView} onView={setActiveView}
      disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onAgent={setAgentId} onChat={openChat}
      onCreate={createChat} onDelete={(target, trigger) => { deleteTrigger.current = trigger; setDeleteTarget(target) }} />
    {activeView === 'mcp' ? <McpCatalog servers={mcpServers} loading={mcpLoading} error={mcpError}
      onReload={() => setReload(value => value + 1)} profile={profile} api={api}
      onProfile={setProfile} onLogout={logout} /> : <>
    <div className={`agent-workspace ${chat ? 'with-inspector' : ''}`}>
    <section className="conversation" aria-label="Диалог с агентом">
      <header className="conversation-header"><div><h1>{agent?.name || 'Мои агенты'}</h1>
        <p>{agent?.description || 'Выберите помощника для своей задачи'}</p></div>
        <div className="header-actions">{agent && <span className="model-name">{agent.model}</span>}
          <UserProfile profile={profile} api={api} disabled={pending || loading || memoryBusy}
            onProfile={setProfile} onLogout={logout} /></div></header>
      {!loading && !chat && <StrategyPanel agent={agent} chat={chat} strategy={strategy} onStrategy={setStrategy}
        disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onFork={forkChat} onOpen={openChat} chats={chats} />}
      {!loading && <ChatUsageSummary messages={chat?.messages || []} summary={chat?.summary} memory={chat?.memory}
        workingMemory={chat?.workingMemory} invariants={chat?.invariants} lifecycle={chat?.lifecycle} />}
      {loading ? <div className="loading-state" role="status">Загружаем чаты…</div>
        : <MessageList messages={chat?.messages || []} agent={agent} pending={pending} pendingMessage={pendingMessage}
          streamedAnswer={streamedAnswer} streamPhase={streamPhase} />}
      {notice && <div className="notice-banner" role="status">{notice}</div>}
      {error && <div className="error-banner" role="alert"><span>{error}</span>
        {!pending && <button type="button" onClick={() => { setError(''); setReload(value => value + 1) }}>Обновить</button>}</div>}
      <MessageComposer draft={draft} onChange={value => setDrafts(current => ({ ...current, [draftKey]: value }))}
        onSubmit={send} pending={pending} disabled={loading || !agent || chat?.readOnly || !!chat?.branches?.length || !!deleteTarget || deleting || memoryBusy}
        mcpServers={mcpServers.filter(item => item.connected && !item.error && item.tools?.length)}
        mcpEnabled={mcpEnabled} mcpServerId={mcpServerId}
        onMcpEnabled={setMcpEnabled} onMcpServer={setMcpServerId} />
      <p className="context-note">{agent?.memoryLayers
        ? 'История и задача изолированы по чатам и веткам. Профиль и подтверждённая долговременная память принадлежат текущему пользователю.'
        : 'Контекст и история изолированы по пользователям, чатам и веткам. Активный профиль применяется автоматически.'}</p>
    </section>
    {!loading && chat && <aside className="agent-inspector" aria-label="Параметры и память текущего чата">
      <StrategyPanel agent={agent} chat={chat} strategy={strategy} onStrategy={setStrategy}
        disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onFork={forkChat} onOpen={openChat} chats={chats} />
      {agent?.memoryLayers && <MemoryLayers key={`${agentId}/${chat.id}`} agent={agent} chat={chat} api={api}
        disabled={pending || !!deleteTarget || deleting} onChat={updateChat}
        onBusy={value => { busyRef.current = value; setMemoryBusy(value) }} />}
      {agent?.invariants && <InvariantPanel agent={agent} chat={chat} api={api}
        disabled={pending || !!deleteTarget || deleting} onChat={updateChat}
        onBusy={value => { busyRef.current = value; setMemoryBusy(value) }} />}
      {(chat.strategy || strategy) === 'SUMMARY' && <ContextMemory agent={agent} summary={chat.summary} />}
    </aside>}
    </div>
    </>}
    {deleteTarget && <div className="delete-overlay"><section role="alertdialog" aria-modal="true"
      aria-labelledby="delete-title" aria-describedby="delete-description" className="delete-confirm"
      onKeyDown={event => {
        if (event.key === 'Escape' && !deleting) setDeleteTarget(null)
        if (event.key === 'Tab') {
          const buttons = [...event.currentTarget.querySelectorAll('button:not(:disabled)')]
          if (buttons.length) {
            const index = buttons.indexOf(document.activeElement)
            event.preventDefault()
            buttons[(index + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length].focus()
          }
        }
      }}>
      <h2 id="delete-title">Удалить чат «{deleteTarget.title}»?</h2>
      <p id="delete-description">История, память и все дочерние ветки этого чата будут удалены.
        Соседние ветки сохранятся. Отменить удаление нельзя.
        {agent?.memoryLayers && ' Отдельно сохранённая долговременная память останется.'}</p>
      {deleting && <p role="status">Удаляем чат…</p>}
      <button type="button" autoFocus disabled={deleting} onClick={() => setDeleteTarget(null)}>Отмена</button>
      <button type="button" disabled={deleting} onClick={deleteChat}>Удалить</button>
    </section></div>}
  </main>
}
