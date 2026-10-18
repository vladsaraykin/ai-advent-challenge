import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import ToolPermissions from './ToolPermissions'
import ToolApproval from './ToolApproval'

it('offers explicit one-shot or chat-wide consent and warns about arbitrary future parameters', async () => {
  const user = userEvent.setup(), onDecide = vi.fn()
  render(<ToolApproval call={{ tool: 'write', server: 'files', input: '{}', toolSignature: 'hash' }} onDecide={onDecide} />)
  expect(screen.getByText(/включая изменение файлов и данных/)).toBeVisible()
  await user.click(screen.getByRole('button', { name: 'Разрешить инструмент в этом чате' }))
  expect(onDecide).toHaveBeenLastCalledWith(true, true)
  await user.click(screen.getByRole('button', { name: 'Разрешить вызов' }))
  expect(onDecide).toHaveBeenLastCalledWith(true)
})

it('does not offer persistent consent for legacy calls with no captured tool schema', () => {
  render(<ToolApproval call={{ tool: 'write', server: 'files', input: '{}', toolSignature: null }} />)
  expect(screen.getByRole('button', { name: 'Разрешить инструмент в этом чате' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Разрешить вызов' })).toBeEnabled()
})

it('loads persisted chat permissions and revokes only after an explicit click', async () => {
  const user = userEvent.setup()
  const api = { toolPermissions: vi.fn().mockResolvedValue([{ server: 'files', tool: 'read' }]), revokeToolPermission: vi.fn().mockResolvedValue() }
  render(<ToolPermissions api={api} agentId="assistant" chatId="chat-a" revision={0} />)
  const button = await screen.findByRole('button', { name: 'Отозвать разрешение files/read' })
  expect(api.revokeToolPermission).not.toHaveBeenCalled()
  await user.click(button)
  expect(api.revokeToolPermission).toHaveBeenCalledWith('assistant', 'chat-a', 'files', 'read')
  expect(await screen.findByText('Постоянных разрешений нет.')).toBeVisible()
})

it('keeps a failed revocation visible and disables changes while a request is active', async () => {
  const user = userEvent.setup()
  const api = { toolPermissions: vi.fn().mockResolvedValue([{ server: 'files', tool: 'write' }]), revokeToolPermission: vi.fn().mockRejectedValue(new Error('offline')) }
  const { rerender } = render(<ToolPermissions api={api} agentId="a" chatId="c" disabled />)
  expect(await screen.findByRole('button', { name: 'Отозвать разрешение files/write' })).toBeDisabled()
  rerender(<ToolPermissions api={api} agentId="a" chatId="c" disabled={false} />)
  await user.click(screen.getByRole('button', { name: 'Отозвать разрешение files/write' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось отозвать')
  expect(screen.getByText('files/write')).toBeVisible()
})
