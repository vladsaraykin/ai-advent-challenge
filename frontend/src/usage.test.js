import { describe, expect, it } from 'vitest'
import { formatUsd, summarizeUsage } from './usage'

describe('chat usage totals', () => {
  it('sums billed tokens and only known historical costs', () => {
    const usage = summarizeUsage([
      { metrics: { promptTokens: 100, completionTokens: 20, totalTokens: 120, totalCostUsd: 0.000072 } },
      { metrics: { promptTokens: 220, completionTokens: 30, totalTokens: 250, totalCostUsd: 0.000136 } },
      { metrics: { promptTokens: 10, completionTokens: 5, totalTokens: 15 } }
    ])
    expect(usage).toMatchObject({ calls: 3, pricedCalls: 2, promptTokens: 330,
      completionTokens: 55, totalTokens: 385 })
    expect(usage.totalCostUsd).toBeCloseTo(0.000208, 10)
    expect(formatUsd(usage.totalCostUsd)).toBe('$0.000208')
  })

  it('keeps archived answer usage and includes summary calls in the chat total', () => {
    const usage = summarizeUsage([
      { metrics: { promptTokens: 100, completionTokens: 20, totalTokens: 120, totalCostUsd: 0.0001 } }
    ], { calls: 2, promptTokens: 300, completionTokens: 80, totalTokens: 380, totalCostUsd: 0.0004,
      archivedUsage: { calls: 5, pricedCalls: 4, promptTokens: 700, completionTokens: 200,
        totalTokens: 900, totalCostUsd: 0.0008 } })
    expect(usage).toMatchObject({ calls: 8, pricedCalls: 7, promptTokens: 1100,
      completionTokens: 300, totalTokens: 1400 })
    expect(usage.totalCostUsd).toBeCloseTo(0.0013, 10)
  })
})
