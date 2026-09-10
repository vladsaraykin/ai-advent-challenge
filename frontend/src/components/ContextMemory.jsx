import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { formatTokens, formatUsd } from '../usage'

export default function ContextMemory({ agent, summary }) {
  if (!agent?.contextCompression) return null
  return <details className="context-memory">
    <summary>
      <strong>Память диалога</strong>
      {summary
        ? <span>Сжато {formatTokens(summary.summarizedMessages)} сообщений · {summary.calls} summary-выз.</span>
        : <span>Ожидает накопления истории · последние {agent.recentMessages} сообщений без изменений</span>}
    </summary>
    {summary ? <div className="context-memory-body">
      <div className="memory-metrics">Сжатие: {summary.content.length.toLocaleString('ru-RU')} символов ·
        {' '}сжатие {formatTokens(summary.totalTokens)} токенов · {formatUsd(summary.totalCostUsd)}
        {summary.archivedUsage && <> · архивировано {formatTokens(summary.archivedUsage.totalTokens)} токенов
          {' '}ответов</>}</div>
      <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{summary.content}</ReactMarkdown></div>
    </div> : <p>Сжатие выполнится, когда кроме последних {agent.recentMessages} сообщений накопится ещё
      {' '}{agent.summaryBatchSize}.</p>}
  </details>
}
