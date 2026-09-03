import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const labels = new Map([[0, '0'], [0.7, '0,7'], [1.2, '1,2']])

export default function ResultCard({ result, pending = false, temperature }) {
  const value = result?.temperature ?? temperature
  return <article className={`result result-${String(value).replace('.', '-')}`} aria-busy={pending}>
    <header><div><span className="temperature-dot" /><h3>Температура {labels.get(value)}</h3></div>
      {result && <span className={`status ${result.status.toLowerCase()}`}>{result.status === 'SUCCESS' ? 'Готово' : 'Ошибка'}</span>}</header>
    {pending && <div className="skeleton" role="status"><span>Генерируем ответ…</span><i /><i /><i /></div>}
    {result?.status === 'ERROR' && <p className="result-error" role="alert">{result.error}</p>}
    {result?.status === 'SUCCESS' && <>
      <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{result.answer}</ReactMarkdown></div>
      <footer><span>{result.usage.totalTokens.toLocaleString('ru-RU')} токенов</span><span>{(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} сек.</span></footer>
    </>}
    {result?.status === 'ERROR' && <footer><span>Ответ не получен</span><span>{(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} сек.</span></footer>}
  </article>
}
