import { useEffect, useState } from 'react'
import { newMessageId } from '../messageId'
import { formatTokens, formatUsd } from '../usage'

const stages = {
  REQUIREMENTS: 'Planning · сбор требований', DESIGN: 'Execution · проектирование',
  REVIEW: 'Validation · проверка', DONE: 'Done · завершено'
}
const actions = { REQUIREMENTS: 'Подтвердить требования', DESIGN: 'Передать на проверку', REVIEW: 'Подтвердить завершение' }
const expectedActions = {
  DEFINE_GOAL: 'Опишите цель задачи', PROVIDE_REQUIREMENTS: 'Добавьте требования',
  ANSWER_OPEN_QUESTIONS: 'Ответьте на открытые вопросы', CONFIRM_REQUIREMENTS: 'Подтвердите требования',
  RECORD_DECISIONS: 'Зафиксируйте архитектурные решения', CONFIRM_DESIGN: 'Передайте решение на проверку',
  VALIDATE_RESULT: 'Проверьте результат и подтвердите завершение', RESUME_TASK: 'Продолжите задачу', NONE: 'Действий не требуется'
}
const taskFields = { requirements: 'Требования', constraints: 'Ограничения', decisions: 'Подтверждённые решения' }
const blankEntry = () => ({ id: newMessageId(), scope: 'GLOBAL', projectKey: '', key: '', value: '' })

export default function MemoryLayers({ agent, chat, api, disabled, onChat, onBusy }) {
  const [memory, setMemory] = useState(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [expanded, setExpanded] = useState(true)
  const [taskEdit, setTaskEdit] = useState(null)
  const [entryEdit, setEntryEdit] = useState(null)
  const [deleting, setDeleting] = useState(null)
  const working = chat?.workingMemory
  useEffect(() => {
    let active = true
    setMemory(null)
    api.memory(agent.id).then(value => { if (active) setMemory(value) })
      .catch(e => { if (active) setError(e.message) })
    return () => { active = false }
  }, [api, agent.id])
  const blocked = disabled || busy || !memory
  const readOnly = !chat || chat.readOnly
  async function run(operation) {
    if (blocked) return
    setBusy(true); onBusy(true); setError(''); setNotice('')
    try { await operation(); setNotice('Память обновлена.') }
    catch (e) { setError(e.message) }
    finally { setBusy(false); onBusy(false) }
  }
  async function reloadMemory() {
    if (busy || disabled) return
    setBusy(true); onBusy(true); setError('')
    try {
      const [updatedMemory, updatedChat] = await Promise.all([api.memory(agent.id), chat ? api.chat(agent.id, chat.id) : null])
      setMemory(updatedMemory); if (updatedChat) onChat(updatedChat)
      setTaskEdit(null); setEntryEdit(null); setDeleting(null)
    } catch (e) { setError(e.message) }
    finally { setBusy(false); onBusy(false) }
  }
  function editTask() {
    setTaskEdit({ version: working?.version || 0, projectKey: working?.projectKey || '', goal: working?.goal || '',
      requirements: JSON.stringify(working?.requirements || {}, null, 2),
      constraints: JSON.stringify(working?.constraints || {}, null, 2),
      decisions: JSON.stringify(working?.decisions || {}, null, 2), openQuestions: (working?.openQuestions || []).join('\n') })
  }
  const entries = memory?.entries || []
  const proposals = (working?.proposals || []).filter(p => !(memory?.resolvedProposals || []).includes(p.id) && !entries.some(e => e.id === p.id))
  const recent = chat?.strategy === 'SLIDING_WINDOW' ? agent.slidingMessages
    : chat?.strategy === 'FACTS' ? agent.factsMessages : null
  return <section className="memory-layers" aria-label="Слои памяти архитектора">
    <div className="memory-heading"><h2>Состояние и память</h2>
      <button type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
        {expanded ? 'Свернуть память' : 'Показать память'}</button></div>
    <div hidden={!expanded}>
    <p>Личный режим: один владелец приложения. Подтверждённая долговременная память доступна в других чатах этого агента.</p>
    <details><summary>1. Краткосрочная — текущий диалог</summary>
      <p>Сообщений с сохранённым текстом: {chat?.messages?.length || 0}. В контекст диалога попадёт: {recent
        ? Math.min(recent, chat?.messages?.length || 0) : chat?.messages?.length || 0} + новое сообщение.
        {chat?.summary && ' Также передаётся накопительное summary.'}</p>
      <p>Окно контекста не удаляет историю в Sliding Window и Sticky Facts. Summary заменяет старый текст пересказом.</p>
    </details>
    <details open><summary>2. Рабочая — Task State Machine</summary>
      <div className="task-state-card" aria-label="Состояние задачи">
        <p><strong>Этап:</strong> {stages[working?.stage] || stages.REQUIREMENTS}</p>
        <p><strong>Статус:</strong> {working?.status === 'PAUSED' ? 'На паузе' : 'Активна'}</p>
        <p><strong>Текущий шаг:</strong> {working?.currentStep || 'Определить цель задачи'}</p>
        <p><strong>Ожидаемое действие:</strong> {expectedActions[working?.expectedAction] || 'Опишите цель задачи'}</p>
        <small>Версия состояния: {working?.version || 0}</small>
      </div>
      <p>Проект: {working?.projectKey || 'не выбран — знания других проектов не используются'}</p>
      <p>Цель: {working?.goal || 'Появится после обсуждения задачи'}</p>
      {Object.entries(taskFields).map(([field, title]) => <div key={field}><h3>{title}</h3>
        {Object.entries(working?.[field] || {}).length ? <dl>{Object.entries(working[field]).map(([key, value]) =>
          <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl> : <p>Пока нет</p>}</div>)}
      <h3>Открытые вопросы</h3>
      {working?.openQuestions?.length ? <ul>{working.openQuestions.map((q, i) => <li key={i}>{q}</li>)}</ul> : <p>Нет сохранённых вопросов</p>}
      <button type="button" disabled={blocked || readOnly} onClick={editTask}>Изменить данные задачи</button>
      {actions[working?.stage || 'REQUIREMENTS'] && <button type="button" disabled={blocked || readOnly || working?.status === 'PAUSED'}
        onClick={() => run(async () => onChat(await api.advanceTask(agent.id, chat.id, working?.version || 0)))}>
        {actions[working?.stage || 'REQUIREMENTS']}</button>}
      {working?.status === 'PAUSED'
        ? <button type="button" disabled={blocked || readOnly}
          onClick={() => run(async () => onChat(await api.resumeTask(agent.id, chat.id, working?.version || 0)))}>Продолжить задачу</button>
        : <button type="button" disabled={blocked || readOnly}
          onClick={() => run(async () => onChat(await api.pauseTask(agent.id, chat.id, working?.version || 0)))}>Поставить на паузу</button>}
      {!chat && <p>Сначала отправьте сообщение, чтобы создать задачу.</p>}
      <p>Переходы выполняет приложение. LLM обновляет данные, но не может изменить этап. После паузы вся история и память сохраняются.</p>
      {taskEdit && <form onSubmit={e => { e.preventDefault(); run(async () => {
        const task = { goal: taskEdit.goal, openQuestions: taskEdit.openQuestions.split('\n').map(q => q.trim()).filter(Boolean) }
        for (const field of Object.keys(taskFields)) task[field] = JSON.parse(taskEdit[field])
        onChat(await api.editTask(agent.id, chat.id, { version: taskEdit.version, projectKey: taskEdit.projectKey, task }))
        setTaskEdit(null)
      }) }}>
        <label>Идентификатор проекта (латиница, цифры, _ или -)<input maxLength={80} value={taskEdit.projectKey}
          onChange={e => setTaskEdit({ ...taskEdit, projectKey: e.target.value })} /></label>
        <label>Цель задачи<textarea maxLength={1000} value={taskEdit.goal} onChange={e => setTaskEdit({ ...taskEdit, goal: e.target.value })} /></label>
        {Object.entries(taskFields).map(([field, title]) => <label key={field}>{title} (JSON-словарь строк)
          <textarea rows={4} value={taskEdit[field]} onChange={e => setTaskEdit({ ...taskEdit, [field]: e.target.value })} /></label>)}
        <label>Открытые вопросы (по одному на строку)<textarea value={taskEdit.openQuestions}
          onChange={e => setTaskEdit({ ...taskEdit, openQuestions: e.target.value })} /></label>
        <button disabled={blocked} type="submit">Сохранить задачу</button>
        <button disabled={busy} type="button" onClick={() => setTaskEdit(null)}>Отменить редактирование задачи</button>
      </form>}
      {!!working?.usage?.calls && <p>Обновление памяти: {working.usage.calls} выз. · вход {formatTokens(working.usage.promptTokens)} ·
        выход {formatTokens(working.usage.completionTokens)} · {formatUsd(working.usage.totalCostUsd)}.
        {working.lastExtraction && <> Последнее: {(working.lastExtraction.durationMs / 1000).toFixed(2)} сек. ·
          {formatTokens(working.lastExtraction.totalTokens)} токенов · {formatUsd(working.lastExtraction.totalCostUsd)}</>}</p>}
    </details>
    <details open><summary>3. Долговременная — подтверждённые записи</summary>
      {!memory && !error && <p role="status">Загружаем долговременную память…</p>}
      <p>Общие записи используются между чатами. Записи проекта — только при совпадении идентификатора проекта в задаче.
        Удаление чата не удаляет эти записи.</p>
      {entries.map(entry => <article className="memory-entry" key={entry.id}>
        <strong>{entry.key}</strong><p>{entry.value}</p>
        <small>{entry.scope === 'GLOBAL' ? 'Общая' : `Проект: ${entry.projectKey}`} ·
          {entry.scope === 'GLOBAL' || entry.projectKey === working?.projectKey ? ' используется в этом чате' : ' вне контекста этого чата'}
          {' '}· {entry.sourceChatId ? `Источник: чат ${entry.sourceChatId}` : 'Добавлено вручную'}
          {' '}· {new Date(entry.updatedAt).toLocaleString('ru-RU')}</small>
        <div><button disabled={blocked} type="button" onClick={() => setEntryEdit({ ...entry, version: memory.version })}>Изменить «{entry.key}»</button>
          <button disabled={blocked} type="button" onClick={() => setDeleting(entry)}>Удалить «{entry.key}»</button></div>
      </article>)}
      {memory && !entries.length && <p>Пока ничего не сохранено.</p>}
      <button disabled={blocked} type="button" onClick={() => setEntryEdit({ ...blankEntry(), version: memory.version })}>Добавить запись памяти</button>
      {entryEdit && <form onSubmit={e => { e.preventDefault(); run(async () => {
        setMemory(await api.putMemory(agent.id, entryEdit.id, entryEdit)); setEntryEdit(null)
      }) }}>
        <label>Область памяти<select value={entryEdit.scope} onChange={e => setEntryEdit({ ...entryEdit, scope: e.target.value })}>
          <option value="GLOBAL">Общая</option><option value="PROJECT">Проект</option></select></label>
        {entryEdit.scope === 'PROJECT' && <label>Проект записи<input required maxLength={80} pattern="[a-zA-Z0-9_-]+"
          value={entryEdit.projectKey} onChange={e => setEntryEdit({ ...entryEdit, projectKey: e.target.value })} /></label>}
        <label>Ключ записи<input required maxLength={80} value={entryEdit.key} onChange={e => setEntryEdit({ ...entryEdit, key: e.target.value })} /></label>
        <label>Значение записи<textarea required maxLength={500} value={entryEdit.value} onChange={e => setEntryEdit({ ...entryEdit, value: e.target.value })} /></label>
        <button disabled={blocked} type="submit">Подтвердить сохранение записи</button>
        <button disabled={busy} type="button" onClick={() => setEntryEdit(null)}>Отменить редактирование записи</button>
      </form>}
      {deleting && <div role="group" aria-label="Подтверждение удаления памяти"><p>Удалить запись «{deleting.key}» из долговременной памяти?</p>
        <button disabled={blocked} type="button" onClick={() => run(async () => {
          setMemory(await api.deleteMemory(agent.id, deleting.id, memory.version)); setDeleting(null)
        })}>Подтвердить удаление записи</button>
        <button disabled={busy} type="button" onClick={() => setDeleting(null)}>Отмена удаления записи</button></div>}
      {!!proposals.length && <h3>Предложения — ещё не сохранены</h3>}
      {proposals.map(p => <article className="memory-entry" key={p.id}>
        <strong>{p.key}</strong><p>{p.value}</p><blockquote>{p.evidence}</blockquote>
        <small>{p.scope === 'GLOBAL' ? 'Общая память' : `Проект: ${working?.projectKey || 'сначала укажите проект в задаче'}`}</small>
        <div><button type="button" disabled={blocked || readOnly || (p.scope === 'PROJECT' && !working?.projectKey)}
          onClick={() => run(async () => setMemory(await api.acceptProposal(agent.id, chat.id, p.id, memory.version, working.version)))}>Сохранить «{p.key}»</button>
          <button type="button" disabled={blocked || readOnly} onClick={() => run(async () =>
            onChat(await api.rejectProposal(agent.id, chat.id, p.id, working.version)))}>Отклонить «{p.key}»</button></div>
      </article>)}
    </details>
    {busy && <p role="status">Сохраняем память…</p>}
    {notice && <p role="status">{notice}</p>}
    {error && <div role="alert">{error}</div>}
    <button type="button" disabled={busy || disabled} onClick={reloadMemory}>Обновить память</button>
    </div>
  </section>
}
