import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import ProjectWorkspace from './ProjectWorkspace'
import ToolApproval from './ToolApproval'

it('restores branch, diff, snapshot-bound checks and a validated PR link', async () => {
  const api = { projectWorkspace: vi.fn().mockResolvedValue({ workspace: { id: 'task', revision: 4, state: {
    branch: 'ai/task-demo', headSha: 'a'.repeat(40), status: ' M src/Auth.java', relativePath: 'data/project-workspaces/task',
    diff: '+new implementation', prUrl: 'https://github.com/vladsaraykin/ai-advent-challenge/pull/123',
    lastCheck: { check: 'backend-test', passed: true, treeHash: 'b'.repeat(64) }
  } } }) }
  render(<ProjectWorkspace api={api} agentId="assistant" chatId="task" pending={false} revision={1} />)
  expect(await screen.findByText('ai/task-demo')).toBeVisible()
  expect(screen.getByText('+new implementation')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Открыть Pull Request в main' })).toHaveAttribute('href', 'https://github.com/vladsaraykin/ai-advent-challenge/pull/123')
  expect(screen.getByText(/не ко всем будущим правкам/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Обновить рабочую область' }))
  await waitFor(() => expect(api.projectWorkspace).toHaveBeenCalledTimes(2))
})

it('does not display another chat workspace after switching and rejects untrusted PR URLs', async () => {
  let resolveOld
  const api = { projectWorkspace: vi.fn().mockImplementation((_agent, id) => id === 'old'
    ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ workspace: { revision: 0, state: { branch: 'new-task', prUrl: 'javascript:alert(1)' } } })) }
  const { rerender } = render(<ProjectWorkspace api={api} agentId="assistant" chatId="old" />)
  rerender(<ProjectWorkspace api={api} agentId="assistant" chatId="new" />)
  await screen.findByText('new-task')
  resolveOld({ workspace: { state: { branch: 'old-task' } } })
  await waitFor(() => expect(screen.queryByText('old-task')).not.toBeInTheDocument())
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})

it.each(['project_delete_file', 'project_git_push', 'project_git_create_pr'])('requires one-shot approval for %s', async tool => {
  const onDecide = vi.fn()
  render(<ToolApproval call={{ server: tool === 'project_delete_file' ? 'project-files' : 'project-git', tool, input: '{}', toolSignature: 'hash' }} onDecide={onDecide} />)
  expect(screen.queryByRole('button', { name: 'Разрешить инструмент в этом чате' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Разрешить вызов' }))
  expect(onDecide).toHaveBeenCalledWith(true)
})
