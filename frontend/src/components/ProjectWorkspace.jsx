import { useEffect, useState } from 'react'

export default function ProjectWorkspace({ api, agentId, chatId, pending, revision }) {
  const [workspace, setWorkspace] = useState(null)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setWorkspace(null); setError('')
    if (!chatId || !api.projectWorkspace) return
    const load = () => api.projectWorkspace(agentId, chatId).then(result => {
      if (active) { setWorkspace(result.workspace || null); setError('') }
    }).catch(() => { if (active) setError('Не удалось загрузить рабочую область проекта.') })
    load()
    const timer = pending ? setInterval(load, 3000) : null
    return () => { active = false; if (timer) clearInterval(timer) }
  }, [api, agentId, chatId, pending, revision, refresh])
  if (!workspace && !error) return null
  const state = workspace?.state || {}
  const prUrl = /^https:\/\/github\.com\/vladsaraykin\/ai-advent-challenge\/pull\/\d+$/.test(state.prUrl || '') ? state.prUrl : null
  return <section className="project-workspace" aria-label="Рабочая область проекта">
    <h3>Рабочая область задачи</h3>
    {error && <p role="alert">{error}</p>}
    {workspace && <>
      <p>Ветка: <code>{state.branch || 'Подготовка…'}</code> · версия области {workspace.revision}</p>
      {state.relativePath && <p>Каталог: <code>{state.relativePath}</code>. Основной checkout не изменяется.</p>}
      {state.headSha && <p>Commit: <code>{state.headSha}</code></p>}
      {state.status ? <details><summary>Изменённые файлы</summary><pre>{state.status}</pre></details>
        : state.headSha && <p>Рабочая копия без незакоммиченных изменений.</p>}
      {state.diff && <details><summary>Diff задачи{state.truncated ? ' (сокращён)' : ''}</summary><pre>{state.diff}</pre></details>}
      {state.lastCheck && <p>Последняя проверка: {state.lastCheck.check} — {state.lastCheck.passed ? 'успешно' : 'не пройдена'}.
        Результат относится к снимку <code>{state.lastCheck.treeHash?.slice(0, 12)}</code>, не ко всем будущим правкам.</p>}
      {prUrl && <p><a href={prUrl} target="_blank" rel="noreferrer">Открыть Pull Request в main</a></p>}
    </>}
    <button type="button" onClick={() => setRefresh(value => value + 1)}>Обновить рабочую область</button>
    <small>Удаление чата не удаляет ветку и worktree. Push, PR и удаление файла подтверждаются отдельно.</small>
  </section>
}
