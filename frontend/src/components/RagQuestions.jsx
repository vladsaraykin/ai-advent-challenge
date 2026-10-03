import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { newMessageId } from '../messageId'
import { number, strategyLabels } from './ragUtils'

const labels = { WITHOUT_RAG: 'Без RAG', WITH_RAG: 'Базовый RAG', BOTH: 'Оба ответа',
  RERANKED: 'RAG + reranker', REWRITTEN: 'RAG + rewrite + reranker', COMPARE: 'Сравнить три режима RAG' }
const modesFor = mode => mode === 'COMPARE' ? ['WITH_RAG', 'RERANKED', 'REWRITTEN']
  : mode === 'BOTH' ? ['WITHOUT_RAG', 'WITH_RAG'] : [mode]
const decisions = { SELECTED: 'В контексте', THRESHOLD: 'Ниже порога', TOP_K: 'За пределами top-K', BUDGET: 'Не поместился в контекст', INVALID: 'Некорректная оценка', NOT_EVALUATED: 'Отбор не завершён' }

// Turn citations in prose (not code or existing links) into local references.
function citationPlugin() {
  return tree => {
    function walk(node) {
      if (!node.children || ['link', 'code', 'inlineCode'].includes(node.type)) return
      node.children = node.children.flatMap(child => {
        if (child.type !== 'text') { walk(child); return [child] }
        const parts = []; let end = 0
        for (const match of child.value.matchAll(/\[(\d+)\]/g)) {
          parts.push({ type: 'text', value: child.value.slice(end, match.index) },
            { type: 'link', url: `#source-${match[1]}`, children: [{ type: 'text', value: match[0] }] })
          end = match.index + match[0].length
        }
        parts.push({ type: 'text', value: child.value.slice(end) }); return parts
      })
    }
    walk(tree)
  }
}

export default function RagQuestions({ api, indexes }) {
  const [chosen, setChosen] = useState('')
  const index = indexes.find(item => item.id === chosen) || indexes[0]
  const [settings, setSettings] = useState(null)
  const [question, setQuestion] = useState('')
  const [mode, setMode] = useState('BOTH')
  const [retrievalOptions, setRetrievalOptions] = useState({ candidateK: 20, finalK: 5, threshold: 0.2 })
  const enhanced = ['RERANKED', 'REWRITTEN', 'COMPARE'].includes(mode)
  const [history, setHistory] = useState([])
  const [selected, setSelected] = useState('')
  const [current, setCurrent] = useState(null)
  const [pending, setPending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const stream = useRef(null)
  const busy = useRef(false)
  useEffect(() => () => stream.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    api.ragAnswerSettings(controller.signal).then(value => { if (!controller.signal.aborted) setSettings(value) })
      .catch(error => { if (!controller.signal.aborted) setError(error.message) })
    api.ragRetrievalSettings?.(controller.signal).then(value => { if (!controller.signal.aborted) setRetrievalOptions(value) })
      .catch(error => { if (!controller.signal.aborted) setError(error.message) })
    return () => controller.abort()
  }, [api, revision])
  useEffect(() => {
    setHistory([]); setSelected(''); setCurrent(null)
    if (!index) return
    const controller = new AbortController(); setLoading(true)
    api.ragQuestions(index.id, controller.signal).then(values => { if (!controller.signal.aborted) setHistory(values) })
      .catch(error => { if (!controller.signal.aborted) setError(error.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [api, index?.id, revision])

  async function ask(event) {
    event.preventDefault()
    if (busy.current || !index || !question.trim()) return
    if (enhanced && (retrievalOptions.finalK > retrievalOptions.candidateK)) { setError('Top-K после должен быть не больше top-K до.'); return }
    busy.current = true; setChosen(index.id); setPending(true); setError(''); setSelected('')
    const controller = new AbortController(); stream.current = controller
    const request = { id: newMessageId(), indexId: index.id, question: question.trim(), mode,
      ...(enhanced ? { retrievalOptions } : {}) }
    const modes = modesFor(mode)
    setCurrent({ ...request, answers: modes.map(mode => ({ mode, text: '', sources: [], phase: 'Ожидание', metrics: null })) })
    function update(mode, patch) {
      if (!controller.signal.aborted) setCurrent(value => ({ ...value, answers: value.answers.map(answer => answer.mode === mode ? patch(answer) : answer) }))
    }
    let completed = false
    try {
      await api.askRag(request, {
        signal: controller.signal,
        phase: value => update(value.mode, answer => ({ ...answer, phase: value.phase })),
        sources: value => update(value.mode, answer => ({ ...answer, sources: value.sources })),
        retrieval: value => update(value.mode, answer => ({ ...answer, retrieval: value.retrieval })),
        delta: value => update(value.mode, answer => ({ ...answer, text: answer.text + value.text })),
        answer: value => update(value.mode, () => value),
        completed: value => {
          completed = true
          if (!controller.signal.aborted) { setCurrent(value); setHistory(values => [value, ...values.filter(item => item.id !== value.id)]) }
        }
      })
      if (!completed && !controller.signal.aborted) throw new Error('Поток прерван. Обновите историю перед повторной отправкой.')
    } catch (error) {
      if (!controller.signal.aborted) {
        setError(error.message)
        setCurrent(value => ({ ...value, answers: value.answers.map(answer => answer.metrics || answer.error ? answer
          : { ...answer, text: '', phase: '', error: 'Ответ не завершён. Частичный текст не сохранён.' }) }))
      }
    } finally { busy.current = false; if (!controller.signal.aborted) setPending(false) }
  }

  const displayed = selected ? history.find(item => item.id === selected) : current
  return <section className="rag-panel rag-questions" aria-labelledby="rag-questions-title">
    <span className="eyebrow">День 24 · Источники и цитаты</span><h2 id="rag-questions-title">Вопрос к документу</h2>
    <p>Каждый вопрос независим. Одна модель и одинаковые параметры, но в режиме RAG добавляются найденные источники.</p>
    <p className="rag-note">RAG-ответ появляется после проверки цитат. Проверяется наличие фрагментов в чанках, а не истинность всех выводов.
      Если подходящего контекста нет — «не знаю» без генерации ответа. При ошибке reranker подмены базовым поиском нет.</p>
    <p>{settings?.model || 'Модель не настроена'} · top-K {settings?.topK ?? '—'} · лимит ответа {settings?.maxCompletionTokens ?? '—'} токенов</p>
    {!index && <p role="status">Сначала завершите индексацию документа.</p>}
    <form onSubmit={ask}>
      <label>Индекс для вопроса<select value={index?.id || ''} disabled={pending || !indexes.length} onChange={event => setChosen(event.target.value)}>
        {!indexes.length && <option value="">Нет завершённых индексов</option>}
        {indexes.map(item => <option key={item.id} value={item.id}>{strategyLabels[item.strategy]} · {new Date(item.createdAt).toLocaleString('ru-RU')} · {item.id.slice(0, 8)}</option>)}
      </select></label>
      <label>Режим ответа<select value={mode} disabled={pending} onChange={event => setMode(event.target.value)}>
        {Object.entries(labels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
      </select></label>
      {enhanced && <fieldset className="rag-retrieval-options" disabled={pending}><legend>Отбор источников</legend>
        <label>Top-K до reranker<input type="number" min="1" max="50" required value={retrievalOptions.candidateK}
          onChange={event => setRetrievalOptions(value => ({ ...value, candidateK: Number(event.target.value) }))} /></label>
        <label>Top-K после reranker<input type="number" min="1" max="20" required value={retrievalOptions.finalK}
          onChange={event => setRetrievalOptions(value => ({ ...value, finalK: Number(event.target.value) }))} /></label>
        <label>Порог reranker<input type="number" min="0" max="1" step="0.01" required value={retrievalOptions.threshold}
          onChange={event => setRetrievalOptions(value => ({ ...value, threshold: Number(event.target.value) }))} /></label>
        <p className="rag-note">Порог применяется к sigmoid-оценке reranker, а не к cosine similarity. Это не вероятность правильного ответа.
          В сравнении базовый RAG получает такой же top-K кандидатов и итоговый лимит, но без reranker и порога.</p>
      </fieldset>}
      <label>Ваш вопрос<textarea rows={3} maxLength={4000} value={question} disabled={pending} onChange={event => setQuestion(event.target.value)} required /></label>
      <button className="rag-primary" disabled={pending || loading || !index || !settings?.model || !question.trim()}>
        {pending ? 'Получаем ответ…' : mode === 'COMPARE' ? 'Сравнить три режима' : mode === 'BOTH' ? 'Получить оба ответа' : 'Получить ответ'}</button>
      <p className="rag-note">Вопрос и выбранные чанки отправляются вашему LLM-провайдеру. «Оба ответа» — до двух вызовов;
        «Сравнить три режима» — до трёх ответов и один rewrite, последовательно. При пустом контексте генерация пропускается.
        Rewrite тоже оплачивается. Переключение вкладки прервёт отображение потока; проверьте историю перед повтором.</p>
    </form>
    {error && <div role="alert" className="rag-failure">{error}</div>}
    <div className="rag-question-history"><button disabled={pending} onClick={() => { setError(''); setRevision(value => value + 1) }}>Обновить историю ответов</button>
      <label>Сохранённые вопросы<select disabled={pending || loading} value={selected} onChange={event => setSelected(event.target.value)}>
        <option value="">Текущий результат</option>{history.map(item => <option key={item.id} value={item.id}>{item.question.slice(0, 80)} · {item.status}</option>)}
      </select></label></div>
    {loading && <p role="status">Загружаем историю ответов…</p>}
    {displayed && <><blockquote className="rag-question-text">{displayed.question}</blockquote>
      {['INTERRUPTED', 'RUNNING'].includes(displayed.status) && <p role="status">Запрос {displayed.status === 'INTERRUPTED' ? 'прерван' : 'ещё выполняется'}. Обновите историю; сохранены только завершённые ответы.</p>}
      <div className={`rag-comparison ${displayed.answers.length === 3 ? 'rag-three' : ''}`}>{displayed.answers.map(answer => <Answer key={answer.mode} answer={answer} id={displayed.id} />)}</div></>}
  </section>
}

function Answer({ answer, id }) {
  const metrics = answer.metrics
  const retrieval = answer.retrieval
  const rewrite = retrieval?.rewriteMetrics
  function link({ href, children }) {
    const match = /^#source-(\d+)$/.exec(href || '')
    if (!match) return <span>{children}</span>
    const source = answer.sources.find(source => source.number === Number(match[1]))
    if (!source) return <span title="Ссылка не соответствует найденным источникам">{children} (источник не найден)</span>
    return <button className="rag-citation" onClick={() => {
      const element = document.getElementById(`source-${id}-${answer.mode}-${source.number}`)
      if (element) { element.open = true; element.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }) }
    }}>{children}</button>
  }
  return <article className="rag-answer" aria-label={labels[answer.mode]}>
    <h3>{labels[answer.mode]}</h3>
    {answer.phase && <p role="status">{answer.phase}…</p>}
    {answer.error && <p role="alert" className="rag-failure">{answer.error}</p>}
    <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm, citationPlugin]} skipHtml components={{ a: link, img: () => null }}>{answer.text}</ReactMarkdown></div>
    {answer.grounding && <section className="rag-evidence" aria-label="Подтверждения ответа">
      <h4>{answer.grounding.status === 'VERIFIED' ? 'Источники и проверенные цитаты' : 'Недостаточно контекста'}</h4>
      {answer.grounding.status === 'VERIFIED' && <p className="rag-note">Цитаты найдены в указанных чанках. Проверьте самостоятельно, подтверждают ли они смысл ответа.</p>}
      {answer.grounding.quotes.map((evidence, position) => {
        const source = answer.sources.find(item => item.number === evidence.sourceNumber)
        return <div key={position} className="rag-evidence-item">
          <blockquote>{evidence.quote}</blockquote>
          {source && <><p>[{source.number}] {source.chunk.source} · {source.chunk.section || 'Без раздела'}</p>
            <p className="rag-note">{source.chunk.pageStart != null && `Страницы ${source.chunk.pageStart}–${source.chunk.pageEnd} · `}chunk_id: {source.chunk.chunkId}</p>
            {link({ href: `#source-${source.number}`, children: 'Открыть полный чанк' })}</>}
        </div>
      })}
    </section>}
    {!answer.grounding && !answer.error && answer.metrics && answer.mode !== 'WITHOUT_RAG' && <p className="rag-note">Исторический ответ: обязательные цитаты ещё не проверялись.</p>}
    {metrics && <><p className="rag-note">{metrics.model} · {(metrics.generationMs / 1000).toFixed(2)} с ·
      вход {number(metrics.promptTokens)} / выход {number(metrics.completionTokens)} / всего {number(metrics.totalTokens)} токенов ·
      оценка стоимости: {metrics.costUsd == null ? 'неизвестна' : `$${metrics.costUsd.toFixed(6)}`}{metrics.finishReason === 'not_called' && ' · генерация не вызывалась'}</p>
      {metrics.finishReason === 'length' && <p role="alert">Ответ ограничен лимитом генерации.</p>}
      {answer.mode !== 'WITHOUT_RAG' && <p className="rag-note">Embedding: {metrics.embeddingMs} мс / {number(metrics.embeddingTokens)} токенов · поиск: {metrics.searchMs} мс
        {retrieval && ` · reranker: ${retrieval.rerankingMs} мс`}</p>}</>}
    {retrieval && <div className="rag-retrieval-trace"><p>Поисковый вопрос: {retrieval.searchQuery}</p>
      {metrics && <p className="rag-note">Время этапов (сумма): {((metrics.generationMs + metrics.embeddingMs + metrics.searchMs + retrieval.rerankingMs + (rewrite?.generationMs || 0)) / 1000).toFixed(2)} с
        {rewrite && ` · токены LLM (ответ + rewrite): ${number(metrics.totalTokens == null || rewrite.totalTokens == null ? null : metrics.totalTokens + rewrite.totalTokens)}`}</p>}
      {rewrite && <p className="rag-note">Rewrite: {(rewrite.generationMs / 1000).toFixed(2)} с · вход {number(rewrite.promptTokens)} / выход {number(rewrite.completionTokens)} токенов ·
        стоимость {rewrite.costUsd == null ? 'неизвестна' : `$${rewrite.costUsd.toFixed(6)}`}
        {metrics && ` · ответ + rewrite: ${metrics.costUsd == null || rewrite.costUsd == null ? 'неизвестна' : `$${(metrics.costUsd + rewrite.costUsd).toFixed(6)}`}`}</p>}
      <details><summary>Отбор чанков: найдено {retrieval.candidates.length}, передано {answer.sources.length}</summary>
        <p className="rag-note">Top-K до {retrieval.options.candidateK} / после {retrieval.options.finalK} · порог {retrieval.options.threshold}</p>
        {retrieval.candidates.map(candidate => <details key={candidate.source.chunk.chunkId}>
          <summary>#{candidate.source.number} · {candidate.source.chunk.section || candidate.source.chunk.source} · {decisions[candidate.decision]}</summary>
          <p>Cosine: {candidate.source.similarity.toFixed(4)} · reranker: {candidate.rerankerScore == null ? 'не применялся' : candidate.rerankerScore.toFixed(4)}</p>
          <pre className="rag-source">{candidate.source.chunk.content}</pre>
        </details>)}
      </details></div>}
    {answer.mode !== 'WITHOUT_RAG' && <div className="rag-sources"><h4>Источники, переданные модели</h4>
      {!answer.sources.length && <p>Контекст отсутствует: подходящие чанки не попали в запрос.</p>}
      {answer.sources.map(source => <details key={source.number} id={`source-${id}-${answer.mode}-${source.number}`}>
        <summary>[{source.number}] {source.chunk.source} · {source.chunk.section || 'Без раздела'}</summary>
        <p className="rag-note">Cosine similarity: {source.similarity.toFixed(4)} — не уверенность ответа.
          {source.chunk.pageStart != null && ` Страницы: ${source.chunk.pageStart}–${source.chunk.pageEnd}.`} Чанк: {source.chunk.chunkId}</p>
        <pre className="rag-source">{source.chunk.content}</pre>
      </details>)}</div>}
  </article>
}
