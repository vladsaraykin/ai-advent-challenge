import { formatTokens, formatUsd, summarizeUsage } from '../usage'

const callWord = value => {
  const lastTwo = value % 100
  if (lastTwo >= 11 && lastTwo <= 14) return 'вызовов'
  if (value % 10 === 1) return 'вызов'
  if (value % 10 >= 2 && value % 10 <= 4) return 'вызова'
  return 'вызовов'
}

export default function ChatUsageSummary({ messages, summary }) {
  const usage = summarizeUsage(messages, summary)
  if (!usage.calls) return null
  return <section className="usage-summary" aria-label="Суммарный расход чата">
    <strong>Расход чата</strong>
    <span>{usage.calls} {callWord(usage.calls)} · вход {formatTokens(usage.promptTokens)} ·
      {' '}выход {formatTokens(usage.completionTokens)} · всего {formatTokens(usage.totalTokens)} токенов</span>
    <span>{usage.pricedCalls
      ? `${formatUsd(usage.totalCostUsd)}${usage.pricedCalls < usage.calls ? ` · посчитано ${usage.pricedCalls} из ${usage.calls}` : ''}`
      : 'Стоимость старых вызовов недоступна'}</span>
  </section>
}
