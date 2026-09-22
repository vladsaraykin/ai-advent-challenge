export default function MessageComposer({ draft, onChange, onSubmit, pending, disabled, mcpServers = [],
  mcpEnabled = false, mcpServerId = '', onMcpEnabled = () => {}, onMcpServer = () => {} }) {
  return <form className="composer" onSubmit={onSubmit}>
    <div className="mcp-composer-controls">
      <label className="mcp-toggle"><input type="checkbox" checked={mcpEnabled}
        disabled={pending || disabled || !mcpServers.length}
        onChange={event => onMcpEnabled(event.target.checked)} />
        Разрешить агенту MCP-инструменты</label>
      {mcpEnabled && <label>Сервер MCP<select aria-label="Сервер MCP" value={mcpServerId}
        disabled={pending || disabled} onChange={event => onMcpServer(event.target.value)}>
        {mcpServers.map(server => <option value={server.id} key={server.id}>
          {server.name} · {server.tools.length} инстр.
        </option>)}
      </select></label>}
      {!mcpServers.length && <small>MCP-серверы с инструментами не подключены</small>}
    </div>
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
