import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const levelLabels = { WEAK: 'Слабая модель', MEDIUM: 'Средняя модель', STRONG: 'Сильная модель' }
const levelClasses = { WEAK: 'weak', MEDIUM: 'medium', STRONG: 'strong' }

export default function ResultCard({ result, pending = false, tier }) {
  const modelTier = result?.tier ?? tier
  const level = modelTier.level
  return <article className={`result result-${levelClasses[level]}`} aria-busy={pending}>
    <header><div><span className="model-dot" /><div><h3>{modelTier.displayName}</h3><p>{levelLabels[level]}</p></div></div>
      {result && <span className={`status ${result.status.toLowerCase()}`}>{result.status === 'SUCCESS' ? 'Готово' : 'Ошибка'}</span>}</header>
    {pending && <div className="skeleton" role="status"><span>Генерируем ответ…</span><i /><i /><i /></div>}
    {result?.status === 'ERROR' && <p className="result-error" role="alert">{result.error}</p>}
    {result?.status === 'SUCCESS' && <>
      <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{result.answer}</ReactMarkdown></div>
      <footer><div><b>Время</b><span>{(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 2 })} сек.</span></div>
        <div><b>Токены</b><span>Вход: {result.usage.promptTokens.toLocaleString('ru-RU')}<br />Выход: {result.usage.completionTokens.toLocaleString('ru-RU')}<br />Всего: {result.usage.totalTokens.toLocaleString('ru-RU')}</span></div>
        <div><b>Стоимость</b><span>${Number(result.estimatedCostUsd).toFixed(6)}</span></div></footer>
    </>}
    {result?.status === 'ERROR' && <footer><div><b>Время</b><span>{(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 2 })} сек.</span></div><div><b>Токены</b><span>{result.usage.totalTokens > 0 ? <>Вход: {result.usage.promptTokens.toLocaleString('ru-RU')}<br />Выход: {result.usage.completionTokens.toLocaleString('ru-RU')}<br />Всего: {result.usage.totalTokens.toLocaleString('ru-RU')}</> : '—'}</span></div><div><b>Стоимость</b><span>{result.usage.totalTokens > 0 ? `$${Number(result.estimatedCostUsd).toFixed(6)}` : '—'}</span></div></footer>}
  </article>
}
