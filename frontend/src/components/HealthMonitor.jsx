import { useCallback, useEffect, useState } from 'react'
import './health.css'

const labels = { UP: 'Работает', DOWN: 'Недоступна', DISABLED: 'Отключена', NOT_CONFIGURED: 'Не настроена', UNKNOWN: 'Нет данных' }

export default function HealthMonitor({ api }) {
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const refresh = useCallback(async () => {
    setLoading(true); setError('')
    try { setReport(await api.health()) } catch (exception) { setError(exception.message || 'Не удалось проверить зависимости') }
    finally { setLoading(false) }
  }, [api])
  useEffect(() => { refresh() }, [refresh])

  return <section className="health-page" aria-labelledby="health-title">
    <header className="conversation-header"><div><span className="eyebrow">Состояние интеграций</span>
      <h1 id="health-title">Мониторинг здоровья</h1>
      <p>Проверка соединения приложения с базой данных, LLM и внешними сервисами.</p></div>
      <div className="health-heading-actions"><button type="button" onClick={refresh} disabled={loading}>{loading ? 'Проверяем…' : 'Проверить сейчас'}</button></div>
    </header>
    <div className="health-content">
      {loading && !report && <p className="health-message" role="status">Проверяем доступность подключённых систем…</p>}
      {error && <div className="error-banner" role="alert"><span>{error}</span><button type="button" onClick={refresh}>Повторить</button></div>}
      {report && <>
        <div className={`health-overall health-${report.status?.toLowerCase()}`} role="status">
          <span className="health-indicator" aria-hidden="true" />
          <div><strong>{report.status === 'UP' ? 'Системы доступны' : report.status === 'DEGRADED' ? 'Обнаружены проблемы' : 'Статус неизвестен'}</strong>
            <small>Проверено: {new Date(report.checkedAt).toLocaleString()}</small></div>
          {loading && <small>Обновляем…</small>}
        </div>
        <div className="health-check-list">{report.checks.map(check => <article className="health-check" key={check.id}>
          <div className="health-check-main"><span className={`health-dot health-dot-${check.status.toLowerCase()}`} aria-hidden="true" />
            <div><h2>{check.name}</h2><p>{check.message}</p></div></div>
          <div className="health-check-meta"><strong className={`health-state health-state-${check.status.toLowerCase()}`}>{labels[check.status] || check.status}</strong>
            <small>{check.durationMs} мс</small></div>
        </article>)}</div>
        <p className="health-footnote">Проверки не генерируют ответы LLM и не изменяют данные. Для OpenAI используется запрос списка моделей; Ollama проверяется по наличию embedding-модели.</p>
      </>}
    </div>
  </section>
}
