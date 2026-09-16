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
  it('authenticates, edits personalization and switches profile by logging out', async () => {
    const api = makeApi()
    const profile = { username: 'alice', displayName: 'Алиса', responseStyle: 'Кратко',
      responseFormat: 'Markdown', constraints: [], version: 0 }
    api.me = vi.fn().mockRejectedValueOnce(new Error('Требуется вход')).mockResolvedValue(profile)
    api.setCredentials = vi.fn()
    api.clearCredentials = vi.fn()
    api.register = vi.fn()
    api.updateProfile = vi.fn().mockResolvedValue({ ...profile, responseStyle: 'Подробно', version: 1 })
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'Войти в свой профиль' })
    await userEvent.type(screen.getByLabelText('Логин'), 'alice')
    await userEvent.type(screen.getByLabelText('Пароль'), 'secret-123')
    await userEvent.click(screen.getByRole('button', { name: 'Войти', exact: true }))
    expect(await screen.findByRole('button', { name: 'Открыть профиль Алиса' })).toBeInTheDocument()
    expect(api.setCredentials).toHaveBeenCalledWith('alice', 'secret-123')
    await userEvent.click(screen.getByRole('button', { name: 'Открыть профиль Алиса' }))
    const style = screen.getByLabelText('Стиль ответа')
    await userEvent.clear(style); await userEvent.type(style, 'Подробно')
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }))
    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith(expect.objectContaining({
      version: 0, responseStyle: 'Подробно'
    })))
    await userEvent.click(screen.getByRole('button', { name: 'Открыть профиль Алиса' }))
    await userEvent.click(screen.getByRole('button', { name: 'Выйти и сменить профиль' }))
    expect(await screen.findByRole('heading', { name: 'Войти в свой профиль' })).toBeInTheDocument()
    expect(api.clearCredentials).toHaveBeenCalled()
  })

  it('announces task memory preparation and includes its cost after SSE completion', async () => {
    const api = makeApi()
    api.agents.mockResolvedValue([{ ...agents[0], memoryLayers: true }])
    api.memory = vi.fn().mockResolvedValue({ version: 0, entries: [] })
    let callbacks, finish
    api.sendStream.mockImplementation((agentId, chatId, message, handlers) => new Promise(resolve => {
      callbacks = handlers; finish = resolve; handlers.updating_memory()
    }))
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByText('Обновляем память задачи…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Новый чат' })).toBeDisabled()
    const completed = { ...answer(makeChat('one')), workingMemory: {
      version: 1, stage: 'REQUIREMENTS', goal: 'Сервис уведомлений', proposals: [],
      usage: { calls: 1, pricedCalls: 1, promptTokens: 10, completionTokens: 20, totalTokens: 30, totalCostUsd: .000036 }
    } }
    callbacks.delta({ text: 'Какой email-провайдер?' })
    callbacks.syncing_questions()
    expect(await screen.findByText('Сохраняем вопросы в память задачи…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Новый чат' })).toBeDisabled()
    completed.workingMemory.openQuestions = ['Какой email-провайдер?']
    callbacks.completed({ chat: completed }); finish()
    expect(await screen.findByText('Цель: Сервис уведомлений')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('всего 120 токенов')
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('$0.000144')
  })
  it('shows empty state and creates independent chats', async () => {
    const api = makeApi()
    render(<App api={api} />)
    await screen.findByRole('heading', { name: 'С чего начнём?' })
    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '+ Новый чат' }))
    expect(api.create).not.toHaveBeenCalled()
    await userEvent.selectOptions(screen.getByLabelText('Стратегия нового чата'), 'SLIDING_WINDOW')
    api.create.mockResolvedValue({ ...makeChat('one'), strategy: 'SLIDING_WINDOW' })
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Мой вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledWith('architect', 'SLIDING_WINDOW'))
    await screen.findByText('Ответ агента')
    expect(screen.queryByLabelText('Стратегия нового чата')).not.toBeInTheDocument()
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
    await userEvent.click(screen.getByRole('button', { name: /^Второй проект/ }))
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

  it('shows facts extraction progress and separately accounted key-value memory', async () => {
    const api = makeApi()
    const chat = { ...makeChat('one'), strategy: 'FACTS' }
    api.create.mockResolvedValue(chat)
    let finish
    api.sendStream.mockImplementation((agentId, chatId, message, handlers) => new Promise(resolve => {
      handlers.updating_facts()
      finish = () => {
        handlers.completed({ chat: { ...answer(chat), memory: {
          facts: { goal: '<script>test</script>' },
          extractionUsage: { calls: 1, pricedCalls: 1, promptTokens: 10, completionTokens: 5, totalTokens: 15, totalCostUsd: 0.00002 }
        } } })
        resolve()
      }
    }))
    const { container } = render(<App api={api} />)
    await screen.findByLabelText('Стратегия нового чата')
    await userEvent.selectOptions(screen.getByLabelText('Стратегия нового чата'), 'FACTS')
    await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Цель: магазин')
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Обновляем факты')
    finish()
    await screen.findByText('<script>test</script>')
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('105 токенов')
    expect(screen.getByText(/Обновление facts: 1/)).toBeInTheDocument()
  })

  it('creates a checkpoint and switches independent branches', async () => {
    const api = makeApi()
    const root = { ...answer(makeChat('root')), strategy: 'BRANCHING', branches: [] }
    const a = { ...makeChat('a', 'architect', 'Вариант A', [{ id: 'a1', role: 'ASSISTANT', content: 'Только A' }]),
      strategy: 'BRANCHING', parentChatId: 'root', branches: [] }
    const b = { ...makeChat('b', 'architect', 'Вариант B', [{ id: 'b1', role: 'ASSISTANT', content: 'Только B' }]),
      strategy: 'BRANCHING', parentChatId: 'root', branches: [] }
    api.chats.mockResolvedValueOnce([root]).mockResolvedValue([root, a, b])
    api.chat.mockImplementation((agent, id) => Promise.resolve({ root, a, b }[id]))
    api.fork = vi.fn().mockResolvedValue({ ...root, branches: [a, b], checkpointId: 'point' })
    render(<App api={api} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Создать развилку: A / B' }))
    await waitFor(() => expect(api.fork).toHaveBeenCalledWith('architect', 'root', {
      firstBranchName: 'Вариант A', secondBranchName: 'Вариант B'
    }))
    expect(screen.getByLabelText('Ваше сообщение')).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Вариант A', exact: true }))
    await screen.findByText('Только A')
    await userEvent.click(screen.getByRole('button', { name: 'Вариант B', exact: true }))
    await screen.findByText('Только B')
    expect(screen.queryByText('Только A')).not.toBeInTheDocument()
  })

  it('renders the full sliding-window transcript and counts each response once', async () => {
    const api = makeApi()
    const messages = Array.from({ length: 14 }, (_, i) => ({
      id: 'window-' + i, role: i % 2 ? 'ASSISTANT' : 'USER', content: 'Сообщение окна ' + i,
      metrics: i % 2 ? answer(makeChat('metrics')).messages[1].metrics : null
    }))
    const chat = { ...makeChat('window', 'architect', 'Окно', messages), strategy: 'SLIDING_WINDOW' }
    api.chats.mockResolvedValue([chat])
    api.chat.mockResolvedValue(chat)
    render(<App api={api} />)
    await screen.findByText('Сообщение окна 0')
    expect(screen.getByText('Сообщение окна 13')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Стратегия контекста' })).toHaveTextContent('Вне контекста: 4')
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' })).toHaveTextContent('630 токенов')
  })

  it('renders the full facts transcript while showing the provider window and memory usage', async () => {
    const api = makeApi()
    const messages = Array.from({ length: 14 }, (_, i) => ({
      id: 'facts-window-' + i, role: i % 2 ? 'ASSISTANT' : 'USER', content: 'Сообщение facts ' + i,
      metrics: i % 2 ? answer(makeChat('metrics')).messages[1].metrics : null
    }))
    const chat = { ...makeChat('facts-window', 'architect', 'Facts', messages), strategy: 'FACTS',
      memory: { facts: { goal: 'Сохранить всю историю' }, discardedMessages: 0,
        extractionUsage: { calls: 14, pricedCalls: 14, promptTokens: 140,
          completionTokens: 70, totalTokens: 210, totalCostUsd: 0.00028 } } }
    api.chats.mockResolvedValue([chat])
    api.chat.mockResolvedValue(chat)
    render(<App api={api} />)

    await screen.findByText('Сообщение facts 0')
    expect(screen.getByText('Сообщение facts 13')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Стратегия контекста' }))
      .toHaveTextContent('Вне контекста: 4')
    expect(screen.getByText('Сохранить всю историю')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Суммарный расход чата' }))
      .toHaveTextContent('840 токенов')
  })

  it('confirms deletion, supports cancellation and clears the deleted active chat', async () => {
    const api = makeApi()
    const saved = answer(makeChat('saved', 'architect', 'Удаляемый'))
    api.chats.mockResolvedValue([saved])
    api.chat.mockResolvedValue(saved)
    api.delete = vi.fn().mockResolvedValue({})
    render(<App api={api} />)
    await screen.findByText('Ответ агента')
    await userEvent.click(screen.getByRole('button', { name: 'Удалить чат «Мой вопрос»' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('дочерние ветки')
    await userEvent.click(screen.getByRole('button', { name: 'Отмена', exact: true }))
    expect(api.delete).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Удалить чат «Мой вопрос»' }))
    await userEvent.click(screen.getByRole('button', { name: 'Удалить', exact: true }))
    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('architect', 'saved'))
    expect(await screen.findByRole('heading', { name: 'С чего начнём?' })).toBeInTheDocument()
    expect(screen.queryByText('Ответ агента')).not.toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('keeps history and shows an error when deletion fails', async () => {
    const api = makeApi()
    const saved = answer(makeChat('saved'))
    api.chats.mockResolvedValue([saved])
    api.chat.mockResolvedValue(saved)
    api.delete = vi.fn().mockRejectedValue(new Error('Дождитесь завершения запроса'))
    render(<App api={api} />)
    await screen.findByText('Ответ агента')
    await userEvent.click(screen.getByRole('button', { name: 'Удалить чат «Мой вопрос»' }))
    await userEvent.click(screen.getByRole('button', { name: 'Удалить', exact: true }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Дождитесь завершения запроса')
    expect(screen.getByText('Ответ агента')).toBeInTheDocument()
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
