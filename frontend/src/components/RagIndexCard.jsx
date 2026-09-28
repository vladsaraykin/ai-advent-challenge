import { useEffect, useState } from 'react'
import { activeRun, number, strategyLabels } from './ragUtils'

const states = { PENDING: 'Ожидание / подготовка', EXTRACTING: 'Извлечение', CHUNKING: 'Разбиение', EMBEDDING: 'Генерация эмбеддингов', COMPLETED: 'Готово', FAILED: 'Ошибка' }
const errors = {
  INTERRUPTED: 'Обработка прервана перезапуском сервера. Создайте новый запуск.',
  OLLAMA_UNAVAILABLE: 'Ollama недоступна или не ответила вовремя. Проверьте сервер и туннель.',
  OLLAMA_INPUT_REJECTED: 'Ollama отклонила чанк. Возможно превышен контекст: уменьшите размер чанков в настройках backend.',
  OLLAMA_REQUEST_FAILED: 'Ollama отклонила запрос. Проверьте имя модели и её доступность.',
  INVALID_EMBEDDING: 'Модель вернула некорректный вектор. Ожидается размерность 768.',
  INVALID_EMBEDDING_USAGE: 'Модель вернула некорректную статистику токенов.',
  EMPTY_INDEX: 'Не удалось сформировать чанки из текста.',
  SCHEDULING_FAILED: 'Не удалось поставить запуск в очередь.',
  INDEXING_FAILED: 'Сбой подготовки или сохранения индекса. Проверьте серверные журналы.'
}

export default function RagIndexCard({ api, strategy, runs, disabled, starting, onStart }) {
  const [chosen, setChosen] = useState('')
  const run = runs.find(item => item.id === chosen) || runs[0]
  const busy = runs.some(activeRun)
  const title = strategyLabels[strategy]
  return <section className="rag-panel rag-index-card" aria-label={title}>
    <div className="rag-card-heading"><span className="eyebrow">{strategy === 'FIXED_SIZE' ? 'Стратегия 01' : 'Стратегия 02'}</span>
      <h3>{title}</h3><p>{strategy === 'FIXED_SIZE'
        ? 'Окна ограниченного размера с перекрытием. Заголовки не задают границы.'
        : 'Границы по заголовкам и разделам. Длинные разделы делятся на окна с перекрытием.'}</p></div>
    <button className="rag-primary" disabled={disabled || busy} onClick={async () => {
      const created = await onStart()
      if (created) setChosen(created.id)
    }}>
      {starting ? 'Отправляем…' : busy ? 'Индексация выполняется…' : run ? 'Создать новый запуск' : 'Индексировать'}</button>
    {starting && <p role="status">Запрос на индексацию отправлен.</p>}
    {runs.length > 0 && <label className="rag-run-picker">Запуск · {title}<select value={run.id} onChange={event => setChosen(event.target.value)}>
      {runs.map(item => <option key={item.id} value={item.id}>{new Date(item.createdAt).toLocaleString('ru-RU')} · {states[item.status] || item.status} · {item.id.slice(0, 8)}</option>)}
    </select></label>}
    {!run && <p className="rag-empty">Индексов ещё нет. Запустите обработку документа.</p>}
    {run && <>
      <p className={`rag-run-status ${run.status.toLowerCase()}`} role="status">{states[run.status] || run.status} · {number(run.embeddedChunks)} / {number(run.totalChunks)} чанков</p>
      {activeRun(run) && <progress aria-label={`Прогресс ${title}`} max={Math.max(1, run.totalChunks)} value={run.totalChunks ? run.embeddedChunks : undefined} />}
      {run.status === 'FAILED' && <div className="rag-failure" role="alert"><p>{errors[run.errorCode] || 'Индексация завершилась с ошибкой.'}</p>
        <code>{run.errorCode}</code><p>Предыдущие результаты сохранены. Новый запуск начнёт обработку заново.</p></div>}
      <dl className="rag-metrics">
        <div><dt>Чанки</dt><dd>{number(run.totalChunks)}</dd></div>
        <div><dt>Токены Ollama</dt><dd>{number(run.promptTokens)}</dd></div>
        <div><dt>Время обработки</dt><dd>{run.durationMs == null ? '—' : `${number(Math.round(run.durationMs / 10) / 100)} с`}</dd></div>
        <div><dt>Размерность</dt><dd>{number(run.dimensions)}</dd></div>
      </dl>
      <p className="rag-note">Модель: {run.embeddingModel}. Время без ожидания очереди; первый запуск может включать загрузку модели.</p>
      <details><summary>Идентификатор запуска</summary><code className="rag-id">{run.id}</code></details>
      {run.totalChunks > 0 && <ChunkBrowser key={run.id} api={api} run={run} />}
    </>}
  </section>
}

function ChunkBrowser({ api, run }) {
  const [offset, setOffset] = useState(0)
  const [chunks, setChunks] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setChunks([])
    api.ragChunks(run.id, offset, controller.signal).then(value => { if (!controller.signal.aborted) setChunks(value) })
      .catch(error => { if (!controller.signal.aborted) setError(error.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [api, run.id, offset, revision])
  return <div className="rag-chunks"><h4>3. Сохранённые чанки</h4>
    {loading && <p role="status">Загружаем чанки…</p>}
    {error && <div role="alert"><p>{error}</p><button onClick={() => setRevision(value => value + 1)}>Повторить загрузку чанков</button></div>}
    <div className="rag-chunk-list">{chunks.map(chunk => <details key={chunk.chunkId}>
      <summary>Чанк {chunk.ordinal + 1} · ≈{number(chunk.estimatedTokens)} токенов</summary>
      <dl className="rag-chunk-meta">
        <div><dt>source</dt><dd>{chunk.source}</dd></div><div><dt>title</dt><dd>{chunk.title}</dd></div>
        <div><dt>section</dt><dd>{chunk.section || 'Без раздела'}</dd></div>
        <div><dt>chunk_id</dt><dd>{chunk.chunkId}</dd></div>
        {chunk.pageStart != null && <div><dt>Страницы</dt><dd>{chunk.pageStart}–{chunk.pageEnd}</dd></div>}
        <div><dt>Смещения UTF-16</dt><dd>[{chunk.startOffset}, {chunk.endOffset})</dd></div>
      </dl><pre className="rag-source">{chunk.content}</pre>
    </details>)}</div>
    <div className="rag-pagination"><button aria-label="Предыдущие чанки" disabled={!offset || loading} onClick={() => setOffset(value => value - 10)}>← Назад</button>
      <span>{offset + 1}–{Math.min(offset + 10, run.totalChunks)} из {number(run.totalChunks)}</span>
      <button aria-label="Следующие чанки" disabled={offset + 10 >= run.totalChunks || loading} onClick={() => setOffset(value => value + 10)}>Далее →</button></div>
    <p className="rag-note">Размер текста оценён через cl100k_base. Чанки FAILED-запуска могут ещё не иметь векторов.</p>
  </div>
}
