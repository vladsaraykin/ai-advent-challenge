import { useEffect, useState } from 'react'
import { loadExperimentConfig, runExperiment } from './api'
import ResultCard from './components/ResultCard'
import TemperatureGuide from './components/TemperatureGuide'

export default function App({ submit = runExperiment, loadConfig = loadExperimentConfig }) {
  const [prompt, setPrompt] = useState('')
  const [config, setConfig] = useState({ models: [], temperatures: [] })
  const [model, setModel] = useState('')
  const [maxTokens, setMaxTokens] = useState(1000)
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    loadConfig().then(loaded => {
      setConfig(loaded)
      setModel(current => current || loaded.models[0] || '')
    }).catch(exception => setError(exception.message))
  }, [loadConfig])

  async function onSubmit(event) {
    event.preventDefault()
    if (!prompt.trim()) { setError('Введите запрос для эксперимента'); return }
    setLoading(true); setError(''); setResult(null)
    try { setResult(await submit({ prompt: prompt.trim(), model, maxTokens: Number(maxTokens) })) }
    catch (exception) { setError(exception.message) }
    finally { setLoading(false) }
  }

  return <main>
    <header className="topbar"><a href="/" className="brand">AI Advent <span>04</span></a><span>Эксперимент с OpenAI</span></header>
    <section className="intro"><div><h1>Лаборатория температуры</h1><p>Один запрос — три характера ответа. Остальные параметры останутся неизменными.</p></div>
      <div className="temperature-scale" aria-hidden="true"><span>точнее</span><i /><span>свободнее</span></div></section>
    <form onSubmit={onSubmit} className="workspace">
      <label className="prompt-field"><span>Запрос</span><textarea value={prompt} onChange={e => setPrompt(e.target.value)} rows="5" maxLength="12000" disabled={loading} placeholder="Например: придумай три названия для сервиса доставки книг и объясни каждое" /></label>
      <div className="controls"><label><span>Модель</span><select value={model} onChange={e => setModel(e.target.value)} disabled={loading || !model}>{config.models.map(item => <option key={item}>{item}</option>)}</select></label>
        <label><span>Лимит токенов</span><input type="number" min="64" max="4096" value={maxTokens} onChange={e => setMaxTokens(e.target.value)} disabled={loading} /></label>
        <button disabled={loading || !model}>{loading ? 'Эксперимент запущен' : 'Запустить эксперимент'}<b aria-hidden="true">→</b></button></div>
      {loading && <div className="progress" role="status" aria-live="polite"><i /><span>Выполняем три запроса, не более двух одновременно…</span></div>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </form>
    {(loading || result) && <section className="results" aria-labelledby="results-title"><div className="section-title"><h2 id="results-title">Три версии ответа</h2>{result && <span>Общее время: {(result.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} сек.</span>}</div>
      <div className="result-grid">{(result?.results ?? config.temperatures).map(item => {
        const temperature = typeof item === 'number' ? item : item.temperature
        return <ResultCard key={temperature} temperature={temperature} pending={loading} result={typeof item === 'number' ? null : item} />
      })}</div></section>}
    <TemperatureGuide />
  </main>
}
