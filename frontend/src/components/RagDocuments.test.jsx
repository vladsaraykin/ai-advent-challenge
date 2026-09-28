import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import RagDocuments from './RagDocuments'

const document = { id: 'doc-one', filename: 'manual.pdf', characterCount: 200, byteSize: 1024 }
const detail = { document, text: '<script>unsafe()</script> Текст документа', metadata: { pageCount: 30, warnings: [] } }
const run = { id: 'run-one', strategy: 'FIXED_SIZE', status: 'COMPLETED', totalChunks: 11, embeddedChunks: 11,
  promptTokens: 500, durationMs: 1500, dimensions: 768, embeddingModel: 'embeddinggemma', createdAt: '2026-09-28T20:00:00Z' }
const chunk = { chunkId: 'chunk-one', ordinal: 0, source: 'manual.pdf', title: 'manual.pdf', section: 'Введение',
  pageStart: 1, pageEnd: 2, startOffset: 0, endOffset: 20, content: '<img src=x onerror=alert(1)>', estimatedTokens: 14 }
const deferred = () => { let resolve; const promise = new Promise(value => { resolve = value }); return { promise, resolve } }
const createApi = () => ({
  ragDocuments: vi.fn().mockResolvedValue([document]), ragDocument: vi.fn().mockResolvedValue(detail),
  ragIndexes: vi.fn().mockResolvedValue([]), ragChunks: vi.fn().mockResolvedValue([chunk]),
  startRagIndex: vi.fn().mockResolvedValue({ ...run, status: 'PENDING', totalChunks: 0, embeddedChunks: 0 }),
  uploadRagDocument: vi.fn().mockResolvedValue(detail), ragPreview: vi.fn()
})
const show = api => render(<RagDocuments api={api} profile={{ username: 'alice', displayName: 'Alice' }} />)

describe('RAG documents', () => {
  it('previews both strategies without generating embeddings', async () => {
    const api = createApi()
    api.ragPreview.mockResolvedValue({ settings: { maxEstimatedTokens: 384, overlapEstimatedTokens: 64 },
      warnings: ['Заголовки PDF определяются эвристически.'], results: ['FIXED_SIZE', 'STRUCTURAL'].map(strategy => ({
        strategy, statistics: { chunkCount: 11, totalEstimatedTokens: 900, minEstimatedTokens: 10, maxEstimatedTokens: 384 }, chunks: [chunk]
      })) })
    show(api)
    await userEvent.click(await screen.findByRole('button', { name: 'Предпросмотр chunking без Ollama' }))
    expect(await screen.findByText(/Текущие настройки сервера/)).toHaveTextContent('384')
    expect(screen.getAllByText(/11 чанков · 900 оценочных токенов/)).toHaveLength(2)
    expect(api.startRagIndex).not.toHaveBeenCalled()
  })

  it('rejects empty files before uploading and preserves a failed upload for retry', async () => {
    const api = createApi(); api.ragDocuments.mockResolvedValue([]); show(api)
    await screen.findByText(/Загрузите первый документ/)
    await userEvent.upload(screen.getByLabelText('Файл документа'), new File([], 'empty.pdf', { type: 'application/pdf' }))
    await userEvent.click(screen.getByRole('button', { name: 'Загрузить документ' }))
    expect(screen.getByRole('alert')).toHaveTextContent('непустой')
    expect(api.uploadRagDocument).not.toHaveBeenCalled()
    api.uploadRagDocument.mockRejectedValue(new Error('Файл повреждён'))
    await userEvent.upload(screen.getByLabelText('Файл документа'), new File(['bad'], 'bad.pdf', { type: 'application/pdf' }))
    await userEvent.click(screen.getByRole('button', { name: 'Загрузить документ' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Файл повреждён')
    expect(screen.getByLabelText('Файл документа').files[0].name).toBe('bad.pdf')
  })
  it('loads document text safely and browses persisted chunks and metrics', async () => {
    const api = createApi(); api.ragIndexes.mockResolvedValue([run]); const { container } = show(api)
    expect(await screen.findByRole('heading', { name: 'manual.pdf' })).toBeInTheDocument()
    expect(await screen.findByText('500')).toBeInTheDocument()
    expect(await screen.findByText('Введение')).toBeInTheDocument()
    expect(container.querySelector('script')).toBeNull(); expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText(chunk.content)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Следующие чанки' }))
    await waitFor(() => expect(api.ragChunks).toHaveBeenLastCalledWith('run-one', 10, expect.any(AbortSignal)))
  })

  it('announces uploading immediately and does not call Ollama automatically', async () => {
    const api = createApi(); const pending = deferred(); api.uploadRagDocument.mockReturnValue(pending.promise)
    api.ragDocuments.mockResolvedValue([]); show(api)
    await screen.findByText(/Загрузите первый документ/)
    await userEvent.upload(screen.getByLabelText('Файл документа'), new File(['pdf'], 'manual.pdf', { type: 'application/pdf' }))
    await userEvent.click(screen.getByRole('button', { name: 'Загрузить документ' }))
    expect(screen.getByRole('button', { name: 'Загрузка и извлечение…' })).toBeDisabled()
    expect(screen.getByText(/Сервер принимает файл/)).toBeInTheDocument()
    api.ragDocuments.mockResolvedValue([document])
    await act(async () => pending.resolve(detail))
    await screen.findByRole('heading', { name: 'manual.pdf' })
    expect(api.startRagIndex).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Файл документа').files).toHaveLength(0)
  })

  it('starts a strategy once and polls until completion', async () => {
    const api = createApi(); const pending = deferred(); api.startRagIndex.mockReturnValue(pending.promise); show(api)
    const fixed = await screen.findByRole('region', { name: 'Fixed-size' })
    await waitFor(() => expect(within(fixed).getByRole('button', { name: 'Индексировать' })).toBeEnabled())
    await userEvent.click(within(fixed).getByRole('button', { name: 'Индексировать' }))
    expect(within(fixed).getByRole('button', { name: 'Отправляем…' })).toBeDisabled()
    api.ragIndexes.mockResolvedValueOnce([{ ...run, status: 'EMBEDDING', embeddedChunks: 2 }]).mockResolvedValue([run])
    await act(async () => pending.resolve({ ...run, status: 'PENDING' }))
    expect(await screen.findByRole('progressbar', { name: 'Прогресс Fixed-size' })).toHaveAttribute('value', '2')
    await waitFor(() => expect(screen.getByText(/Готово · 11 \/ 11/)).toBeInTheDocument(), { timeout: 4000 })
    expect(api.startRagIndex).toHaveBeenCalledTimes(1)
    expect(api.startRagIndex).toHaveBeenCalledWith('doc-one', 'FIXED_SIZE', expect.any(AbortSignal))
  })

  it('explains provider failure and retains old runs when retrying', async () => {
    const api = createApi(); api.ragIndexes.mockResolvedValue([{ ...run, status: 'FAILED', errorCode: 'OLLAMA_INPUT_REJECTED' }, { ...run, id: 'old' }]); show(api)
    expect(await screen.findByText(/Ollama отклонила чанк/)).toBeInTheDocument()
    const fixed = screen.getByRole('region', { name: 'Fixed-size' })
    await userEvent.selectOptions(within(fixed).getByRole('combobox'), 'old')
    expect(within(fixed).getByText(/Готово · 11/)).toBeInTheDocument()
    api.startRagIndex.mockRejectedValue(new Error('Очередь заполнена'))
    await userEvent.click(within(fixed).getByRole('button', { name: 'Создать новый запуск' }))
    expect(await screen.findByText(/Очередь заполнена/)).toBeInTheDocument()
    expect(within(fixed).getByText(/Готово · 11/)).toBeInTheDocument()
  })

  it('does not display stale document data after switching and aborts on unmount', async () => {
    const api = createApi(); const pending = deferred()
    api.ragDocuments.mockResolvedValue([document, { ...document, id: 'two', filename: 'second.docx' }])
    api.ragDocument.mockReturnValueOnce(pending.promise).mockResolvedValue({ ...detail, document: { ...document, id: 'two', filename: 'second.docx' } })
    const view = show(api)
    await waitFor(() => expect(api.ragDocument).toHaveBeenCalledTimes(1))
    await userEvent.selectOptions(screen.getByLabelText('Мои документы'), 'two')
    await screen.findByRole('heading', { name: 'second.docx' })
    await act(async () => pending.resolve(detail))
    expect(screen.queryByRole('heading', { name: 'manual.pdf' })).toBeNull()
    expect(api.ragDocument.mock.calls[0][1].aborted).toBe(true)
    view.unmount()
    expect(api.ragIndexes.mock.calls.at(-1)[1].aborted).toBe(true)
  })

  it('explains disabled RAG and supports retry', async () => {
    const api = createApi(); api.ragDocuments.mockRejectedValueOnce(Object.assign(new Error('Not found'), { status: 404 })).mockResolvedValue([])
    show(api)
    expect(await screen.findByRole('alert')).toHaveTextContent('RAG_ENABLED')
    await userEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }))
    expect(await screen.findByText(/Загрузите первый документ/)).toBeInTheDocument()
  })
})
