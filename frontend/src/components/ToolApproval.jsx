export default function ToolApproval({ call, busy, onDecide, onCancel }) {
  if (!call) return null
  return <section className="tool-approval" aria-label="Подтверждение MCP-инструмента">
    <div className="eyebrow">ТРЕБУЕТСЯ ВАШЕ РАЗРЕШЕНИЕ</div>
    <h3>{call.tool}</h3><p>Сервер: <strong>{call.server}</strong>. Инструмент ещё не выполнялся.</p>
    <details open><summary>Параметры вызова</summary><pre>{typeof call.input === 'string' ? call.input : JSON.stringify(call.input, null, 2)}</pre></details>
    <p>Проверьте параметры: действие может изменить файлы или данные внешнего сервиса.</p>
    <div className="approval-actions"><button disabled={busy} onClick={() => onDecide(true)}>Разрешить вызов</button>
      <button disabled={busy} onClick={() => onDecide(false)}>Отклонить</button>
      <button disabled={busy} onClick={onCancel}>Отменить запрос</button></div>
  </section>
}
