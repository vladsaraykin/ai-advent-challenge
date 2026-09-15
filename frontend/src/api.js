async function request(path, options = {}) {
  const response = await fetch('/api/agents' + path, options)
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || 'Не удалось выполнить запрос. Попробуйте ещё раз.')
  return body
}
const chatPath = (agentId, chatId) => `/${encodeURIComponent(agentId)}/chats/${encodeURIComponent(chatId)}`

async function stream(path, body, handlers = {}) {
  const response = await fetch('/api/agents' + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body), signal: handlers.signal
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || 'Не удалось выполнить запрос. Попробуйте ещё раз.')
  }
  if (!response.body) throw new Error('Сервер не открыл поток ответа. Попробуйте ещё раз.')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const dispatch = block => {
    let event = 'message'
    const data = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) return
    const payload = JSON.parse(data.join('\n'))
    if (event === 'error') throw new Error(payload.message || 'Поток ответа завершился с ошибкой.')
    handlers[event]?.(payload)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer = (buffer + decoder.decode(value || new Uint8Array(), { stream: !done })).replaceAll('\r\n', '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      if (block) dispatch(block)
    }
    if (done) break
  }
  if (buffer.trim()) dispatch(buffer)
}

export const agentApi = {
  memory: agentId => request(`/${encodeURIComponent(agentId)}/memory`),
  putMemory: (agentId, id, body) => jsonRequest(`/${encodeURIComponent(agentId)}/memory/${encodeURIComponent(id)}`, 'PUT', body),
  deleteMemory: (agentId, id, version) => jsonRequest(`/${encodeURIComponent(agentId)}/memory/${encodeURIComponent(id)}`, 'DELETE', { version }),
  editTask: (agentId, chatId, body) => jsonRequest(chatPath(agentId, chatId) + '/task', 'PUT', body),
  advanceTask: (agentId, chatId, version) => jsonRequest(chatPath(agentId, chatId) + '/task/advance', 'POST', { version }),
  acceptProposal: (agentId, chatId, id, version, taskVersion) => jsonRequest(chatPath(agentId, chatId)
    + `/proposals/${encodeURIComponent(id)}/accept`, 'POST', { version, taskVersion }),
  rejectProposal: (agentId, chatId, id, version) => jsonRequest(chatPath(agentId, chatId)
    + `/proposals/${encodeURIComponent(id)}/reject`, 'POST', { version }),
  agents: () => request(''),
  chats: agentId => request(`/${encodeURIComponent(agentId)}/chats`),
  create: (agentId, strategy = 'SUMMARY') => request(`/${encodeURIComponent(agentId)}/chats`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ strategy })
  }),
  fork: (agentId, chatId, names) => request(chatPath(agentId, chatId) + '/branches', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(names)
  }),
  chat: (agentId, chatId) => request(chatPath(agentId, chatId)),
  delete: (agentId, chatId) => request(chatPath(agentId, chatId), { method: 'DELETE' }),
  send: (agentId, chatId, message) => request(chatPath(agentId, chatId) + '/messages', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(message)
  }),
  sendStream: (agentId, chatId, message, handlers) =>
    stream(chatPath(agentId, chatId) + '/messages/stream', message, handlers)
}
function jsonRequest(path, method, body) {
  return request(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}
