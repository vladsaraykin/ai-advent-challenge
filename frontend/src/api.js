export async function runComparison(payload) {
  const response = await fetch('/api/model-comparisons', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || 'Не удалось запустить сравнение')
  return body
}

export async function loadModels() {
  const response = await fetch('/api/model-comparisons/models')
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error('Не удалось загрузить список моделей')
  return body
}
