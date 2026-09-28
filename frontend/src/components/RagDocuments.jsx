import { useEffect, useRef, useState } from 'react'
import UserProfile from './UserProfile'
import RagIndexCard from './RagIndexCard'
import { strategyLabels, activeRun, number } from './ragUtils'
import './rag.css'

function ErrorNotice({ message, retry }) {
  return message && <div className="error-banner" role="alert"><span>{message}</span>
    {retry && <button onClick={retry}>Повторить загрузку</button>}</div>
}

export default function RagDocuments({ api, profile, onProfile, onLogout }) {
  const [documents, setDocuments] = useState([])
  const [selected, setSelected] = useState('')
  const [offset, setOffset] = useState(0)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [file, setFile] = useState(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const uploadController = useRef(null)
  const input = useRef(null)
  useEffect(() => () => uploadController.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setDocuments([])
    api.ragDocuments(offset, controller.signal).then(items => {
      if (!controller.signal.aborted) { setDocuments(items); setSelected(current => current || items[0]?.id || '') }
    }).catch(error => {
      if (!controller.signal.aborted) setError(error.status === 404
        ? 'RAG недоступен. Проверьте RAG_ENABLED и настройки PostgreSQL на сервере.' : error.message)
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [api, offset, revision])

  async function upload(event) {
    event.preventDefault()
    if (!file || uploading) return
    if (!/\.(pdf|doc|docx)$/i.test(file.name) || !file.size || file.size > 20 * 1024 * 1024) {
      setUploadError('Выберите непустой PDF, DOC или DOCX размером до 20 МиБ.'); return
    }
    const controller = new AbortController()
    uploadController.current = controller
    setUploading(true); setUploadError('')
    try {
      const result = await api.uploadRagDocument(file, controller.signal)
      if (controller.signal.aborted) return
      setSelected(result.document.id); setOffset(0); setRevision(value => value + 1)
      setFile(null); input.current.value = ''
    } catch (error) { if (!controller.signal.aborted) setUploadError(error.message) }
    finally { if (!controller.signal.aborted) setUploading(false) }
  }

  return <section className="rag-page" aria-labelledby="rag-title">
    <header className="conversation-header"><div><span className="eyebrow">День 21 · Индексация документов</span>
      <h1 id="rag-title">Лаборатория RAG</h1><p>Документ → чанки → эмбеддинги → PostgreSQL</p></div>
      <UserProfile api={api} profile={profile} onProfile={onProfile} onLogout={onLogout} disabled={uploading} />
    </header>
    <div className="rag-content">
      <form className="rag-panel rag-upload" onSubmit={upload}>
        <div><h2>1. Загрузите документ</h2><p>Один PDF или Word до 20 МиБ. Для сканов OCR не предусмотрен. Документы доступны только вашему профилю.</p></div>
        <label>Файл документа<input ref={input} type="file" accept=".pdf,.doc,.docx" disabled={uploading}
          onChange={event => { setFile(event.target.files[0] || null); setUploadError('') }} /></label>
        <button className="rag-primary" disabled={!file || uploading || !!error}>{uploading ? 'Загрузка и извлечение…' : 'Загрузить документ'}</button>
        {uploading && <p role="status">Сервер принимает файл и извлекает текст. Это может занять некоторое время.</p>}
        <ErrorNotice message={uploadError} />
      </form>
      <ErrorNotice message={error} retry={() => setRevision(value => value + 1)} />
      {loading && <p role="status">Загружаем документы…</p>}
      {!error && <div className="rag-document-picker">
        <label>Мои документы<select value={documents.some(item => item.id === selected) ? selected : ''}
          disabled={loading || !documents.length} onChange={event => setSelected(event.target.value)}>
          <option value="" disabled>{documents.length ? 'Выберите документ' : 'Документы пока не загружены'}</option>
          {documents.map(item => <option key={item.id} value={item.id}>{item.filename} · {number(item.characterCount)} символов</option>)}
        </select></label>
        <div className="rag-pagination"><button disabled={!offset || loading} onClick={() => { setSelected(''); setOffset(value => value - 20) }}>Предыдущие документы</button>
          <button disabled={documents.length < 20 || loading} onClick={() => { setSelected(''); setOffset(value => value + 20) }}>Следующие документы</button></div>
      </div>}
      {!loading && !error && !selected && <p className="rag-empty">Загрузите первый документ, чтобы увидеть его текст и две стратегии индексации.</p>}
      {selected && !error && <DocumentWorkspace key={selected} api={api} id={selected} />}
    </div>
  </section>
}

function DocumentWorkspace({ api, id }) {
  const [document, setDocument] = useState(null)
  const [runs, setRuns] = useState([])
  const [error, setError] = useState('')
  const [runError, setRunError] = useState('')
  const [startError, setStartError] = useState('')
  const [runsLoading, setRunsLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  const [starting, setStarting] = useState('')
  const [preview, setPreview] = useState(null)
  const [previewBusy, setPreviewBusy] = useState(false)
  const [previewError, setPreviewError] = useState('')
  const lifecycle = useRef(null)
  useEffect(() => {
    const controller = new AbortController(); lifecycle.current = controller
    return () => controller.abort()
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    api.ragDocument(id, controller.signal).then(value => { if (!controller.signal.aborted) setDocument(value) })
      .catch(error => { if (!controller.signal.aborted) setError(error.message) })
    return () => controller.abort()
  }, [api, id, revision])
  useEffect(() => {
    const controller = new AbortController()
    let timer
    setRunsLoading(true)
    async function refresh() {
      try {
        const values = await api.ragIndexes(id, controller.signal)
        if (controller.signal.aborted) return
        setRuns(values); setRunError('')
        if (values.some(activeRun)) timer = setTimeout(refresh, 1500)
      } catch (error) { if (!controller.signal.aborted) setRunError(error.message) }
      finally { if (!controller.signal.aborted) setRunsLoading(false) }
    }
    refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [api, id, revision])

  async function start(strategy) {
    if (starting) return
    const signal = lifecycle.current.signal
    setStarting(strategy); setStartError('')
    try {
      const result = await api.startRagIndex(id, strategy, signal)
      if (!signal.aborted) {
        setRuns(values => [result, ...values.filter(value => value.id !== result.id)])
        return result
      }
    } catch (error) { if (!signal.aborted) setStartError(error.message + ' Проверьте список запусков перед повторной отправкой.') }
    finally { if (!signal.aborted) { setStarting(''); setRevision(value => value + 1) } }
  }
  async function showPreview() {
    const signal = lifecycle.current.signal
    setPreviewBusy(true); setPreviewError('')
    try { const value = await api.ragPreview(id, signal); if (!signal.aborted) setPreview(value) }
    catch (error) { if (!signal.aborted) setPreviewError(error.message) }
    finally { if (!signal.aborted) setPreviewBusy(false) }
  }

  return <div className="rag-workspace">
    <ErrorNotice message={error} retry={() => setRevision(value => value + 1)} />
    {!document && !error && <p role="status">Извлекаем сохранённый текст…</p>}
    {document && <section className="rag-panel">
      <h2>{document.document.filename}</h2>
      <p>{number(document.document.characterCount)} символов · {number(Math.ceil(document.document.byteSize / 1024))} КиБ
        {document.metadata.pageCount != null && ` · ${number(document.metadata.pageCount)} страниц`}</p>
      {document.metadata.warnings?.map(warning => <p className="rag-note" key={warning}>{warning}</p>)}
      <details><summary>Извлечённый текст</summary><pre className="rag-source">{document.text}</pre></details>
      <button disabled={previewBusy} onClick={showPreview}>{previewBusy ? 'Разбиваем текст…' : 'Предпросмотр chunking без Ollama'}</button>
      <ErrorNotice message={previewError} />
      {previewBusy && <p role="status">Готовим статистику двух стратегий…</p>}
      {preview && <div className="rag-preview">
        <p>Текущие настройки сервера: до {preview.settings.maxEstimatedTokens} оценочных токенов; overlap {preview.settings.overlapEstimatedTokens}.
          Это оценка cl100k_base, не фактические токены Ollama. Сохранённые запуски могли использовать другие настройки.</p>
        <div className="rag-comparison">{preview.results.map(result => <div key={result.strategy}>
          <h3>{strategyLabels[result.strategy]}</h3><p>{number(result.statistics.chunkCount)} чанков · {number(result.statistics.totalEstimatedTokens)} оценочных токенов</p>
          <p>Размер чанка: {result.statistics.minEstimatedTokens}–{result.statistics.maxEstimatedTokens} токенов</p>
          <details><summary>Первые чанки</summary>{result.chunks.map(chunk => <pre className="rag-source" key={chunk.chunkId}>{chunk.content}</pre>)}</details>
        </div>)}</div>
        {preview.warnings.map(warning => <p className="rag-note" key={warning}>{warning}</p>)}
      </div>}
    </section>}
    <div className="rag-section-heading"><div><h2>2. Индексируйте двумя способами</h2>
      <p>Отдельные запуски, один фоновый поток. Можно переключать вкладки — обработка продолжится на сервере.</p></div>
      <button onClick={() => setRevision(value => value + 1)}>Обновить запуски</button></div>
    <ErrorNotice message={runError} />
    <ErrorNotice message={startError} />
    {runsLoading && <p role="status">Проверяем запуски…</p>}
    <div className="rag-comparison">{Object.keys(strategyLabels).map(strategy => <RagIndexCard key={strategy}
      api={api} strategy={strategy} runs={runs.filter(run => run.strategy === strategy)}
      disabled={!document || !!starting || runsLoading || !!runError} starting={starting === strategy} onStart={() => start(strategy)} />)}</div>
    <p className="rag-note">Готовым считается только COMPLETED-индекс. Токены включают успешно сохранённые эмбеддинги.
      Оценку качества вы выполняете самостоятельно; поиск и ответы по документам пока не подключены.</p>
  </div>
}
