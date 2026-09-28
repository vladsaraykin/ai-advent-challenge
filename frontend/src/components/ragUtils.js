export const strategyLabels = { FIXED_SIZE: 'Fixed-size', STRUCTURAL: 'По структуре' }
export const activeRun = run => !['COMPLETED', 'FAILED'].includes(run.status)
export const number = value => value == null ? '—' : value.toLocaleString('ru-RU')
