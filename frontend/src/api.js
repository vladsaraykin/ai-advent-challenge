async function request(path, options = {}) {
  const response = await fetch('/api/agents' + path, options)
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || 'Не удалось выполнить запрос. Попробуйте ещё раз.')
  return body
}
const chatPath = (agentId, chatId) => `/${encodeURIComponent(agentId)}/chats/${encodeURIComponent(chatId)}`
export const agentApi = {
  agents: () => request(''),
  chats: agentId => request(`/${encodeURIComponent(agentId)}/chats`),
  create: agentId => request(`/${encodeURIComponent(agentId)}/chats`, { method: 'POST' }),
  chat: (agentId, chatId) => request(chatPath(agentId, chatId)),
  send: (agentId, chatId, message) => request(chatPath(agentId, chatId) + '/messages', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(message)
  })
}
