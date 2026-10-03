import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import RagQuestions from './RagQuestions'

const indexes = [{ id: 'index-one', strategy: 'FIXED_SIZE', createdAt: '2026-09-29T10:00:00Z' }]
const createApi = () => ({ ragAnswerSettings: vi.fn().mockResolvedValue({ model: 'gpt-6.1-sol', topK: 5, maxCompletionTokens: 4096 }),
  ragRetrievalSettings: vi.fn().mockResolvedValue({ candidateK: 20, finalK: 5, threshold: 0.2 }),
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
    const answer = { mode: 'WITH_RAG', text: 'Ответ [1]', sources: [source], grounding: { status: 'VERIFIED', quotes: [{ sourceNumber: 1, quote: 'Источник' }] }, metrics: { model: 'gpt-6.1-sol', generationMs: 500, promptTokens: 100, completionTokens: 20, totalTokens: 120, costUsd: 0.0004, embeddingMs: 10, searchMs: 2 } }
    await act(async () => {
      handlers.completed({ ...api.askRag.mock.calls[0][0], status: 'COMPLETED', answers: [answer] }); resolve()
    })
    expect(screen.getByText(/оценка стоимости:/)).toHaveTextContent('$0.000400')
    expect(screen.getByLabelText('Ваш вопрос')).toHaveValue('Что такое PDLC?')
    expect(screen.getByRole('region', { name: 'Подтверждения ответа' })).toHaveTextContent('chunk_id: chunk-1')
    expect(screen.getByRole('button', { name: 'Открыть полный чанк' })).toBeInTheDocument()
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
  it('loads a saved refusal without inventing quotes and shows skipped generation', async () => {
    const api = createApi()
    api.ragQuestions.mockResolvedValue([{ id: 'saved', question: 'Нет ответа', status: 'COMPLETED', answers: [
      { mode: 'RERANKED', text: 'Не знаю. Уточните вопрос.', sources: [],
        grounding: { status: 'INSUFFICIENT_CONTEXT', reason: 'NO_ELIGIBLE_CONTEXT', quotes: [] },
        metrics: { model: 'gpt-6.1-sol', generationMs: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0,
          costUsd: 0, embeddingMs: 10, searchMs: 2, finishReason: 'not_called' } }
    ] }])
    render(<RagQuestions api={api} indexes={indexes} />)
    await screen.findByRole('option', { name: 'Нет ответа · COMPLETED' })
    await userEvent.selectOptions(screen.getByLabelText('Сохранённые вопросы'), 'saved')
    expect(screen.getByText('Не знаю. Уточните вопрос.')).toBeInTheDocument()
    expect(screen.getByText(/генерация не вызывалась/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Открыть полный чанк' })).not.toBeInTheDocument()
  })
  it('sends ranking settings and displays three answers, rewrite costs and rejected candidates', async () => {
    const api = createApi()
    api.askRag.mockImplementation(async (request, handlers) => {
      const source = { number: 1, similarity: 0.9, chunk: { source: 'paper.pdf', section: 'Раздел', chunkId: 'c1', content: 'Текст источника' } }
      const metrics = { model: 'gpt-6.1-sol', generationMs: 100, embeddingMs: 10, searchMs: 2, promptTokens: 10, completionTokens: 5, totalTokens: 15, costUsd: 0.0001 }
      const retrieval = { searchQuery: 'Переформулированный вопрос', rerankingMs: 25, options: request.retrievalOptions,
        rewriteMetrics: metrics, candidates: [{ source, rerankerScore: 0.1, decision: 'THRESHOLD' }] }
      handlers.retrieval({ mode: 'REWRITTEN', retrieval })
      handlers.completed({ ...request, status: 'COMPLETED', answers: [
        { mode: 'WITH_RAG', text: 'Базовый ответ', sources: [source], metrics },
        { mode: 'RERANKED', text: 'Отфильтрованный ответ', sources: [], metrics },
        { mode: 'REWRITTEN', text: 'Нет достаточных источников', sources: [], metrics, retrieval }] })
    })
    render(<RagQuestions api={api} indexes={indexes} />)
    await userEvent.selectOptions(screen.getByLabelText('Режим ответа'), 'COMPARE')
    await userEvent.clear(screen.getByLabelText('Порог reranker'))
    await userEvent.type(screen.getByLabelText('Порог reranker'), '0.4')
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Как передать сессию?')
    await userEvent.click(screen.getByRole('button', { name: 'Сравнить три режима' }))
    expect(api.askRag.mock.calls[0][0]).toMatchObject({ mode: 'COMPARE', retrievalOptions: { candidateK: 20, finalK: 5, threshold: 0.4 } })
    expect(await screen.findByText('Базовый ответ')).toBeInTheDocument()
    expect(screen.getByText(/Поисковый вопрос:/)).toHaveTextContent('Переформулированный вопрос')
    expect(screen.getByText(/Rewrite:/)).toHaveTextContent('ответ + rewrite: $0.000200')
    expect(screen.getByText(/найдено 1, передано 0/)).toBeInTheDocument()
    expect(screen.getByText(/Ниже порога/)).toBeInTheDocument()
    expect(screen.getByText(/reranker: 0.1000/)).toBeInTheDocument()
  })
  it('rejects final top-K larger than candidate count without sending a request', async () => {
    const api = createApi()
    render(<RagQuestions api={api} indexes={indexes} />)
    await waitFor(() => expect(api.ragRetrievalSettings).toHaveBeenCalled())
    await userEvent.selectOptions(screen.getByLabelText('Режим ответа'), 'RERANKED')
    await userEvent.clear(screen.getByLabelText('Top-K до reranker'))
    await userEvent.type(screen.getByLabelText('Top-K до reranker'), '2')
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Вопрос')
    await userEvent.click(screen.getByRole('button', { name: 'Получить ответ' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('не больше')
    expect(api.askRag).not.toHaveBeenCalled()
  })
})
