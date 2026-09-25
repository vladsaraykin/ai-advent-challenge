export default function MessageComposer({ draft, onChange, onSubmit, pending, disabled, mcpServers = [],
  mcpEnabled = false, mcpServerIds = [], onMcpEnabled = () => {}, onMcpServer = () => {} }) {
  return <form className="composer" onSubmit={onSubmit}>
    <div className="mcp-composer-controls">
      <label className="mcp-toggle"><input type="checkbox" checked={mcpEnabled}
        disabled={pending || disabled || !mcpServers.length}
        onChange={event => onMcpEnabled(event.target.checked)} />
        Разрешить агенту MCP-инструменты</label>
      {mcpEnabled && <fieldset disabled={pending || disabled}>
        <legend>Серверы MCP — выберите до 8</legend>
        {mcpServers.map(server => <label key={server.id} className="mcp-toggle">
          <input type="checkbox" checked={mcpServerIds.includes(server.id)}
            disabled={!mcpServerIds.includes(server.id) && mcpServerIds.length >= 8}
            onChange={event => onMcpServer(event.target.checked
              ? [...mcpServerIds, server.id] : mcpServerIds.filter(id => id !== server.id))} />
          {server.name} · {server.tools.length} инстр.
        </label>)}
      </fieldset>}
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
