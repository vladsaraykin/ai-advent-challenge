import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { formatTokens, formatUsd } from '../usage'

export default function MessageList({ messages, agent, pending, pendingMessage = '', streamedAnswer = '', streamPhase = '' }) {
  const bottom = useRef(null)
  useEffect(() => { bottom.current?.scrollIntoView?.({ block: 'nearest' }) }, [messages, pending, streamedAnswer])
  return <div className="message-list" role="log" aria-label="Сообщения чата" aria-live="polite">
    {!messages.length && !pending && <div className="welcome">
      <div className="welcome-mark" aria-hidden="true">{agent?.name?.slice(0, 1) || 'А'}</div>
      <h2>С чего начнём?</h2><p>{agent?.description}</p>
      <p className="welcome-hint">Расскажите о задаче. Можно продолжать разговор и возвращаться к нему позже.</p>
    </div>}
    {messages.map(message => <article key={message.id} className={`message message-${message.role.toLowerCase()}`}>
      <div className="message-author">{message.role === 'USER' ? 'Вы' : agent?.name}</div>
      <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{message.content}</ReactMarkdown></div>
      {message.metrics && <footer>
        {message.metrics.model} · {(message.metrics.durationMs / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 2 })} сек. ·
        {' '}{formatTokens(message.metrics.totalTokens)} токенов
        {message.metrics.currentMessageTokens != null && <span>
          Текущий запрос: {formatTokens(message.metrics.currentMessageTokens)} ·
          {' '}История: {formatTokens(message.metrics.historyTokens)} ·
          {' '}Системный промпт: {formatTokens(message.metrics.systemPromptTokens)}
        </span>}
        <span>Вход API: {formatTokens(message.metrics.promptTokens)}
          {message.metrics.cachedPromptTokens > 0 && <> · из кэша: {formatTokens(message.metrics.cachedPromptTokens)}</>}
          {' '}· Ответ: {formatTokens(message.metrics.completionTokens)}</span>
        <span className="message-cost">Стоимость: {message.metrics.totalCostUsd != null
          ? `${formatUsd(message.metrics.totalCostUsd)} (вход ${formatUsd(message.metrics.inputCostUsd)} · ответ ${formatUsd(message.metrics.outputCostUsd)})`
          : 'нет данных — сообщение создано до включения расчёта'}</span>
        {message.metrics.finishReason === 'length' && <span className="truncation-note">Ответ достиг лимита токенов. Попросите агента продолжить.</span>}
      </footer>}
    </article>)}
    {pending && pendingMessage && <article className="message message-user"><div className="message-author">Вы</div>
      <p className="pending-text">{pendingMessage}</p></article>}
    {pending && <>
      {streamedAnswer
        ? <article className="message message-assistant message-streaming">
          <div className="message-author">{agent?.name}</div>
          <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{streamedAnswer}</ReactMarkdown></div>
          <div className="stream-status" role="status"><i />
            {streamPhase === 'syncing_questions' ? 'Сохраняем вопросы в память задачи…'
              : streamPhase === 'summarizing' ? 'Сжимаем историю…'
                : streamPhase === 'validating_lifecycle' ? 'Проверяем ответ по этапу задачи…'
                  : streamPhase === 'validating_answer' ? 'Проверяем ответ по инвариантам…' : 'Ответ поступает…'}</div>
        </article>
        : <div className="waiting" role="status"><i />{streamPhase === 'summarizing'
          ? 'Сжимаем предыдущую историю…' : streamPhase === 'syncing_questions' ? 'Сохраняем вопросы в память задачи…'
            : streamPhase === 'updating_memory' ? 'Обновляем память задачи…' : streamPhase === 'updating_facts'
            ? 'Обновляем факты диалога…' : streamPhase === 'checking_invariants'
              ? 'Проверяем запрос по инвариантам…' : streamPhase === 'generating'
                ? 'Формируем ответ в рамках инвариантов…' : streamPhase === 'validating_answer'
                  ? 'Проверяем ответ по инвариантам…' : streamPhase === 'checking_lifecycle'
                    ? 'Проверяем допустимость действия на текущем этапе…' : streamPhase === 'validating_lifecycle'
                      ? 'Проверяем ответ по этапу задачи…' : streamPhase === 'using_mcp'
                        ? 'Модель выбирает и выполняет MCP-инструменты…' : `${agent?.name} подключается к модели…`}</div>}</>}
    <div ref={bottom} />
  </div>
}
