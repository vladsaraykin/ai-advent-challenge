export const formatTokens = value => Number(value || 0).toLocaleString('ru-RU')

export const formatUsd = value => new Intl.NumberFormat('en-US', {
  style: 'currency', currency: 'USD', minimumFractionDigits: 6, maximumFractionDigits: 8
}).format(Number(value || 0))

export function summarizeUsage(messages) {
  return messages.reduce((summary, message) => {
    if (!message.metrics) return summary
    summary.calls += 1
    summary.promptTokens += Number(message.metrics.promptTokens || 0)
    summary.completionTokens += Number(message.metrics.completionTokens || 0)
    summary.totalTokens += Number(message.metrics.totalTokens || 0)
    if (message.metrics.totalCostUsd != null) {
      summary.pricedCalls += 1
      summary.totalCostUsd += Number(message.metrics.totalCostUsd)
    }
    return summary
  }, { calls: 0, pricedCalls: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0, totalCostUsd: 0 })
}
