import { useEffect, useState } from 'react'

export default function ReportDownloads({ api, refreshKey }) {
  const [files, setFiles] = useState([])
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(false)
  const [downloading, setDownloading] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    if (!api.reports) return
    let active = true
    setLoading(true); setError('')
    api.reports().then(items => { if (active) setFiles(items) })
      .catch(e => { if (active) { setError(e.message); setFiles([]) } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [api, refreshKey, reload])
  async function download(name) {
    setDownloading(name); setError('')
    try { await api.downloadReport(name) }
    catch (e) { setError(e.message) }
    finally { setDownloading('') }
  }
  return <details className="report-downloads">
    <summary>Скачать отчёты · {files.length}</summary>
    <p>Общая папка отчётов — доступна всем пользователям приложения.</p>
    <button type="button" disabled={loading} onClick={() => setReload(v => v + 1)}>Обновить файлы</button>
    {loading && <p role="status">Загружаем список файлов…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && !files.length && <p>Отчётов пока нет. Попросите агента сохранить файл в общую папку.</p>}
    <ul>{files.map(file => <li key={file.name}>
      <span>{file.name} · {(file.sizeBytes / 1024).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} КБ</span>
      <button type="button" disabled={!!downloading} onClick={() => download(file.name)}
        aria-label={`Скачать ${file.name}`}>{downloading === file.name ? 'Скачиваем…' : 'Скачать'}</button>
    </li>)}</ul>
  </details>
}
