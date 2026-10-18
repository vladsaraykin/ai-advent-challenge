export default function ToolApproval({ call, busy, onDecide, onCancel }) {
  if (!call) return null
  return <section className="tool-approval" aria-label="Подтверждение MCP-инструмента">
    <div className="eyebrow">ТРЕБУЕТСЯ ВАШЕ РАЗРЕШЕНИЕ</div>
    <h3>{call.tool}</h3><p>Сервер: <strong>{call.server}</strong>. Инструмент ещё не выполнялся.</p>
    <details open><summary>Параметры вызова</summary><pre>{typeof call.input === 'string' ? call.input : JSON.stringify(call.input, null, 2)}</pre></details>
    <p>Проверьте параметры: действие может изменить файлы или данные внешнего сервиса.</p>
    <p>Можно разрешить этот инструмент для всего чата: повторные вызовы с любыми параметрами,
      включая изменение файлов и данных, больше не потребуют подтверждения. Разрешение можно отозвать в настройках MCP чата.</p>
    <div className="approval-actions"><button disabled={busy} onClick={() => onDecide(true)}>Разрешить вызов</button>
      <button disabled={busy || call.toolSignature === null} onClick={() => onDecide(true, true)}>Разрешить инструмент в этом чате</button>
      <button disabled={busy} onClick={() => onDecide(false)}>Отклонить</button>
      <button disabled={busy} onClick={onCancel}>Отменить запрос</button></div>
    {call.toolSignature === null && <p>Этот вызов создан до обновления: пока доступно разовое разрешение. Постоянное можно выдать в следующем запросе.</p>}
  </section>
}
