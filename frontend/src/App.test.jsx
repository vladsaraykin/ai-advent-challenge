import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { newMessageId } from './messageId'

const agents = [
  { id: 'architect', name: 'Архитектор ПО', description: 'Проектирование систем', model: 'gpt-4.1-mini',
    contextCompression: true, recentMessages: 10, summaryBatchSize: 10 },
  { id: 'chef', name: 'Повар-помощник', description: 'Рецепты блюд', model: 'gpt-4.1-mini' }
]
const makeChat = (id, agentId = 'architect', title = 'Новый чат', messages = []) => ({
  id, agentId, title, messages, updatedAt: '2026-09-07T12:00:00Z'
})
const answer = (chat, text = '**Ответ агента**') => ({
  ...chat, title: 'Мой вопрос', messages: [
    { id: 'user', role: 'USER', content: 'Мой вопрос' },
    { id: 'answer', role: 'ASSISTANT', content: text,
      metrics: { model: 'gpt-4.1-mini', durationMs: 1250,
        currentMessageTokens: 4, historyTokens: 0, systemPromptTokens: 10,
        totalTokens: 90, promptTokens: 30, cachedPromptTokens: 0, completionTokens: 60,
        inputCostUsd: 0.000012, outputCostUsd: 0.000096, totalCostUsd: 0.000108,
        finishReason: 'stop' } }
  ]
})
const makeApi = () => ({
  agents: vi.fn().mockResolvedValue(agents),
  chats: vi.fn().mockResolvedValue([]),
  chat: vi.fn(),
  create: vi.fn().mockResolvedValue(makeChat('one')),
  sendStream: vi.fn().mockImplementation(async (agentId, chatId, message, handlers) => {
    handlers.delta({ text: '**Ответ ' })
    handlers.delta({ text: 'агента**' })
    handlers.completed({ chat: answer(makeChat('one')) })
  })
})
beforeEach(() => localStorage.clear())

describe('agent conversations', () => {
  it('shows empty state and creates independent chats', async () => {
    const api = makeApi()
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '+ Новый чат' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledWith('architect'))
    expect(await screen.findByRole('button', { name: /Новый чат.*0 сообщ/ })).toBeInTheDocument()
  })

  it('disables sending and switching while waiting and renders answer with metrics', async () => {
    const api = makeApi()
    let resolve
    let handlers
    api.sendStream.mockImplementation((agentId, chatId, message, callbacks) => new Promise(done => {
      handlers = callbacks
      resolve = () => {
        callbacks.completed({ chat: answer(makeChat('one')) })
        done()
      }
    }))
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByRole('status')).toHaveTextContent('подключается к модели')
    expect(screen.getByRole('combobox')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ожидаем ответ…' })).toBeDisabled()
    await waitFor(() => expect(api.sendStream).toHaveBeenCalledTimes(1))
    handlers.summarizing()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Сжимаем предыдущую историю'))
    handlers.delta({ text: '**Ответ ' })
    handlers.delta({ text: 'поступает**' })
    expect(await screen.findByText('Ответ поступает')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Ответ поступает')
    resolve(answer(makeChat('one')))
    expect(await screen.findByText('Ответ агента')).toBeInTheDocument()
    expect(within(screen.getByRole('log')).getByText(/90 токенов/)).toBeInTheDocument()
    expect(screen.getByText(/Текущий запрос: 4/)).toBeInTheDocument()
    expect(screen.getByText(/Стоимость: \$0\.000108/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('всего 90 токенов')
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('$0.000108')
    expect(screen.getByLabelText('Ваше сообщение')).toHaveValue('')
    expect(api.sendStream.mock.calls[0].slice(0, 2)).toEqual(['architect', 'one'])
    expect(api.sendStream.mock.calls[0][2].content).toBe('Мой вопрос')
  })

  it('retains draft on provider failure and reuses message ID on retry', async () => {
    const api = makeApi()
    api.sendStream.mockRejectedValueOnce(new Error('OpenAI недоступен'))
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('OpenAI недоступен')
    expect(screen.getByLabelText('Ваше сообщение')).toHaveValue('Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    await screen.findByText('Ответ агента')
    expect(api.sendStream.mock.calls[0][2].messageId).toBe(api.sendStream.mock.calls[1][2].messageId)
    expect(api.create).toHaveBeenCalledTimes(1)
  })

  it('switches chats and agents without showing another history', async () => {
    const api = makeApi()
    const first = makeChat('first', 'architect', 'Первый проект', [{ id: 'm1', role: 'USER', content: 'Мой магазин' }])
    const second = makeChat('second', 'architect', 'Второй проект', [{ id: 'm2', role: 'USER', content: 'Мой блог' }])
    const chef = makeChat('recipe', 'chef', 'Ужин', [{ id: 'm3', role: 'USER', content: 'Рецепт супа' }])
    api.chats.mockImplementation(id => Promise.resolve(id === 'architect' ? [first, second] : [chef]))
    api.chat.mockImplementation((id, chatId) => Promise.resolve([first, second, chef].find(chat => chat.id === chatId)))
    render(<App api={api} />)
    await screen.findByText('Мой магазин')
    await userEvent.click(screen.getByRole('button', { name: /Второй проект/ }))
    await screen.findByText('Мой блог')
    expect(screen.queryByText('Мой магазин')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByRole('combobox'), 'chef')
    await screen.findByText('Рецепт супа')
    expect(screen.queryByText('Мой блог')).not.toBeInTheDocument()
    expect(within(screen.getByRole('navigation')).queryByText('Первый проект')).not.toBeInTheDocument()
  })

  it('restores selected chat after remount and renders Markdown safely', async () => {
    const api = makeApi()
    const chat = answer(makeChat('saved'), '<script>alert(1)</script>\n\n**Безопасный Markdown**')
    api.chats.mockResolvedValue([chat])
    api.chat.mockResolvedValue(chat)
    localStorage.setItem('agent-lab.agent', 'architect')
    localStorage.setItem('agent-lab.chat.architect', 'saved')
    const { container } = render(<App api={api} />)
    await screen.findByText('Безопасный Markdown')
    expect(container.querySelector('script')).toBeNull()
    expect(api.chat).toHaveBeenCalledWith('architect', 'saved')
  })

  it('generates valid message IDs on an HTTP origin without randomUUID', () => {
    expect(newMessageId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('keeps legacy token metrics and marks their historical cost as unavailable', async () => {
    const api = makeApi()
    const chat = makeChat('legacy', 'architect', 'Старый чат', [{
      id: 'old', role: 'ASSISTANT', content: 'Старый ответ',
      metrics: { model: 'gpt-4.1-mini', durationMs: 100, promptTokens: 10,
        completionTokens: 5, totalTokens: 15, finishReason: 'stop' }
    }])
    api.chats.mockResolvedValue([chat])
    api.chat.mockResolvedValue(chat)
    render(<App api={api} />)
    await screen.findByText('Старый ответ')
    expect(screen.getByText(/Стоимость: нет данных/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' }))
      .toHaveTextContent('Стоимость старых вызовов недоступна')
  })

  it('shows separately stored summary and includes its usage safely', async () => {
    const api = makeApi()
    const chat = makeChat('compressed', 'architect', 'Длинный чат', [])
    chat.summary = { content: '<script>alert(1)</script>\n\n**Выбран PostgreSQL**', summarizedMessages: 20,
      calls: 2, promptTokens: 300, completionTokens: 80, totalTokens: 380, totalCostUsd: 0.0004,
      archivedUsage: { calls: 10, pricedCalls: 10, promptTokens: 1000, completionTokens: 200,
        totalTokens: 1200, totalCostUsd: 0.0012 } }
    api.chats.mockResolvedValue([chat])
    api.chat.mockResolvedValue(chat)
    const { container } = render(<App api={api} />)

    expect(await screen.findByText(/Сжато 20 сообщений/)).toBeInTheDocument()
    await userEvent.click(screen.getByText(/Сжато 20 сообщений/))
    expect(await screen.findByText('Выбран PostgreSQL')).toBeInTheDocument()
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByText(/архивировано 1 200 токенов/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('1 580 токенов')
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('$0.001600')
  })
})
