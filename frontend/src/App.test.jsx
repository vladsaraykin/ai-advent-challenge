import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { newMessageId } from './messageId'

const agents = [
  { id: 'architect', name: 'Архитектор ПО', description: 'Проектирование систем', model: 'gpt-4.1-mini' },
  { id: 'chef', name: 'Повар-помощник', description: 'Рецепты блюд', model: 'gpt-4.1-mini' }
]
const makeChat = (id, agentId = 'architect', title = 'Новый чат', messages = []) => ({
  id, agentId, title, messages, updatedAt: '2026-09-07T12:00:00Z'
})
const answer = (chat, text = '**Ответ агента**') => ({
  ...chat, title: 'Мой вопрос', messages: [
    { id: 'user', role: 'USER', content: 'Мой вопрос' },
    { id: 'answer', role: 'ASSISTANT', content: text,
      metrics: { model: 'gpt-4.1-mini', durationMs: 1250, totalTokens: 90, promptTokens: 30, completionTokens: 60, finishReason: 'stop' } }
  ]
})
const makeApi = () => ({
  agents: vi.fn().mockResolvedValue(agents),
  chats: vi.fn().mockResolvedValue([]),
  chat: vi.fn(),
  create: vi.fn().mockResolvedValue(makeChat('one')),
  send: vi.fn().mockResolvedValue(answer(makeChat('one')))
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
    api.send.mockImplementation(() => new Promise(done => { resolve = done }))
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByRole('status')).toHaveTextContent('готовит ответ')
    expect(screen.getByRole('combobox')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ожидаем ответ…' })).toBeDisabled()
    await waitFor(() => expect(api.send).toHaveBeenCalledTimes(1))
    resolve(answer(makeChat('one')))
    expect(await screen.findByText('Ответ агента')).toBeInTheDocument()
    expect(screen.getByText(/90 токенов/)).toBeInTheDocument()
    expect(screen.getByLabelText('Ваше сообщение')).toHaveValue('')
    expect(api.send.mock.calls[0].slice(0, 2)).toEqual(['architect', 'one'])
    expect(api.send.mock.calls[0][2].content).toBe('Мой вопрос')
  })

  it('retains draft on provider failure and reuses message ID on retry', async () => {
    const api = makeApi()
    api.send.mockRejectedValueOnce(new Error('OpenAI недоступен'))
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('OpenAI недоступен')
    expect(screen.getByLabelText('Ваше сообщение')).toHaveValue('Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    await screen.findByText('Ответ агента')
    expect(api.send.mock.calls[0][2].messageId).toBe(api.send.mock.calls[1][2].messageId)
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
})
