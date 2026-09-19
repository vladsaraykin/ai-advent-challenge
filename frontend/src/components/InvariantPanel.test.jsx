import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InvariantPanel from './InvariantPanel'

const agent = { id: 'architect', invariants: true }
const base = { id: 'chat', readOnly: false, invariants: { version: 0, entries: [], usage: {} } }
const entry = { id: 'inv-1', type: 'STACK_CONSTRAINT', title: 'Основная БД',
  rule: 'Использовать только PostgreSQL', rationale: 'Решение команды' }

function Harness({ api }) {
  const [chat, setChat] = useState(base)
  return <InvariantPanel agent={agent} chat={chat} api={api} disabled={false} onChat={setChat} onBusy={() => {}} />
}

describe('task invariants', () => {
  afterEach(() => vi.restoreAllMocks())

  it('creates an explicit typed invariant with optimistic version', async () => {
    const api = { putInvariant: vi.fn().mockResolvedValue({ ...base, invariants: { version: 1, entries: [entry] } }),
      deleteInvariant: vi.fn() }
    render(<Harness api={api} />)
    await userEvent.click(screen.getByRole('button', { name: 'Добавить инвариант' }))
    await userEvent.type(screen.getByLabelText('Название'), 'Основная БД')
    await userEvent.type(screen.getByLabelText('Обязательное правило'), 'Использовать только PostgreSQL')
    await userEvent.type(screen.getByLabelText('Причина или контекст'), 'Решение команды')
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(api.putInvariant).toHaveBeenCalledWith('architect', 'chat', expect.any(String), {
      version: 0, type: 'STACK_CONSTRAINT', title: 'Основная БД',
      rule: 'Использовать только PostgreSQL', rationale: 'Решение команды'
    }))
    expect(await screen.findByText('Использовать только PostgreSQL')).toBeInTheDocument()
  })

  it('requires confirmation before deleting a rule', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const populated = { ...base, invariants: { version: 1, entries: [entry] } }
    const api = { putInvariant: vi.fn(), deleteInvariant: vi.fn().mockResolvedValue({
      ...base, invariants: { version: 2, entries: [] } }) }
    function Populated() {
      const [chat, setChat] = useState(populated)
      return <InvariantPanel agent={agent} chat={chat} api={api} disabled={false} onChat={setChat} onBusy={() => {}} />
    }
    render(<Populated />)
    await userEvent.click(screen.getByRole('button', { name: 'Удалить' }))
    await waitFor(() => expect(api.deleteInvariant).toHaveBeenCalledWith('architect', 'chat', 'inv-1', 1))
    expect(screen.getByText('Инварианты пока не заданы.')).toBeInTheDocument()
  })
})
