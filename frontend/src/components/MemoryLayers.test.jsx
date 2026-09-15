import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import MemoryLayers from './MemoryLayers'
import { summarizeUsage } from '../usage'

const agent = { id: 'architect', memoryLayers: true, slidingMessages: 10 }
const proposal = { id: 'proposal', scope: 'GLOBAL', key: 'style', value: 'кратко', evidence: 'Предпочитаю кратко' }
const chat = { id: 'chat', strategy: 'SLIDING_WINDOW', messages: [], workingMemory: {
  version: 1, stage: 'REQUIREMENTS', projectKey: '', goal: 'Сервис уведомлений', requirements: { channel: 'email' },
  constraints: { language: 'Java' }, decisions: {}, openQuestions: [], proposals: [proposal],
  usage: { calls: 1, pricedCalls: 1, promptTokens: 10, completionTokens: 20, totalTokens: 30, totalCostUsd: .01 },
  lastExtraction: { durationMs: 50, totalTokens: 30, totalCostUsd: .01 }
} }
const entry = { ...proposal, projectKey: '', updatedAt: '2026-09-14T10:00:00Z', sourceChatId: 'chat' }
const makeApi = () => ({ memory: vi.fn().mockResolvedValue({ version: 0, entries: [] }),
  acceptProposal: vi.fn().mockResolvedValue({ version: 1, entries: [entry], resolvedProposals: ['proposal'] }),
  rejectProposal: vi.fn().mockResolvedValue({ ...chat, workingMemory: { ...chat.workingMemory, version: 2, proposals: [] } }),
  putMemory: vi.fn(), deleteMemory: vi.fn(), editTask: vi.fn(), advanceTask: vi.fn(), chat: vi.fn().mockResolvedValue(chat) })
function Harness({ api, initial = chat }) {
  const [current, setCurrent] = useState(initial)
  return <MemoryLayers agent={agent} chat={current} api={api} onChat={setCurrent} onBusy={() => {}} />
}
describe('explicit memory layers', () => {
  it('shows separate task data and waits for explicit long-term confirmation', async () => {
    const api = makeApi()
    render(<Harness api={api} />)
    expect(screen.getByText('Цель: Сервис уведомлений')).toBeInTheDocument()
    expect(screen.getByText('Java')).toBeInTheDocument()
    expect(await screen.findByText('Пока ничего не сохранено.')).toBeInTheDocument()
    expect(api.acceptProposal).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить «style»' }))
    await waitFor(() => expect(api.acceptProposal).toHaveBeenCalledWith('architect', 'chat', 'proposal', 0, 1))
    expect(await screen.findByRole('button', { name: 'Изменить «style»' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сохранить «style»' })).not.toBeInTheDocument()
  })
  it('rejects a proposal without writing long-term memory', async () => {
    const api = makeApi()
    render(<Harness api={api} />)
    await screen.findByText('Пока ничего не сохранено.')
    await userEvent.click(screen.getByRole('button', { name: 'Отклонить «style»' }))
    await waitFor(() => expect(api.rejectProposal).toHaveBeenCalledWith('architect', 'chat', 'proposal', 1))
    expect(api.acceptProposal).not.toHaveBeenCalled()
    expect(screen.queryByText('Предложения — ещё не сохранены')).not.toBeInTheDocument()
  })
  it('announces conflicts and keeps existing data until refreshed', async () => {
    const api = makeApi()
    api.advanceTask.mockRejectedValue(new Error('Память уже изменилась'))
    render(<Harness api={api} />)
    await screen.findByText('Пока ничего не сохранено.')
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить требования' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Память уже изменилась')
    expect(screen.getByText('Этап: Сбор требований')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Обновить память' }))
    await waitFor(() => expect(api.chat).toHaveBeenCalledWith('architect', 'chat'))
  })
  it('requires deletion confirmation and does not resurrect accepted proposals', async () => {
    const api = makeApi()
    api.memory.mockResolvedValue({ version: 1, entries: [entry], resolvedProposals: ['proposal'] })
    api.deleteMemory.mockResolvedValue({ version: 2, entries: [], resolvedProposals: ['proposal'] })
    render(<Harness api={api} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Удалить «style»' }))
    expect(api.deleteMemory).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Отмена удаления записи' }))
    expect(api.deleteMemory).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Удалить «style»' }))
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить удаление записи' }))
    await waitFor(() => expect(api.deleteMemory).toHaveBeenCalledWith('architect', 'proposal', 1))
    expect(screen.queryByRole('button', { name: 'Сохранить «style»' })).not.toBeInTheDocument()
  })
  it('saves manual entries and renders memory as text, not HTML', async () => {
    const api = makeApi()
    api.putMemory.mockResolvedValue({ version: 1, entries: [{ ...entry, value: '<img src=x onerror=alert(1)>' }] })
    render(<Harness api={api} initial={null} />)
    await screen.findByText('Пока ничего не сохранено.')
    expect(screen.getByRole('button', { name: 'Изменить данные задачи' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Добавить запись памяти' }))
    await userEvent.type(screen.getByLabelText('Ключ записи'), 'style')
    await userEvent.type(screen.getByLabelText('Значение записи'), 'кратко')
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить сохранение записи' }))
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(document.querySelector('.memory-layers img')).toBeNull()
    expect(api.putMemory).toHaveBeenCalledWith('architect', expect.any(String), expect.objectContaining({ key: 'style', value: 'кратко', version: 0 }))
  })
  it('edits task fields with optimistic version and counts extraction once', async () => {
    const api = makeApi()
    api.editTask.mockResolvedValue({ ...chat, workingMemory: { ...chat.workingMemory, version: 2, goal: 'Новая цель' } })
    render(<Harness api={api} />)
    await screen.findByText('Пока ничего не сохранено.')
    await userEvent.click(screen.getByRole('button', { name: 'Изменить данные задачи' }))
    await userEvent.clear(screen.getByLabelText('Цель задачи'))
    await userEvent.type(screen.getByLabelText('Цель задачи'), 'Новая цель')
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить задачу' }))
    expect(await screen.findByText('Цель: Новая цель')).toBeInTheDocument()
    expect(api.editTask).toHaveBeenCalledWith('architect', 'chat', expect.objectContaining({ version: 1,
      task: expect.objectContaining({ goal: 'Новая цель', requirements: { channel: 'email' } }) }))
    expect(summarizeUsage([{ metrics: { promptTokens: 5, completionTokens: 5, totalTokens: 10, totalCostUsd: .02 } }], null, null, chat.workingMemory))
      .toMatchObject({ calls: 2, totalTokens: 40, totalCostUsd: .03 })
  })
})
