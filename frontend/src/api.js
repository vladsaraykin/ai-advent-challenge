export async function runExperiment(payload) {
  const response = await fetch('/api/temperature-experiments', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || 'Не удалось запустить эксперимент')
  return body
}

export async function loadExperimentConfig() {
  const response = await fetch('/api/temperature-experiments/models')
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error('Не удалось загрузить настройки эксперимента')
  return body
}
