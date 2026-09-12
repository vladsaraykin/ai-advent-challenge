import { formatTokens, formatUsd } from '../usage'

export const strategyNames = {
  SUMMARY: 'Summary', SLIDING_WINDOW: 'Sliding Window', FACTS: 'Sticky Facts', BRANCHING: 'Branching'
}
const descriptions = {
  SUMMARY: 'Старые сообщения заменяются накопительным summary. Сжатие требует отдельного вызова модели.',
  SLIDING_WINDOW: 'Полная история сохраняется в чате. Модель получает только последние N сообщений и новый запрос.',
  FACTS: 'Полная история сохраняется в чате. Перед ответом модель обновляет facts по последним N сообщениям, затем получает facts и то же окно.',
  BRANCHING: 'Полная история выбранной ветки. Создайте checkpoint и два независимых продолжения. При достижении лимита появится ошибка.'
}
export default function StrategyPanel({ agent, chat, strategy, onStrategy, disabled, onFork, onOpen, chats = [] }) {
  const selected = chat?.strategy || strategy
  const memory = chat?.memory
  const relatives = chat?.parentChatId ? chats.filter(item => item.parentChatId === chat.parentChatId) : []
  return <section className="strategy-panel" aria-label="Стратегия контекста">
    {!chat ? <label>Стратегия нового чата
      <select value={strategy} disabled={disabled} onChange={e => onStrategy(e.target.value)}>
        {(agent?.strategies || Object.keys(strategyNames)).map(value =>
          <option key={value} value={value}>{strategyNames[value]}</option>)}
      </select>
    </label> : <strong>{strategyNames[selected] || 'Summary'} · стратегия чата</strong>}
    <p>{descriptions[selected]}</p>
    {(selected === 'SLIDING_WINDOW' || selected === 'FACTS') && <p>
      Окно: {selected === 'FACTS' ? agent?.factsMessages || 10 : agent?.slidingMessages || 10} сообщений.
      {' '}Вне контекста: {Math.max(0, (chat?.messages?.length || 0)
        - (selected === 'FACTS' ? agent?.factsMessages || 10 : agent?.slidingMessages || 10))}.
      {' '}История сохранена.
    </p>}
    {selected === 'FACTS' && <details className="facts-memory" open>
      <summary>Факты диалога</summary>
      {Object.keys(memory?.facts || {}).length ? <table><thead><tr><th>Ключ</th><th>Значение</th></tr></thead>
        <tbody>{Object.entries(memory.facts).map(([key, value]) => <tr key={key}><th scope="row">{key}</th><td>{value}</td></tr>)}</tbody>
      </table> : <p>Факты появятся после первого сообщения.</p>}
      {!!memory?.extractionUsage?.calls && <p>Обновление facts: {memory.extractionUsage.calls} выз. ·
        {' '}{formatTokens(memory.extractionUsage.totalTokens)} токенов · {formatUsd(memory.extractionUsage.totalCostUsd)}</p>}
    </details>}
    {selected === 'BRANCHING' && chat && <div className="branch-controls">
      {chat.parentChatId && <><p>Ветка: {chat.title}. История checkpoint унаследована; расход учитывает только новые вызовы этой ветки.</p>
        <button type="button" disabled={disabled} onClick={() => onOpen(chat.parentChatId)}>К checkpoint</button>
        {relatives.filter(item => item.id !== chat.id).map(item =>
          <button type="button" disabled={disabled} key={item.id} onClick={() => onOpen(item.id)}>{item.title}</button>)}</>}
      {chat.readOnly || chat.branches?.length ? <><p>Checkpoint сохранён. {chat.branches?.length ? 'Выберите продолжение:' : 'Все дочерние ветки удалены.'}</p>
        {chat.branches.map(branch => <button type="button" key={branch.id} disabled={disabled}
          onClick={() => onOpen(branch.id)}>{branch.title}</button>)}</>
        : <button type="button" disabled={disabled || !chat.messages.length} onClick={onFork}>Создать развилку: A / B</button>}
    </div>}
    {!chat && <small>Стратегия фиксируется при отправке первого сообщения. Для другой стратегии создайте новый чат.</small>}
  </section>
}
