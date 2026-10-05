import { useEffect, useState } from 'react'

export default function RetrievalSettings({ settings, disabled, onSave }) {
  const [values, setValues] = useState(settings.retrieval)
  const [error, setError] = useState('')
  useEffect(() => { setValues(settings.retrieval) }, [settings])
  return <details className="inspector-settings"><summary>Параметры RAG-поиска</summary>
    <form className="retrieval-settings" onSubmit={event => {
      event.preventDefault(); setError('')
      if (values.finalK > values.candidateK) { setError('Top-K после фильтрации не может превышать Top-K до.'); return }
      onSave({ retrieval: values })
    }}>
      <label>Top-K до фильтрации<input type="number" min="1" max="50" required disabled={disabled} value={values.candidateK}
        onChange={event => setValues({ ...values, candidateK: Number(event.target.value) })} /></label>
      <label>Top-K после фильтрации<input type="number" min="1" max="20" required disabled={disabled} value={values.finalK}
        onChange={event => setValues({ ...values, finalK: Number(event.target.value) })} /></label>
      <label>Порог реранкера<input type="number" min="0" max="1" step="0.01" required disabled={disabled} value={values.threshold}
        onChange={event => setValues({ ...values, threshold: Number(event.target.value) })} /></label>
      {error && <p role="alert">{error}</p>}
      <button disabled={disabled}>Сохранить параметры поиска</button>
    </form>
  </details>
}
