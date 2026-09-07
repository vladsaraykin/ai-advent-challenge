import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export default function MessageList({ messages, agent, pending, draft }) {
  const bottom = useRef(null)
  useEffect(() => { bottom.current?.scrollIntoView?.({ block: 'nearest' }) }, [messages, pending])
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
        {' '}{message.metrics.totalTokens.toLocaleString('ru-RU')} токенов
        <span>Вход: {message.metrics.promptTokens.toLocaleString('ru-RU')} · Выход: {message.metrics.completionTokens.toLocaleString('ru-RU')}</span>
        {message.metrics.finishReason === 'length' && <span className="truncation-note">Ответ достиг лимита токенов. Попросите агента продолжить.</span>}
      </footer>}
    </article>)}
    {pending && <><article className="message message-user"><div className="message-author">Вы</div><p className="pending-text">{draft}</p></article>
      <div className="waiting" role="status"><i />{agent?.name} готовит ответ…</div></>}
    <div ref={bottom} />
  </div>
}
