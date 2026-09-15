export const formatTokens = value => Number(value || 0).toLocaleString('ru-RU')

export const formatUsd = value => new Intl.NumberFormat('en-US', {
  style: 'currency', currency: 'USD', minimumFractionDigits: 6, maximumFractionDigits: 8
}).format(Number(value || 0))

export function summarizeUsage(messages, contextSummary, memory, workingMemory) {
  const result = messages.reduce((summary, message) => {
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
  if (contextSummary) {
    const archived = contextSummary.archivedUsage || {}
    result.calls += Number(archived.calls || 0)
    result.pricedCalls += Number(archived.pricedCalls || 0)
    result.promptTokens += Number(archived.promptTokens || 0)
    result.completionTokens += Number(archived.completionTokens || 0)
    result.totalTokens += Number(archived.totalTokens || 0)
    result.totalCostUsd += Number(archived.totalCostUsd || 0)
    result.calls += Number(contextSummary.calls || 0)
    result.pricedCalls += contextSummary.pricedCalls == null
      ? (contextSummary.totalCostUsd == null ? 0 : Number(contextSummary.calls || 0))
      : Number(contextSummary.pricedCalls)
    result.promptTokens += Number(contextSummary.promptTokens || 0)
    result.completionTokens += Number(contextSummary.completionTokens || 0)
    result.totalTokens += Number(contextSummary.totalTokens || 0)
    result.totalCostUsd += Number(contextSummary.totalCostUsd || 0)
  }
  for (const usage of [memory?.archivedUsage, memory?.extractionUsage, workingMemory?.usage]) {
    if (usage) for (const key of Object.keys(result)) result[key] += Number(usage[key] || 0)
  }
  return result
}
