import { useEffect, useState } from 'react'

export default function UserProfile({ profile, api, disabled, onProfile, onLogout }) {
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState(profile)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => setForm(profile), [profile])
  async function save(event) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const constraints = form.constraintsText.split('\n').map(value => value.trim()).filter(Boolean)
      const updated = await api.updateProfile({ version: profile.version, displayName: form.displayName,
        responseStyle: form.responseStyle, responseFormat: form.responseFormat, constraints })
      onProfile(updated); setOpen(false)
    } catch (exception) { setError(exception.message) }
    finally { setBusy(false) }
  }
  function show() { setForm({ ...profile, constraintsText: (profile.constraints || []).join('\n') }); setError(''); setOpen(true) }
  return <div className="profile-control">
    <button type="button" className="profile-chip" disabled={disabled} onClick={show}
      aria-label={`Открыть профиль ${profile.displayName || profile.username}`}>
      <span>{(profile.displayName || profile.username).slice(0, 1).toUpperCase()}</span>
      <strong>{profile.displayName || profile.username}</strong>
    </button>
    {open && <div className="profile-overlay"><section className="profile-dialog" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <span className="eyebrow">Активный профиль · {profile.username}</span><h2 id="profile-title">Персонализация</h2>
      <p>Эти настройки автоматически передаются агенту с каждым вашим запросом.</p>
      <form onSubmit={save}>
        <label>Имя<input required maxLength="80" value={form.displayName || ''}
          onChange={event => setForm({ ...form, displayName: event.target.value })} /></label>
        <label>Стиль ответа<textarea required maxLength="500" rows="3" value={form.responseStyle || ''}
          onChange={event => setForm({ ...form, responseStyle: event.target.value })} /></label>
        <label>Формат ответа<textarea required maxLength="500" rows="3" value={form.responseFormat || ''}
          onChange={event => setForm({ ...form, responseFormat: event.target.value })} /></label>
        <label>Ограничения — по одному в строке<textarea maxLength="6000" rows="5" value={form.constraintsText || ''}
          onChange={event => setForm({ ...form, constraintsText: event.target.value })} /></label>
        {error && <div className="error-banner" role="alert">{error}</div>}
        <div className="profile-actions"><button type="button" disabled={busy} onClick={() => setOpen(false)}>Отмена</button>
          <button className="primary-action" disabled={busy}>{busy ? 'Сохраняем…' : 'Сохранить профиль'}</button></div>
      </form>
      <button type="button" className="logout-button" disabled={busy} onClick={onLogout}>Выйти и сменить профиль</button>
    </section></div>}
  </div>
}
