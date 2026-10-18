import { useId, useState } from 'react'
import UserProfile from './UserProfile'

function ToolCard({ tool }) {
  return <li className="mcp-tool">
    <div className="mcp-tool-heading"><div><h3>{tool.title || tool.name}</h3>
      <code>{tool.name}</code></div>
      <div className="mcp-tool-badges">
        {tool.readOnly === true && <span>только чтение</span>}
        {tool.destructive === true && <span className="danger">может изменять данные</span>}
      </div>
    </div>
    <p>{tool.description || 'Описание не предоставлено MCP-сервером'}</p>
    {tool.inputSchema && Object.keys(tool.inputSchema).length > 0 && <details>
      <summary>Схема параметров</summary>
      <pre>{JSON.stringify(tool.inputSchema, null, 2)}</pre>
    </details>}
  </li>
}

function ServerItem({ server }) {
  const [expanded, setExpanded] = useState(false)
  const id = useId()
  const tools = server.tools || []
  return <article className="mcp-server">
    <header className="mcp-server-header">
      <h2 aria-label={server.name}><button type="button" className="mcp-server-toggle" id={`${id}-toggle`}
        aria-label={`Инструменты ${server.name}`} aria-expanded={expanded} aria-controls={`${id}-tools`}
        onClick={() => setExpanded(value => !value)}>
        <span className="mcp-server-identity"><span className="mcp-server-name">{server.name}</span>
          <code>{server.id}</code></span>
        <span className="mcp-server-summary"><span className={server.connected ? 'mcp-status connected' : 'mcp-status'}>
          {server.connected ? 'Подключён' : 'Недоступен'}</span>
          <span className="mcp-tool-count">Инструменты: {tools.length}</span>
          <span className="mcp-server-chevron" aria-hidden="true">{expanded ? '▾' : '▸'}</span></span>
      </button></h2>
    </header>
    {server.error && <p className="mcp-server-error" role="alert">{server.error}</p>}
    <div id={`${id}-tools`} role="region" aria-labelledby={`${id}-toggle`} hidden={!expanded}>
      {expanded && <>
        <dl className="mcp-server-metadata"><div><dt>Версия</dt><dd>{server.version || '—'}</dd></div>
          <div><dt>Протокол</dt><dd>{server.protocolVersion || '—'}</dd></div></dl>
        {!server.error && (tools.length
          ? <ul className="mcp-tools">{tools.map(tool => <ToolCard key={tool.name} tool={tool} />)}</ul>
          : <p className="mcp-no-tools">Сервер не предоставил инструментов.</p>)}
      </>}
    </div>
  </article>
}

export default function McpCatalog({ api, profile, onProfile, onLogout, servers = [], loading, error, onReload, embedded = false }) {
  return <section className="mcp-page" aria-labelledby="mcp-title">
    <header className="conversation-header"><div><span className="eyebrow">Model Context Protocol</span>
      <h1 id="mcp-title">MCP-подключения</h1>
      <p>Серверы и инструменты, обнаруженные через Model Context Protocol.</p></div>
      {!embedded && <UserProfile profile={profile} api={api} disabled={loading} onProfile={onProfile} onLogout={onLogout} />}
    </header>
    <div className="mcp-content">
      <div className="mcp-intro"><div><strong>{servers.filter(server => server.connected).length}</strong><span>активных подключений</span></div>
        <p>Приложение выполняет MCP handshake и запрашивает <code>tools/list</code>. В чате можно выбрать несколько серверов: модель сможет использовать их инструменты в одном запросе.</p></div>
      {loading && <div className="loading-state" role="status">Подключаемся к MCP-серверам и загружаем инструменты…</div>}
      {error && <div className="error-banner" role="alert"><span>{error}</span>
        <button type="button" onClick={onReload}>Повторить</button></div>}
      {!loading && !error && !servers.length && <div className="mcp-empty" role="status">
        <h2>MCP-серверы не настроены</h2><p>Добавьте соединение в серверную конфигурацию и перезапустите приложение.</p>
      </div>}
      {!loading && !error && <div className="mcp-server-list">{servers.map(server =>
        <ServerItem key={server.id} server={server} />)}</div>}
    </div>
  </section>
}
