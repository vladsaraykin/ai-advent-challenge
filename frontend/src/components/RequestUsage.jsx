import { formatTokens, formatUsd } from '../usage'

export default function RequestUsage({ requests }) {
  if (!requests?.length) return null
  const totals = requests.flatMap(request => request.usage || [])
  const tokens = totals.reduce((sum, metrics) => sum + (metrics.totalTokens || 0), 0)
  const cost = totals.reduce((sum, metrics) => sum + Number(metrics.totalCostUsd || 0), 0)
  const incomplete = requests.some(request => !request.usageComplete) || totals.some(metrics => metrics.totalCostUsd == null)
  return <details className="request-usage"><summary>Расходы всех запросов: {formatTokens(tokens)} токенов · {formatUsd(cost)}{incomplete && ' · неполные данные'}</summary>
    <p>Включены известные расходы на ответы, память, проверки, поиск и неуспешные попытки. Наследованные ответы веток не оплачиваются повторно.</p>
    {incomplete && <p>Часть расходов провайдер не вернул, например при остановке для подтверждения MCP. Итог — сумма известных расходов, а не полный счёт.</p>}
    {requests.map(request => <div key={request.requestId}><small>{request.requestId} · {request.status}</small>
      <p>{request.usage.length} измеренных вызовов · {formatTokens(request.usage.reduce((sum, m) => sum + m.totalTokens, 0))} токенов ·
        {' '}{formatUsd(request.usage.reduce((sum, m) => sum + Number(m.totalCostUsd || 0), 0))}{!request.usageComplete && ' (неполно)'}</p></div>)}
  </details>
}
