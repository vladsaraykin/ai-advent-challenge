import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import McpCatalog from './McpCatalog'

const servers = [
  { id: 'project-files', name: 'Project files', connected: true, version: '1.0', protocolVersion: '2025-11-25',
    tools: [{ name: 'project_read_files', title: 'Читать файлы', description: 'Чтение файлов проекта', readOnly: true,
      inputSchema: { type: 'object', properties: { paths: { type: 'array' } } } }] },
  { id: 'project-git', name: 'Project git', connected: true,
    tools: [{ name: 'get_current_branch', description: 'Текущая ветка', inputSchema: {} }] }
]

it('starts collapsed and independently expands and collapses each server without reloading discovery', async () => {
  const user = userEvent.setup()
  const onReload = vi.fn()
  render(<McpCatalog embedded servers={servers} onReload={onReload} />)
  const files = screen.getByRole('button', { name: 'Инструменты Project files' })
  const git = screen.getByRole('button', { name: 'Инструменты Project git' })
  expect(files).toHaveAttribute('aria-expanded', 'false')
  expect(git).toHaveAttribute('aria-expanded', 'false')
  expect(screen.queryByText('project_read_files')).not.toBeInTheDocument()

  await user.click(files)
  expect(files).toHaveAttribute('aria-expanded', 'true')
  const region = screen.getByRole('region', { name: 'Инструменты Project files' })
  expect(region.id).toEqual(files.getAttribute('aria-controls'))
  expect(within(region).getByText('project_read_files')).toBeInTheDocument()
  expect(within(region).getByText('2025-11-25')).toBeInTheDocument()
  await user.click(within(region).getByText('Схема параметров'))
  expect(within(region).getByText(/"paths":/)).toBeVisible()

  await user.click(git)
  expect(screen.getByRole('heading', { name: 'get_current_branch' })).toBeInTheDocument()
  await user.click(files)
  expect(screen.queryByText('project_read_files')).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'get_current_branch' })).toBeInTheDocument()
  expect(onReload).not.toHaveBeenCalled()
})

it('supports Enter and Space and retains expansion by server id when discovery data changes order', async () => {
  const user = userEvent.setup()
  const { rerender } = render(<McpCatalog embedded servers={servers} />)
  screen.getByRole('button', { name: 'Инструменты Project files' }).focus()
  await user.keyboard('{Enter}')
  expect(screen.getByText('project_read_files')).toBeInTheDocument()
  rerender(<McpCatalog embedded servers={[...servers].reverse()} />)
  const files = screen.getByRole('button', { name: 'Инструменты Project files' })
  expect(files).toHaveAttribute('aria-expanded', 'true')
  files.focus()
  await user.keyboard(' ')
  expect(files).toHaveAttribute('aria-expanded', 'false')
})

it('keeps connection errors visible when collapsed and explains an empty tool list', async () => {
  const user = userEvent.setup()
  render(<McpCatalog embedded servers={[
    { id: 'offline', name: 'Offline', connected: false, error: 'Соединение недоступно', tools: [] },
    { id: 'empty', name: 'Empty', connected: true, tools: [] }
  ]} />)
  expect(screen.getByRole('alert')).toHaveTextContent('Соединение недоступно')
  expect(screen.getByText('Недоступен')).toBeVisible()
  await user.click(screen.getByRole('button', { name: 'Инструменты Empty' }))
  expect(screen.getByText('Сервер не предоставил инструментов.')).toBeVisible()
})

it('preserves loading, global retry and unconfigured states', async () => {
  const user = userEvent.setup()
  const onReload = vi.fn()
  const { rerender } = render(<McpCatalog embedded loading servers={servers} />)
  expect(screen.getByRole('status')).toHaveTextContent('загружаем инструменты')
  expect(screen.queryByRole('button', { name: /Инструменты/ })).not.toBeInTheDocument()
  rerender(<McpCatalog embedded error="Не удалось загрузить подключения" onReload={onReload} />)
  await user.click(screen.getByRole('button', { name: 'Повторить' }))
  expect(onReload).toHaveBeenCalledOnce()
  rerender(<McpCatalog embedded servers={[]} />)
  expect(screen.getByRole('status')).toHaveTextContent('MCP-серверы не настроены')
})
