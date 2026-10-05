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
import RagDocuments from './components/RagDocuments'
import ReportDownloads from './components/ReportDownloads'
import ToolApproval from './components/ToolApproval'
import RequestUsage from './components/RequestUsage'
import RetrievalSettings from './components/RetrievalSettings'

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
  const [mcpServerIds, setMcpServerIds] = useState([])
  const [settings, setSettings] = useState({ version: 0, ragEnabled: false, mcpServerIds: [], retrieval: { candidateK: 20, finalK: 5, threshold: .2 } })
  const [settingsReady, setSettingsReady] = useState(!api.chatSettings)
  const [settingsBusy, setSettingsBusy] = useState(false)
  const [transcript, setTranscript] = useState(null)
  const [approval, setApproval] = useState(null)
  const [inspectorOpen, setInspectorOpen] = useState(() => !window.matchMedia?.('(max-width: 850px)').matches)
  const [requestUsage, setRequestUsage] = useState([])
  const [activeJob, setActiveJob] = useState(null)
  const retry = useRef(null)
  const busyRef = useRef(false)
  const deleteTrigger = useRef(null)
  const agent = agents.find(item => item.id === agentId)
  const draftKey = chat ? `${agentId}/${chat.id}` : agentId
  const draft = drafts[draftKey] || ''

  useEffect(() => {
    let active = true
    setApproval(null); setActiveJob(null); if (!busyRef.current) setPendingMessage(''); setTranscript(null)
    if (!chat || !api.chatSettings) { setSettingsReady(true); return }
    setSettingsReady(false)
    Promise.all([api.chatSettings(agentId, chat.id), api.chatHistory(agentId, chat.id), api.pendingRequest(agentId, chat.id)])
      .then(([preferences, history, outstanding]) => {
        if (!active) return
        setSettings(preferences); setTranscript(history)
        setMcpServerIds(preferences.mcpServerIds); setMcpEnabled(!!preferences.mcpServerIds.length)
        const awaiting = outstanding.calls?.find(call => call.status === 'AWAITING_APPROVAL')
        if (awaiting && outstanding.request?.status === 'AWAITING_APPROVAL') {
          const context = outstanding.request.context
          setApproval({ call: awaiting, requestId: context.messageId })
          setPendingMessage(context.content)
          retry.current = { chatId: chat.id, content: context.content, messageId: context.messageId }
        } else if (['FAILED', 'INTERRUPTED'].includes(outstanding.request?.status)) {
          const context = outstanding.request.context
          retry.current = { chatId: chat.id, content: context.content, messageId: context.messageId, mcpServerIds: context.settings.mcpServerIds }
          setDrafts(values => ({ ...values, [`${agentId}/${chat.id}`]: context.content }))
          setNotice(outstanding.calls?.some(call => call.status === 'UNCERTAIN')
            ? 'Результат MCP-действия неизвестен. Проверьте внешний сервис: автоматическое повторение такого вызова запрещено.'
            : 'Предыдущий запрос прерван. Текст восстановлен; можно повторить отправку с тем же идентификатором.')
        } else if (outstanding.request?.status === 'RUNNING') {
          setActiveJob(outstanding.request.context.messageId)
          setPendingMessage(outstanding.request.context.content)
          setNotice('Запрос ещё выполняется на сервере. Проверяем его состояние…')
          return
        }
        setSettingsReady(true)
      }).catch(exception => { if (active) setError(exception.message) })
    return () => { active = false }
  }, [chat?.id, agentId, api])

  useEffect(() => {
    if (!activeJob || !chat || !api.requestDetails) return
    let active = true, timer
    async function check() {
      try {
        const result = await api.requestDetails(agentId, chat.id, activeJob)
        if (!active) return
        if (result.request.status === 'RUNNING') { timer = setTimeout(check, 2000); return }
        if (result.request.status === 'COMPLETED') {
          const [updated, history] = await Promise.all([api.chat(agentId, chat.id), api.chatHistory(agentId, chat.id)])
          if (!active) return
          updateChat(updated); setTranscript(history); setPendingMessage('')
        } else if (result.request.status === 'AWAITING_APPROVAL') {
          const call = result.calls.find(item => item.status === 'AWAITING_APPROVAL')
          if (call) setApproval({ call, requestId: activeJob })
        } else {
          const context = result.request.context
          retry.current = { chatId: chat.id, content: context.content, messageId: context.messageId, mcpServerIds: context.settings.mcpServerIds }
          setDrafts(values => ({ ...values, [`${agentId}/${chat.id}`]: context.content }))
          setNotice('Запрос прерван. Проверьте журнал инструментов перед повтором; неизвестные MCP-действия автоматически не повторяются.')
        }
        setActiveJob(null); setSettingsReady(true)
      } catch (exception) { if (active) { setError(exception.message); timer = setTimeout(check, 5000) } }
    }
    check()
    return () => { active = false; clearTimeout(timer) }
  }, [activeJob, chat?.id, agentId, api])

  useEffect(() => {
    let active = true
    if (!chat || !api.chatRequestUsage) { setRequestUsage([]); return }
    api.chatRequestUsage(agentId, chat.id).then(value => { if (active) setRequestUsage(value) })
      .catch(() => { if (active) setNotice('Статистика запросов пока недоступна.') })
    return () => { active = false }
  }, [chat?.id, chat?.updatedAt, agentId, pending, api])

  async function changeSettings(changes) {
    if (pending || settingsBusy || approval) return
    const next = { ...settings, ...changes }
    setSettingsBusy(true); setError('')
    try {
      const saved = chat && api.saveChatSettings ? await api.saveChatSettings(agentId, chat.id, next) : next
      setSettings(saved); setMcpServerIds(saved.mcpServerIds); setMcpEnabled(!!saved.mcpServerIds.length)
    } catch (exception) { setError(exception.message) }
    finally { setSettingsBusy(false) }
  }

  function streamHandlers(completed, requested) {
    const handlers = Object.fromEntries(['retrieving', 'updating_memory', 'syncing_questions', 'updating_facts',
      'summarizing', 'checking_invariants', 'checking_lifecycle', 'using_mcp', 'generating',
      'validating_lifecycle', 'validating_answer'].map(phase => [phase, () => setStreamPhase(phase)]))
    return { ...handlers,
      delta: part => { setStreamPhase('streaming'); setStreamedAnswer(value => value + (part.text || '')) },
      approval: call => requested(call),
      completed: event => { completed(event.chat); if (event.warning) setNotice(event.warning) }
    }
  }

  async function decideTool(allow) {
    if (!approval || busyRef.current) return
    const current = approval
    busyRef.current = true; setPending(true); setStreamedAnswer(''); setStreamPhase('using_mcp'); setError('')
    let nextApproval = null
    try {
      let completed
      await api.approveTool(agentId, chat.id, current.requestId, { callId: current.call.id, allow },
        streamHandlers(value => { completed = value }, call => { nextApproval = { call, requestId: current.requestId }; setApproval(nextApproval) }))
      if (completed) {
        updateChat(completed); setTranscript(await api.chatHistory(agentId, chat.id))
        setApproval(null); setPendingMessage(''); retry.current = null
      } else if (!nextApproval) throw new Error('Поток прерван. Обновите страницу для проверки состояния запроса.')
    } catch (exception) { setError(exception.message); setApproval(null) }
    finally { busyRef.current = false; setPending(false); setStreamedAnswer(''); setStreamPhase('') }
  }

  async function cancelToolRequest() {
    if (!approval || pending) return
    try { await api.cancelRequest(agentId, chat.id, approval.requestId); setApproval(null); setPendingMessage(''); retry.current = null }
    catch (exception) { setError(exception.message) }
  }

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
      setMcpServerIds(current => current.filter(id => available.some(item => item.id === id)))
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
    if (busyRef.current || loading || !agent || !settingsReady || settingsBusy || approval) return
    const content = draft.trim()
    if (!content) { setError('Введите сообщение'); return }
    if (mcpEnabled && !mcpServerIds.length) { setError('Выберите хотя бы один MCP-сервер'); return }
    const selectedMcp = mcpEnabled ? [...mcpServerIds].sort() : []
    busyRef.current = true; setPending(true); setStreamedAnswer(''); setStreamPhase('connecting')
    setPendingMessage(content)
    setError(''); setNotice('')
    try {
      const current = chat || await api.create(agentId, strategy)
      if (!chat) updateChat(current)
      if (!chat && api.saveChatSettings) setSettings(await api.saveChatSettings(agentId, current.id, { ...settings, version: 0 }))
      const key = `${agentId}/${current.id}`
      setDrafts(values => ({ ...values, [draftKey]: '', [key]: '' }))
      if (!retry.current || retry.current.chatId !== current.id || retry.current.content !== content
          || JSON.stringify(retry.current.mcpServerIds) !== JSON.stringify(selectedMcp)) {
        retry.current = { chatId: current.id, content, mcpServerIds: selectedMcp, messageId: newMessageId() }
      }
      let completed, awaitingApproval = false
      await api.sendStream(agentId, current.id, {
        messageId: retry.current.messageId, content, mcpServerIds: retry.current.mcpServerIds
      }, { ...streamHandlers(value => { completed = value }, call => {
        awaitingApproval = true
        setApproval({ call, requestId: retry.current.messageId })
      }),
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
      if (awaitingApproval) return
      if (!completed) throw new Error('Сервер закрыл поток до завершения ответа. Попробуйте ещё раз.')
      setPendingMessage('')
      updateChat(completed)
      if (api.chatHistory) setTranscript(await api.chatHistory(agentId, current.id))
      setDrafts(values => ({ ...values, [key]: '' }))
      retry.current = null
    } catch (exception) {
      setDrafts(values => ({ ...values, [draftKey]: content,
        ...(retry.current?.chatId ? { [`${agentId}/${retry.current.chatId}`]: content } : {}) }))
      setError(exception.message)
    }
    finally { busyRef.current = false; setPending(false); setStreamedAnswer(''); setStreamPhase('') }
  }

  if (authLoading) return <main className="auth-shell"><div className="loading-state" role="status">Проверяем профиль…</div></main>
  if (!profile) return <AuthScreen api={api} onAuthenticated={value => { setProfile(value); setReload(current => current + 1) }} />

  function logout() {
    api.clearCredentials(); setProfile(null); setAgents([]); setAgentId(''); setChats([]); setChat(null)
    setMcpEnabled(false); setMcpServerIds([]); setMcpServers([])
    setError(''); setNotice(''); retry.current = null
  }

  return <main className="app-shell unified-shell">
    <header className="harness-topbar"><a className="brand" href="/">AI Advent<span>Рабочее пространство агента</span></a>
      <div className="harness-tabs" role="tablist" aria-label="Разделы приложения">{[['chat', 'Чат'], ['rag', 'RAG'], ['mcp', 'MCP']].map(([id, label]) =>
        <button key={id} type="button" role="tab" aria-selected={activeView === id} aria-current={activeView === id ? 'page' : undefined} onClick={() => setActiveView(id)}>{label}</button>)}</div>
      <UserProfile profile={profile} api={api} disabled={pending || memoryBusy} onProfile={setProfile} onLogout={logout} />
    </header>
    <div className={`harness-body view-${activeView}`}>
    {activeView === 'chat' && <ChatSidebar unified agents={agents} agentId={agentId} chats={chats} chatId={chat?.id}
      activeView={activeView} onView={setActiveView}
      disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onAgent={setAgentId} onChat={openChat}
      onCreate={createChat} onDelete={(target, trigger) => { deleteTrigger.current = trigger; setDeleteTarget(target) }} />}
    {activeView === 'mcp' ? <McpCatalog embedded servers={mcpServers} loading={mcpLoading} error={mcpError}
      onReload={() => setReload(value => value + 1)} profile={profile} api={api}
      onProfile={setProfile} onLogout={logout} /> : activeView === 'rag' ?
      <RagDocuments embedded key={profile.username} api={api} profile={profile} onProfile={setProfile} onLogout={logout} /> : <>
    <div className={`agent-workspace ${chat && inspectorOpen ? 'with-inspector' : ''}`}>
    <section className="conversation" aria-label="Диалог с агентом">
      <header className="conversation-header"><div><span className="eyebrow">{agent?.name || 'Личный помощник'}</span><h1>{chat?.title || agent?.name || 'Новый диалог'}</h1>
        <p>{agent?.description || 'Выберите помощника для своей задачи'}</p></div>
        <div className="header-actions">{agent && <span className="model-name">{agent.model}</span>}
          {chat && <button className="inspector-toggle" aria-expanded={inspectorOpen} onClick={() => setInspectorOpen(value => !value)}>Память и настройки</button>}</div></header>
      <div className="chat-context-bar"><label><input type="checkbox" checked={settings.ragEnabled}
        disabled={pending || !settingsReady || settingsBusy || !!approval || chat?.readOnly} onChange={event => changeSettings({ ragEnabled: event.target.checked })} /> RAG</label>
        <span>{settings.ragEnabled ? 'Все мои документы · строгие источники' : 'Обычный диалог с LLM'}</span>
        {settingsBusy && <small role="status">Сохраняем настройки…</small>}</div>
      {!loading && !chat && <StrategyPanel agent={agent} chat={chat} strategy={strategy} onStrategy={setStrategy}
        disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onFork={forkChat} onOpen={openChat} chats={chats} />}
      {!chat && api.chatSettings && <details className="new-chat-connections"><summary>Подключения нового чата · {settings.mcpServerIds.length} MCP</summary>
        <p>MCP-вызовы доступны после утверждения плана и требуют вашего разрешения.</p>
        {mcpServers.filter(server => server.connected && !server.error && server.tools?.length).map(server => <label className="server-option" key={server.id}>
          <input type="checkbox" checked={settings.mcpServerIds.includes(server.id)} disabled={pending || settingsBusy || (!settings.mcpServerIds.includes(server.id) && settings.mcpServerIds.length >= 8)}
            onChange={event => changeSettings({ mcpServerIds: event.target.checked ? [...settings.mcpServerIds, server.id] : settings.mcpServerIds.filter(id => id !== server.id) })} />{server.name}</label>)}
      </details>}
      {!loading && <ChatUsageSummary messages={chat?.messages || []} summary={chat?.summary} memory={chat?.memory}
        workingMemory={chat?.workingMemory} invariants={chat?.invariants} lifecycle={chat?.lifecycle} />}
      <RequestUsage requests={requestUsage} />
      {loading ? <div className="loading-state" role="status">Загружаем чаты…</div>
        : <MessageList messages={transcript || chat?.messages || []} agent={agent} pending={pending || !!activeJob} pendingMessage={pendingMessage}
          streamedAnswer={streamedAnswer} streamPhase={streamPhase} />}
      <ToolApproval call={approval?.call} busy={pending} onDecide={decideTool} onCancel={cancelToolRequest} />
      {notice && <div className="notice-banner" role="status">{notice}</div>}
      <ReportDownloads key={profile?.username} api={api} refreshKey={chat?.updatedAt} />
      {error && <div className="error-banner" role="alert"><span>{error}</span>
        {!pending && <button type="button" onClick={() => { setError(''); setReload(value => value + 1) }}>Обновить</button>}</div>}
      <MessageComposer draft={draft} onChange={value => setDrafts(current => ({ ...current, [draftKey]: value }))}
        onSubmit={send} pending={pending} showMcpControls={!api.chatSettings} disabled={loading || !settingsReady || settingsBusy || !!approval || !agent || chat?.readOnly || !!chat?.branches?.length || !!deleteTarget || deleting || memoryBusy}
        mcpServers={mcpServers.filter(item => item.connected && !item.error && item.tools?.length)}
        mcpEnabled={mcpEnabled} mcpServerIds={mcpServerIds}
        onMcpEnabled={setMcpEnabled} onMcpServer={setMcpServerIds} />
      <p className="context-note">{agent?.memoryLayers
        ? 'История и задача изолированы по чатам и веткам. Профиль и подтверждённая долговременная память принадлежат текущему пользователю.'
        : 'Контекст и история изолированы по пользователям, чатам и веткам. Активный профиль применяется автоматически.'}</p>
    </section>
    {!loading && chat && inspectorOpen && <aside className="agent-inspector" aria-label="Параметры и память текущего чата">
      <div className="inspector-heading"><h2>Память задачи</h2><button aria-label="Закрыть панель памяти" onClick={() => setInspectorOpen(false)}>×</button></div>
      <details className="inspector-settings"><summary>Настройки контекста</summary>
      <StrategyPanel agent={agent} chat={chat} strategy={strategy} onStrategy={setStrategy}
        disabled={pending || loading || !!deleteTarget || deleting || memoryBusy} onFork={forkChat} onOpen={openChat} chats={chats} />
      {(chat.strategy || strategy) === 'SUMMARY' && <ContextMemory agent={agent} summary={chat.summary} />}
      </details>
      {api.chatSettings && <RetrievalSettings settings={settings} disabled={pending || !settingsReady || settingsBusy || !!approval || chat.readOnly} onSave={changeSettings} />}
      {agent?.memoryLayers && <MemoryLayers key={`${agentId}/${chat.id}`} agent={agent} chat={chat} api={api}
        disabled={pending || !!deleteTarget || deleting} onChat={updateChat}
        onBusy={value => { busyRef.current = value; setMemoryBusy(value) }} />}
      {agent?.invariants && <details className="inspector-settings"><summary>Инварианты</summary><InvariantPanel agent={agent} chat={chat} api={api}
        disabled={pending || !!deleteTarget || deleting} onChat={updateChat}
        onBusy={value => { busyRef.current = value; setMemoryBusy(value) }} /></details>}
      {api.chatSettings && <section className="inspector-settings"><h3>MCP-инструменты</h3><p>Каждый вызов требует подтверждения. Доступен на этапах Execution и Validation.</p>
        {mcpServers.filter(server => server.connected && !server.error && server.tools?.length).map(server => <label className="server-option" key={server.id}>
          <input type="checkbox" checked={settings.mcpServerIds.includes(server.id)} disabled={pending || !settingsReady || settingsBusy || !!approval || chat.readOnly || (!settings.mcpServerIds.includes(server.id) && settings.mcpServerIds.length >= 8)}
            onChange={event => changeSettings({ mcpServerIds: event.target.checked ? [...settings.mcpServerIds, server.id] : settings.mcpServerIds.filter(id => id !== server.id) })} />
          <span>{server.name}<small>{server.tools.length} инструментов</small></span></label>)}
        {!mcpServers.length && <p>Нет подключённых серверов</p>}{mcpError && <p role="alert">{mcpError}</p>}
      </section>}
    </aside>}
    </div>
    </>}
    </div>
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
