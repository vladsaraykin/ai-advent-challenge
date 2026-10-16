import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import ChatSidebar from './ChatSidebar'

const chats = [
  { id: 'a', title: 'Рецепт пасты', messageCount: 2, updatedAt: '2026-10-16', strategy: 'SUMMARY' },
  { id: 'b', title: 'Другой разговор', messageCount: 4, updatedAt: '2026-10-16', strategy: 'SUMMARY' },
]
const props = { agents: [{ id: 'chef', name: 'Повар' }], agentId: 'chef', chats, activeView: 'chat', onChat: vi.fn(), onAgent: vi.fn() }
describe('chat title search', () => {
  it('searches through the API and keeps matching chats selectable', async () => {
    const searchChats = vi.fn().mockResolvedValue([chats[0]])
    render(<ChatSidebar {...props} searchChats={searchChats} />)
    await userEvent.type(screen.getByRole('searchbox', { name: 'Поиск чатов по названию' }), 'паста')
    await waitFor(() => expect(searchChats).toHaveBeenCalledWith('chef', 'паста', expect.any(AbortSignal)))
    await waitFor(() => expect(screen.queryByText('Другой разговор')).not.toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Рецепт пасты/ }))
    expect(props.onChat).toHaveBeenCalledWith('a')
  })
  it('announces a search failure without claiming no matches', async () => {
    const searchChats = vi.fn().mockRejectedValue(new Error('Сервер недоступен'))
    render(<ChatSidebar {...props} searchChats={searchChats} />)
    await userEvent.type(screen.getByRole('searchbox'), 'тест')
    expect(await screen.findByRole('alert')).toHaveTextContent('Сервер недоступен')
    expect(screen.queryByText('Чаты не найдены.')).not.toBeInTheDocument()
  })
})
