import { useMemo, useState } from 'react'

const COMMANDS = [
  { name: '/help', description: 'Задать вопрос о проекте по README и документации project/docs' },
  { name: '/model', description: 'Выбрать модель и уровень reasoning для этого чата' }
]

export default function MessageComposer({ draft, onChange, onSubmit, pending, disabled, mcpServers = [],
  mcpEnabled = false, mcpServerIds = [], onMcpEnabled = () => {}, onMcpServer = () => {}, showMcpControls = true,
  modelOptions = [], defaultModel = '', selectedModel = null, selectedReasoning = null, onModelSelection }) {
  const [activeCommand, setActiveCommand] = useState(-1)
  const [dismissed, setDismissed] = useState(false)
  const [modelDialog, setModelDialog] = useState(false)
  const [modelDraft, setModelDraft] = useState(selectedModel || '')
  const [reasoningDraft, setReasoningDraft] = useState(selectedReasoning || '')
  const commandPrefix = draft.startsWith('/') && !/\s/.test(draft) ? draft.toLowerCase() : null
  const suggestions = useMemo(() => commandPrefix === null ? [] : COMMANDS.filter(command =>
    command.name.startsWith(commandPrefix)), [commandPrefix])
  const showCommands = !dismissed && commandPrefix !== null
  const chooseCommand = command => {
    if (command.name === '/model' && onModelSelection && modelOptions.length) {
      setModelDraft(selectedModel || '')
      setReasoningDraft(selectedReasoning || '')
      setModelDialog(true)
      onChange('')
      setActiveCommand(-1)
      setDismissed(true)
      return
    }
    onChange(`${command.name} `)
    setActiveCommand(-1)
    setDismissed(true)
  }

  return <form className="composer" onSubmit={onSubmit}>
    {modelDialog && <section className="model-command-panel" aria-label="Выбор модели чата">
      <div><strong>Модель чата</strong><p>Выбор действует только для этого чата; reasoning — усилие модели, не её скрытая цепочка мыслей.</p></div>
      <label>Модель<select aria-label="Модель чата" value={modelDraft} disabled={pending || disabled}
        onChange={event => { setModelDraft(event.target.value); setReasoningDraft('') }}>
        <option value="">По умолчанию · {defaultModel}</option>
        {modelOptions.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
      </select></label>
      {(() => {
        const choice = modelOptions.find(option => option.id === (modelDraft || defaultModel))
        return <label>Reasoning<select aria-label="Уровень reasoning" value={reasoningDraft} disabled={pending || disabled || !choice?.reasoningEfforts?.length}
          onChange={event => setReasoningDraft(event.target.value)}>
          <option value="">По умолчанию модели{choice?.defaultReasoningEffort ? ` · ${choice.defaultReasoningEffort}` : ''}</option>
          {(choice?.reasoningEfforts || []).map(level => <option key={level} value={level}>{level}</option>)}
        </select>{!choice?.reasoningEfforts?.length && <small>Для этой модели настройка reasoning недоступна.</small>}</label>
      })()}
      <div className="model-command-actions"><button type="button" disabled={pending || disabled} onClick={() => setModelDialog(false)}>Отмена</button>
        <button type="button" disabled={pending || disabled} onClick={async () => {
          const saved = await onModelSelection({ model: modelDraft || null, reasoningEffort: reasoningDraft || null })
          if (saved !== false) setModelDialog(false)
        }}>Применить</button></div>
    </section>}
    {showMcpControls && <div className="mcp-composer-controls">
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
    </div>}
    <label htmlFor="message">Ваше сообщение</label>
    <div className="composer-input-wrap">
    {showCommands && <div id="chat-command-list" className="chat-command-list" role="listbox" aria-label="Команды чата">
      {suggestions.length ? suggestions.map((command, index) => <button key={command.name} type="button"
        role="option" aria-selected={activeCommand === index} className={activeCommand === index ? 'active' : ''}
        onMouseDown={event => event.preventDefault()} onClick={() => chooseCommand(command)}>
        <code>{command.name}</code><span>{command.description}</span>
      </button>) : <p>Нет такой команды. Доступные команды появятся при вводе «/».</p>}
    </div>}
    <textarea id="message" rows="3" value={draft} maxLength={12000} disabled={pending || disabled}
      placeholder="Опишите задачу или задайте уточняющий вопрос…"
      aria-controls={showCommands ? 'chat-command-list' : undefined}
      aria-expanded={showCommands}
      onChange={event => { onChange(event.target.value); setActiveCommand(-1); setDismissed(false) }}
      onKeyDown={event => {
        if (showCommands && suggestions.length && event.key === 'ArrowDown') {
          event.preventDefault()
          setActiveCommand(index => (index + 1) % suggestions.length)
          return
        }
        if (showCommands && suggestions.length && event.key === 'ArrowUp') {
          event.preventDefault()
          setActiveCommand(index => index <= 0 ? suggestions.length - 1 : index - 1)
          return
        }
        if (showCommands && event.key === 'Escape') {
          event.preventDefault()
          setDismissed(true)
          setActiveCommand(-1)
          return
        }
        if (showCommands && activeCommand >= 0 && event.key === 'Enter' && !event.shiftKey) {
          event.preventDefault()
          chooseCommand(suggestions[activeCommand])
          return
        }
        if (showCommands && draft === '/model' && suggestions.some(command => command.name === '/model')
            && event.key === 'Enter' && !event.shiftKey && onModelSelection && modelOptions.length) {
          event.preventDefault()
          chooseCommand(COMMANDS.find(command => command.name === '/model'))
          return
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
          event.preventDefault()
          if (!pending && !disabled && draft.trim()) onSubmit(event)
        }
      }} />
    </div>
    <div className="composer-actions"><small>{draft.length.toLocaleString('ru-RU')} / 12 000 · Enter — отправить · Shift + Enter — новая строка</small>
      <button type="submit" disabled={pending || disabled || !draft.trim()}>
        {pending ? 'Ожидаем ответ…' : 'Отправить'}
      </button></div>
  </form>
}
