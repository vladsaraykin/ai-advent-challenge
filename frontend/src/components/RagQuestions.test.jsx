import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import RagQuestions from './RagQuestions'

const indexes = [{ id: 'index-one', strategy: 'FIXED_SIZE', createdAt: '2026-09-29T10:00:00Z' }]
const createApi = () => ({ ragAnswerSettings: vi.fn().mockResolvedValue({ model: 'gpt-6.1-sol', topK: 5, maxCompletionTokens: 4096 }),
  ragQuestions: vi.fn().mockResolvedValue([]), askRag: vi.fn() })

describe('RAG questions', () => {
  it('streams both modes, displays the question immediately and opens cited sources', async () => {
    const api = createApi(); let handlers; let resolve
    api.askRag.mockImplementation((request, value) => { handlers = value; return new Promise(done => { resolve = done }) })
    const { container } = render(<RagQuestions api={api} indexes={indexes} />)
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Что такое PDLC?')
    const button = screen.getByRole('button', { name: 'Получить оба ответа' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)
    expect(screen.getByRole('button', { name: 'Получаем ответ…' })).toBeDisabled()
    expect(container.querySelector('blockquote')).toHaveTextContent('Что такое PDLC?')
    expect(api.askRag.mock.calls[0][0]).toMatchObject({ mode: 'BOTH', indexId: 'index-one' })
    const source = { number: 1, similarity: 0.8, chunk: { source: 'manual.pdf', chunkId: 'chunk-1', content: 'Источник', pageStart: 5, pageEnd: 5 } }
    await act(async () => {
      handlers.sources({ mode: 'WITH_RAG', sources: [source] })
      handlers.delta({ mode: 'WITH_RAG', text: 'Ответ [1]<script>bad()</script>' })
    })
    expect(container.querySelector('script')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '[1]' }))
    expect(container.querySelector('details')).toHaveAttribute('open')
    const answer = { mode: 'WITH_RAG', text: 'Ответ [1]', sources: [source], metrics: { model: 'gpt-6.1-sol', generationMs: 500, promptTokens: 100, completionTokens: 20, totalTokens: 120, costUsd: 0.0004, embeddingMs: 10, searchMs: 2 } }
    await act(async () => {
      handlers.completed({ ...api.askRag.mock.calls[0][0], status: 'COMPLETED', answers: [answer] }); resolve()
    })
    expect(screen.getByText(/оценка стоимости:/)).toHaveTextContent('$0.000400')
    expect(screen.getByLabelText('Ваш вопрос')).toHaveValue('Что такое PDLC?')
  })

  it('removes incomplete output on failure and preserves the draft for retry', async () => {
    const api = createApi()
    api.askRag.mockImplementation(async (request, handlers) => {
      handlers.delta({ mode: 'WITHOUT_RAG', text: 'Частичный секрет' }); throw new Error('Связь прервана')
    })
    render(<RagQuestions api={api} indexes={indexes} />)
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Вопрос')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Получить оба ответа' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Получить оба ответа' }))
    expect(await screen.findByText('Связь прервана')).toBeInTheDocument()
    expect(screen.queryByText('Частичный секрет')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Ваш вопрос')).toHaveValue('Вопрос')
  })

  it('requires a completed index', async () => {
    render(<RagQuestions api={createApi()} indexes={[]} />)
    expect(await screen.findByText('Сначала завершите индексацию документа.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Получить оба ответа' })).toBeDisabled()
  })
})
