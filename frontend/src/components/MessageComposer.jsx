export default function MessageComposer({ draft, onChange, onSubmit, pending, disabled }) {
  return <form className="composer" onSubmit={onSubmit}>
    <label htmlFor="message">Ваше сообщение</label>
    <textarea id="message" rows="3" value={draft} maxLength={12000} disabled={pending || disabled}
      placeholder="Опишите задачу или задайте уточняющий вопрос…"
      onChange={event => onChange(event.target.value)}
      onKeyDown={event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
          event.preventDefault()
          if (!pending && !disabled && draft.trim()) onSubmit(event)
        }
      }} />
    <div className="composer-actions"><small>{draft.length.toLocaleString('ru-RU')} / 12 000 · Enter — отправить · Shift + Enter — новая строка</small>
      <button type="submit" disabled={pending || disabled || !draft.trim()}>
        {pending ? 'Ожидаем ответ…' : 'Отправить'}
      </button></div>
  </form>
}
