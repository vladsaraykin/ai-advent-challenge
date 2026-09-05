import { useEffect, useState } from 'react'
import { loadModels, runComparison } from './api'
import ComparisonNote from './components/ComparisonNote'
import ResultCard from './components/ResultCard'
import './model-selectors.css'

export default function App({ submit = runComparison, getModels = loadModels }) {
  const [prompt, setPrompt] = useState('')
  const [maxTokens, setMaxTokens] = useState(1000)
  const [models, setModels] = useState([])
  const [selectedModels, setSelectedModels] = useState(['gpt-4o-mini', 'gpt-5-mini', 'gpt-5.6-sol'])
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { getModels().then(setModels).catch(exception => setError(exception.message)) }, [getModels])

  async function onSubmit(event) {
    event.preventDefault()
    if (!prompt.trim()) { setError('Введите запрос для сравнения'); return }
    if (new Set(selectedModels).size !== 3) { setError('Выберите три разные модели'); return }
    setLoading(true); setError(''); setResult(null)
    try { setResult(await submit({ prompt: prompt.trim(), maxTokens: Number(maxTokens), models: selectedModels })) }
    catch (exception) { setError(exception.message) }
    finally { setLoading(false) }
  }

  const levels = ['WEAK', 'MEDIUM', 'STRONG']
  const labels = ['Слабая модель', 'Средняя модель', 'Сильная модель']
  const cards = result?.results ?? selectedModels.map((modelId, index) => ({
    ...models.find(model => model.model === modelId), level: levels[index]
  })).filter(model => model.model)
  const updateModel = (index, model) => setSelectedModels(current => current.map((value, itemIndex) => itemIndex === index ? model : value))
  return <main>
    <header className="topbar"><a href="/" className="brand">Лаборатория моделей</a><span>Один запрос — три уровня возможностей</span></header>
    <section className="intro"><h1>Сравните возможности моделей</h1><p>Выберите три GPT-модели OpenAI и отправьте им один и тот же запрос.</p></section>
    <form onSubmit={onSubmit} className="workspace">
      <label className="prompt-field"><span>Введите ваш запрос</span><textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows="5" maxLength="12000" disabled={loading} placeholder="Например: объясни принцип градиентного спуска простыми словами и приведи короткий пример" /></label>
      <div className="controls"><label><span>Лимит ответа</span><input type="number" min="64" max="32768" value={maxTokens} onChange={event => setMaxTokens(event.target.value)} disabled={loading} /></label>
        <button disabled={loading || models.length === 0}>{loading ? 'Сравнение выполняется' : 'Сравнить модели'}<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h12m-5-5 5 5-5 5" /></svg></button></div>
      <div className="model-selectors" aria-label="Модели для сравнения">{levels.map((level, index) => <label key={level}><span>{labels[index]}</span><select aria-label={labels[index]} value={selectedModels[index]} onChange={event => updateModel(index, event.target.value)} disabled={loading}>{models.map(model => <option key={model.model} value={model.model}>{model.displayName} · ${model.inputUsdPerMillion}/${model.outputUsdPerMillion}</option>)}</select></label>)}</div>
      <p className="form-hint">Вы оцениваете ответы самостоятельно. Все три модели получают одинаковый запрос и лимит ответа.</p>
      {loading && <div className="progress" role="status" aria-live="polite"><i /><span>Запрос отправлен трём моделям. Одновременно работают не более двух…</span></div>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </form>
    {(models.length > 0 || result) && <section className="results" aria-labelledby="results-title"><div className="section-title"><h2 id="results-title">Три версии ответа</h2>{result && <div className="total"><span>Общее время: {(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 2 })} сек.</span><span>Всего токенов: {result.totalUsage.totalTokens.toLocaleString('ru-RU')}</span><span>Итого: ${Number(result.estimatedTotalCostUsd).toFixed(6)}</span></div>}</div>
      <div className="result-grid">{cards.map(item => <ResultCard key={(item.tier ?? item).model} tier={item.tier ?? item} result={item.status ? item : null} pending={loading} />)}</div></section>}
    <ComparisonNote />
  </main>
}
