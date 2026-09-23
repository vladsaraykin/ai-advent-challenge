import { useState } from 'react'

export default function AuthScreen({ api, onAuthenticated }) {
  const [register, setRegister] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      if (register) await api.register({ username, password, displayName })
      api.setCredentials(username.trim().toLowerCase(), password)
      onAuthenticated(await api.me())
    } catch (exception) {
      api.clearCredentials(); setError(exception.message)
    } finally { setBusy(false) }
  }
  return <main className="auth-shell">
    <section className="auth-card" aria-labelledby="auth-title">
      <span className="eyebrow">AI Advent · Персональные агенты</span>
      <h1 id="auth-title">{register ? 'Создать профиль' : 'Войти в свой профиль'}</h1>
      <p>Чаты, память и персональные настройки хранятся отдельно для каждого пользователя.</p>
      <form onSubmit={submit}>
        <label>Логин<input required minLength="3" maxLength="40" autoComplete="username"
          value={username} onChange={event => setUsername(event.target.value)} /></label>
        {register && <label>Как к вам обращаться<input maxLength="80" autoComplete="name"
          value={displayName} onChange={event => setDisplayName(event.target.value)} /></label>}
        <label>Пароль<input required minLength="8" maxLength="200" type="password"
          autoComplete={register ? 'new-password' : 'current-password'} value={password}
          onChange={event => setPassword(event.target.value)} /></label>
        {error && <div className="error-banner" role="alert">{error}</div>}
        <button className="primary-action" disabled={busy}>{busy ? 'Проверяем…' : register ? 'Создать и войти' : 'Войти'}</button>
      </form>
      <button className="link-button" type="button" disabled={busy} onClick={() => { setRegister(value => !value); setError('') }}>
        {register ? 'У меня уже есть профиль' : 'Создать новый профиль'}
      </button>
      <small>Используется HTTP Basic. Для удалённого стенда обязательно включите HTTPS.</small>
    </section>
  </main>
}
