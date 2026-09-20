import { useState } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
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
  putMemory: vi.fn(), deleteMemory: vi.fn(), editTask: vi.fn(), advanceTask: vi.fn(),
  pauseTask: vi.fn(), resumeTask: vi.fn(), chat: vi.fn().mockResolvedValue(chat) })
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
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Planning · сбор требований')
    await userEvent.click(screen.getByRole('button', { name: 'Обновить память' }))
    await waitFor(() => expect(api.chat).toHaveBeenCalledWith('architect', 'chat'))
  })
  it('shows a rejected validation transition next to the task controls and stays in execution', async () => {
    const api = makeApi()
    const designing = { ...chat, workingMemory: { ...chat.workingMemory, version: 3, stage: 'DESIGN',
      decisions: {}, currentStep: 'Подготовить архитектурные решения', expectedAction: 'RECORD_DECISIONS' } }
    api.advanceTask.mockRejectedValue(new Error('Сначала зафиксируйте архитектурные решения.'))
    render(<Harness api={api} initial={designing} />)
    await screen.findByText('Пока ничего не сохранено.')

    const controls = screen.getByRole('group', { name: 'Управление состоянием задачи' })
    await userEvent.click(within(controls).getByRole('button', { name: 'Передать на проверку' }))

    expect(await within(controls).findByRole('alert')).toHaveTextContent('Сначала зафиксируйте архитектурные решения.')
    expect(api.advanceTask).toHaveBeenCalledWith('architect', 'chat', 3)
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Execution · проектирование')
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Подготовить архитектурные решения')
  })
  it('pauses and resumes the formal task state without changing its stage', async () => {
    const api = makeApi()
    const paused = { ...chat, workingMemory: { ...chat.workingMemory, version: 2, status: 'PAUSED',
      currentStep: 'Планирование приостановлено', expectedAction: 'RESUME_TASK' } }
    api.pauseTask.mockResolvedValue(paused)
    api.resumeTask.mockResolvedValue({ ...chat, workingMemory: { ...chat.workingMemory, version: 3,
      status: 'ACTIVE', currentStep: 'Согласовать план задачи', expectedAction: 'CONFIRM_REQUIREMENTS' } })
    render(<Harness api={api} />)
    await screen.findByText('Пока ничего не сохранено.')
    await userEvent.click(screen.getByRole('button', { name: 'Поставить на паузу' }))
    await waitFor(() => expect(api.pauseTask).toHaveBeenCalledWith('architect', 'chat', 1))
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('На паузе')
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Продолжите задачу')
    await userEvent.click(screen.getByRole('button', { name: 'Продолжить задачу' }))
    await waitFor(() => expect(api.resumeTask).toHaveBeenCalledWith('architect', 'chat', 2))
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Активна')
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Согласовать план задачи')
  })
  it('finishes validation and renders the returned done state', async () => {
    const api = makeApi()
    const reviewing = { ...chat, workingMemory: { ...chat.workingMemory, version: 7, stage: 'REVIEW',
      goal: 'Подготовить план без начала разработки на текущем этапе',
      constraints: { implementation_scope: 'Реализацию на текущем этапе не начинать' },
      decisions: { architecture: 'Модульный монолит' }, currentStep: 'Проверить решение и закрыть замечания',
      expectedAction: 'VALIDATE_RESULT' } }
    const done = { ...reviewing, workingMemory: { ...reviewing.workingMemory, version: 8, stage: 'DONE',
      goal: 'Подготовить план', constraints: {}, currentStep: 'Задача завершена', expectedAction: 'NONE' } }
    api.advanceTask.mockResolvedValue(done)
    render(<Harness api={api} initial={reviewing} />)
    await screen.findByText('Пока ничего не сохранено.')
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить завершение' }))
    await waitFor(() => expect(api.advanceTask).toHaveBeenCalledWith('architect', 'chat', 7))
    expect(screen.getByLabelText('Состояние задачи')).toHaveTextContent('Done · завершено')
    expect(screen.queryByRole('button', { name: 'Подтвердить завершение' })).not.toBeInTheDocument()
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
