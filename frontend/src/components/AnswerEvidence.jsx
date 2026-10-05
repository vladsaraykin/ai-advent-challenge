export default function AnswerEvidence({ evidence }) {
  if (!evidence) return null
  const { sources = [], grounding, retrieval } = evidence
  return <section className="answer-evidence" aria-label="Источники ответа">
    {grounding?.status === 'INSUFFICIENT_CONTEXT' && <p className="insufficient-context">Недостаточно подтверждённого контекста. Уточните вопрос или загрузите дополнительные документы.</p>}
    {grounding?.quotes?.length > 0 && <details><summary>Подтверждающие цитаты · {grounding.quotes.length}</summary>
      {grounding.quotes.map((quote, i) => <blockquote key={i}>{quote.quote || quote.text}<small>Источник [{quote.sourceNumber}]</small></blockquote>)}
    </details>}
    {!!sources.length && <details><summary>Источники · {sources.length}</summary>
      {sources.map(source => <details key={source.number} className="source-chunk">
        <summary>[{source.number}] {source.chunk?.source || source.chunk?.filename || 'Источник'}
          {source.chunk?.section && <> · {source.chunk.section}</>}</summary>
        <small>chunk_id: {source.chunk?.id || source.chunk?.chunkId} · {source.chunk?.title || source.chunk?.filename}</small>
        <pre>{source.chunk?.content}</pre>
      </details>)}
    </details>}
    {retrieval && <details><summary>Как найден контекст</summary>
      <p>Поисковый запрос: {retrieval.searchQuery || retrieval.query}</p>
      <p>Top-K до: {retrieval.options?.candidateK} · после: {retrieval.options?.finalK} · кандидатов: {retrieval.candidates?.length || 0} · порог: {retrieval.options?.threshold}</p>
      {retrieval.rewriteMetrics && <p>Rewrite: {formatTokens(retrieval.rewriteMetrics.totalTokens)} токенов · {formatUsd(retrieval.rewriteMetrics.costUsd)} ·
        {' '}{retrieval.rewriteMetrics.generationMs} мс. Embedding: {formatTokens(retrieval.rewriteMetrics.embeddingTokens)} токенов · {retrieval.rewriteMetrics.embeddingMs} мс.
        Поиск: {retrieval.rewriteMetrics.searchMs} мс · reranker: {retrieval.rerankingMs} мс.</p>}
      {retrieval.candidates?.length > 0 && <div className="retrieval-candidates"><table><thead><tr><th>Фрагмент</th><th>Cosine</th><th>Reranker</th><th>Решение</th></tr></thead>
        <tbody>{retrieval.candidates.map((candidate, index) => <tr key={index}><td>{candidate.source?.chunk?.source} · {candidate.source?.chunk?.section}</td>
          <td>{candidate.source?.similarity?.toFixed(3)}</td><td>{candidate.rerankerScore?.toFixed(3)}</td><td>{candidate.decision}</td></tr>)}</tbody></table></div>}
      <p className="context-note">Оценка реранкера показывает релевантность чанка, а не достоверность ответа.</p>
    </details>}
  </section>
}
import { formatTokens, formatUsd } from '../usage'
