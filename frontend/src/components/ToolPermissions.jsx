import { useEffect, useState } from 'react'

export default function ToolPermissions({ api, agentId, chatId, revision, disabled }) {
  const [permissions, setPermissions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    api.toolPermissions(agentId, chatId).then(value => { if (active) setPermissions(value) })
      .catch(() => { if (active) setError('Не удалось загрузить разрешения инструментов.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [api, agentId, chatId, revision])

  async function revoke(permission) {
    setBusy(true); setError('')
    try {
      await api.revokeToolPermission(agentId, chatId, permission.server, permission.tool)
      setPermissions(values => values.filter(value => value.server !== permission.server || value.tool !== permission.tool))
    } catch { setError('Не удалось отозвать разрешение. Повторите попытку.') }
    finally { setBusy(false) }
  }
  return <section aria-label="Постоянные разрешения MCP">
    <h4>Разрешено без повторных вопросов</h4>
    {loading ? <p role="status">Загружаем разрешения…</p> : <>
      {!permissions.length && !error && <p>Постоянных разрешений нет.</p>}
      <ul>{permissions.map(permission => <li key={`${permission.server}/${permission.tool}`}>
        <code>{permission.server}/{permission.tool}</code>{' '}
        <button type="button" disabled={disabled || busy} aria-label={`Отозвать разрешение ${permission.server}/${permission.tool}`}
          onClick={() => revoke(permission)}>Отозвать</button>
      </li>)}</ul>
    </>}
    {error && <p role="alert">{error}</p>}
    <p>Только этот чат и инструмент; для любых параметров. Изменение схемы или описания требует нового разрешения.
      Уже начатый вызов отзыв не отменяет.</p>
  </section>
}
