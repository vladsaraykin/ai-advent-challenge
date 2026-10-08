import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import App from './App'

const settings = { version: 2, ragEnabled: true, mcpServerIds: ['files'], retrieval: { candidateK: 20, finalK: 5, threshold: .2 } }
const chat = { id: 'chat-a', agentId: 'assistant', title: 'Анализ документа', messages: [], updatedAt: '2026-10-04T10:00:00Z' }
const apiFixture = () => ({
  agents: vi.fn().mockResolvedValue([{ id: 'assistant', name: 'Личный ассистент', description: 'Помощник', model: 'test-model' }]),
  chats: vi.fn().mockResolvedValue([chat]), chat: vi.fn().mockResolvedValue(chat),
  chatSettings: vi.fn().mockResolvedValue(settings),
  saveChatSettings: vi.fn().mockImplementation(async (_agent, _chat, value) => ({ ...value, version: value.version + 1 })),
  chatHistory: vi.fn().mockResolvedValue([{ id: 'old', role: 'USER', content: 'Сохранённая полная история' }]),
  pendingRequest: vi.fn().mockResolvedValue({}),
  mcpServers: vi.fn().mockResolvedValue([{ id: 'files', name: 'Файловый сервер', connected: true, tools: [{}] }]),
  sendStream: vi.fn(), approveTool: vi.fn(), cancelRequest: vi.fn().mockResolvedValue({})
})
beforeEach(() => localStorage.clear())

it('keeps chat properties in the inspector and usage below the composer', async () => {
  const api = apiFixture()
  api.chat.mockResolvedValue({ ...chat, messages: [{ id: 'answer', role: 'ASSISTANT', content: 'Ответ',
    metrics: { promptTokens: 10, completionTokens: 5, totalTokens: 15, totalCostUsd: 0 } }] })
  render(<App api={api} />)
  await screen.findByText('Сохранённая полная история')
  const inspector = screen.getByRole('complementary', { name: 'Параметры и память текущего чата' })
  expect(within(inspector).getByRole('combobox', { name: 'Провайдер LLM' })).toBeInTheDocument()
  expect(within(inspector).getByRole('checkbox', { name: 'RAG', exact: true })).toBeInTheDocument()
  const conversation = screen.getByRole('region', { name: 'Диалог с агентом' })
  expect(within(conversation).queryByRole('combobox', { name: 'Провайдер LLM' })).not.toBeInTheDocument()
  const composer = screen.getByLabelText('Ваше сообщение').closest('form')
  const usage = screen.getByRole('region', { name: 'Суммарный расход чата' })
  expect(composer.compareDocumentPosition(usage) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  await userEvent.click(screen.getByRole('button', { name: 'Память и настройки', exact: true }))
  expect(screen.queryByRole('combobox', { name: 'Провайдер LLM' })).not.toBeInTheDocument()
})

it('persists Local MLX selection and restores the local model without requiring OpenAI', async () => {
  const api = apiFixture()
  api.llmProviders = vi.fn().mockResolvedValue([
    { id: 'OPENAI', name: 'OpenAI', enabled: false },
    { id: 'LOCAL_MLX', name: 'Local MLX', model: 'mlx-community/Qwen3.5-9B-4bit', enabled: true, toolsEnabled: false }
  ])
  render(<App api={api} />)
  await screen.findByText('Сохранённая полная история')
  expect(await screen.findByText('Выберите настроенного провайдера.')).toBeInTheDocument()
  await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Провайдер LLM' }), 'LOCAL_MLX')
  await waitFor(() => expect(api.saveChatSettings).toHaveBeenCalledWith('assistant', 'chat-a', { ...settings, provider: 'LOCAL_MLX' }))
  expect(await screen.findByText('mlx-community/Qwen3.5-9B-4bit')).toBeInTheDocument()
  expect(screen.getByText(/Локальная генерация/)).toBeInTheDocument()
})

it('shows local API billing separately and never presents absent token usage as zero', async () => {
  const api = apiFixture()
  api.chatHistory.mockResolvedValue([{ id: 'local-answer', role: 'ASSISTANT', content: 'Локальный ответ', metrics: {
    model: 'qwen', provider: 'LOCAL_MLX', usageAvailable: false, totalTokens: 0, promptTokens: 0,
    completionTokens: 0, durationMs: 1500, totalCostUsd: 0
  } }])
  render(<App api={api} />)
  expect(await screen.findByText(/Токены: сервер не вернул данные/)).toBeInTheDocument()
  expect(screen.getByText('Стоимость: локально · без оплаты API')).toBeInTheDocument()
  expect(screen.queryByText(/Вход API: 0/)).not.toBeInTheDocument()
})

it('loads full stored history and persists RAG preference before sending the next request', async () => {
  const api = apiFixture()
  api.sendStream.mockImplementation(async (_agent, _chat, request, handlers) => {
    handlers.completed({ chat: { ...chat, messages: [{ id: request.messageId, role: 'USER', content: request.content }] } })
  })
  render(<App api={api} />)
  expect(await screen.findByText('Сохранённая полная история')).toBeInTheDocument()
  const rag = screen.getByRole('checkbox', { name: 'RAG', exact: true })
  expect(rag).toBeChecked()
  await userEvent.click(rag)
  await waitFor(() => expect(api.saveChatSettings).toHaveBeenCalledWith('assistant', 'chat-a', { ...settings, ragEnabled: false }))
  await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Продолжим')
  await userEvent.keyboard('{Enter}')
  await waitFor(() => expect(api.sendStream).toHaveBeenCalledTimes(1))
  expect(api.sendStream.mock.calls[0][2].content).toBe('Продолжим')
  expect(api.sendStream.mock.calls[0][2].mcpServerIds).toEqual(['files'])
})

it('restores a pending tool confirmation after reload and never approves without user action', async () => {
  const api = apiFixture()
  const call = { id: 'tool-call', server: 'files', tool: 'save_report', input: '{"path":"report.txt"}', status: 'AWAITING_APPROVAL' }
  api.pendingRequest.mockResolvedValue({ request: { status: 'AWAITING_APPROVAL', context: { messageId: 'request-a', content: 'Сохрани отчёт' } }, calls: [call] })
  const sources = [{ number: 1, chunk: { chunkId: 'chunk-a', source: 'whitepaper.pdf', title: 'Документ', section: 'Раздел 1', content: 'Полный текст подтверждающего фрагмента.' } }]
  api.chatHistory.mockResolvedValueOnce([]).mockResolvedValue([{ id: 'answer', role: 'ASSISTANT', content: 'Вывод по документу [1].', evidence: {
    sources, grounding: { status: 'ANSWERED', quotes: [{ sourceNumber: 1, quote: 'подтверждающего фрагмента' }] }
  } }])
  api.approveTool.mockImplementation(async (_agent, _chat, _id, _body, handlers) => handlers.completed({ chat }))
  render(<App api={api} />)
  const approval = await screen.findByRole('region', { name: 'Подтверждение MCP-инструмента' })
  expect(within(approval).getByText('save_report')).toBeInTheDocument()
  expect(screen.getByText('Сохрани отчёт')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled()
  expect(api.approveTool).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: 'Разрешить вызов' }))
  await waitFor(() => expect(api.approveTool).toHaveBeenCalledWith('assistant', 'chat-a', 'request-a', { callId: 'tool-call', allow: true }, expect.any(Object)))
  expect(await screen.findByText('Вывод по документу [1].')).toBeInTheDocument()
  expect(screen.getByText(/whitepaper.pdf/)).toBeInTheDocument()
  expect(screen.getByText('Полный текст подтверждающего фрагмента.')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Подтверждение MCP-инструмента' })).not.toBeInTheDocument()
})

it('keeps submitted text visible while waiting for a tool approval, clearing the composer immediately', async () => {
  const api = apiFixture()
  api.sendStream.mockImplementation(async (_agent, _chat, _body, handlers) => {
    handlers.approval({ id: 'call', server: 'files', tool: 'save_report', input: '{}' })
  })
  render(<App api={api} />)
  await screen.findByText('Сохранённая полная история')
  await userEvent.type(screen.getByLabelText('Ваше сообщение'), 'Запиши результат')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить' }))
  await screen.findByRole('region', { name: 'Подтверждение MCP-инструмента' })
  expect(screen.getByText('Запиши результат')).toBeInTheDocument()
  expect(screen.getByLabelText('Ваше сообщение')).toHaveValue('')
  await userEvent.click(screen.getByRole('button', { name: 'Отменить запрос' }))
  await waitFor(() => expect(api.cancelRequest).toHaveBeenCalledWith('assistant', 'chat-a', expect.any(String)))
  expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled() // Empty composer, not a stuck request.
})
