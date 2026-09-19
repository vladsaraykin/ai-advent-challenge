import { useState } from 'react'
import { newMessageId } from '../messageId'

const types = {
  ARCHITECTURE: 'Выбранная архитектура',
  TECHNICAL_DECISION: 'Техническое решение',
  STACK_CONSTRAINT: 'Ограничение по стеку',
  BUSINESS_RULE: 'Бизнес-правило'
}
const blank = () => ({ id: newMessageId(), type: 'STACK_CONSTRAINT', title: '', rule: '', rationale: '' })

export default function InvariantPanel({ agent, chat, api, disabled, onChat, onBusy }) {
  const [expanded, setExpanded] = useState(true)
  const [edit, setEdit] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const entries = chat?.invariants?.entries || []
  const blocked = disabled || busy || chat?.readOnly

  async function run(operation) {
    if (blocked) return
    setBusy(true); onBusy(true); setError('')
    try { onChat(await operation()); setEdit(null) }
    catch (exception) { setError(exception.message) }
    finally { setBusy(false); onBusy(false) }
  }

  return <section className="invariant-panel" aria-label="Инварианты задачи">
    <div className="memory-heading"><h2>Инварианты задачи</h2>
      <button type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
        {expanded ? 'Свернуть' : 'Показать'}</button></div>
    <div hidden={!expanded}>
      <p>Подтверждённые правила проверяются до генерации и перед показом ответа. Модель не может изменить их сама.</p>
      {!entries.length && <p>Инварианты пока не заданы.</p>}
      <ul className="invariant-list">{entries.map(entry => <li key={entry.id}>
        <span className="invariant-type">{types[entry.type]}</span>
        <strong>{entry.title}</strong><p>{entry.rule}</p>
        {entry.rationale && <small>Причина: {entry.rationale}</small>}
        <div className="invariant-actions">
          <button type="button" disabled={blocked} onClick={() => setEdit({ ...entry })}>Изменить</button>
          <button type="button" disabled={blocked} onClick={() => {
            if (window.confirm(`Удалить инвариант «${entry.title}»?`)) run(() =>
              api.deleteInvariant(agent.id, chat.id, entry.id, chat.invariants.version))
          }}>Удалить</button>
        </div>
      </li>)}</ul>
      {!edit && <button type="button" disabled={blocked} onClick={() => setEdit(blank())}>Добавить инвариант</button>}
      {edit && <form className="invariant-form" onSubmit={event => {
        event.preventDefault()
        run(() => api.putInvariant(agent.id, chat.id, edit.id, {
          version: chat.invariants.version, type: edit.type, title: edit.title,
          rule: edit.rule, rationale: edit.rationale
        }))
      }}>
        <label>Тип<select value={edit.type} onChange={event => setEdit(value => ({ ...value, type: event.target.value }))}>
          {Object.entries(types).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Название<input required maxLength="100" value={edit.title}
          onChange={event => setEdit(value => ({ ...value, title: event.target.value }))} /></label>
        <label>Обязательное правило<textarea required maxLength="1000" rows="4" value={edit.rule}
          onChange={event => setEdit(value => ({ ...value, rule: event.target.value }))} /></label>
        <label>Причина или контекст<textarea maxLength="500" rows="2" value={edit.rationale}
          onChange={event => setEdit(value => ({ ...value, rationale: event.target.value }))} /></label>
        <div className="invariant-actions"><button type="submit" disabled={blocked}>Сохранить</button>
          <button type="button" disabled={busy} onClick={() => setEdit(null)}>Отмена</button></div>
      </form>}
      {busy && <p role="status">Сохраняем инварианты…</p>}
      {error && <p className="memory-error" role="alert">{error}</p>}
    </div>
  </section>
}
