let credentials = ''
try { credentials = sessionStorage.getItem('agent-lab.basic') || '' } catch { /* Optional storage. */ }

const authHeaders = headers => ({ ...(headers || {}), ...(credentials ? { Authorization: `Basic ${credentials}` } : {}) })
async function rawRequest(path, options = {}) {
  const response = await fetch(path, { ...options, headers: authHeaders(options.headers) })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(body.message || 'Не удалось выполнить запрос. Попробуйте ещё раз.')
    error.status = response.status
    throw error
  }
  return body
}
async function request(path, options = {}) { return rawRequest('/api/agents' + path, options) }
const chatPath = (agentId, chatId) => `/${encodeURIComponent(agentId)}/chats/${encodeURIComponent(chatId)}`

async function stream(path, body, handlers = {}, prefix = '/api/agents') {
  const response = await fetch(prefix + path, {
    method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
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
  setCredentials: (username, password) => {
    credentials = btoa(unescape(encodeURIComponent(`${username}:${password}`)))
    try { sessionStorage.setItem('agent-lab.basic', credentials) } catch { /* Optional storage. */ }
  },
  clearCredentials: () => {
    credentials = ''
    try { sessionStorage.removeItem('agent-lab.basic') } catch { /* Optional storage. */ }
  },
  me: () => rawRequest('/api/auth/me'),
  register: body => rawRequest('/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  }),
  profile: () => rawRequest('/api/profile'),
  updateProfile: body => rawRequest('/api/profile', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  }),
  mcpServers: () => rawRequest('/api/mcp/servers'),
  knowledgeStats: () => rawRequest('/api/harness/knowledge/stats'),
  ragAnswerSettings: signal => rawRequest('/api/rag/answer-settings', { signal }),
  ragRetrievalSettings: signal => rawRequest('/api/rag/retrieval-settings', { signal }),
  ragQuestions: (id, signal) => rawRequest(`/api/rag/indexes/${encodeURIComponent(id)}/questions`, { signal }),
  askRag: (body, handlers) => stream('/questions/stream', body, handlers, '/api/rag'),
  ragDocuments: (offset = 0, signal) => rawRequest(`/api/rag/documents?limit=20&offset=${offset}`, { signal }),
  ragDocument: (id, signal) => rawRequest(`/api/rag/documents/${encodeURIComponent(id)}`, { signal }),
  uploadRagDocument: (file, signal) => {
    const body = new FormData()
    body.append('file', file)
    return rawRequest('/api/rag/documents', { method: 'POST', body, signal })
  },
  ragPreview: (id, signal) => rawRequest(`/api/rag/documents/${encodeURIComponent(id)}/chunking?limit=3`, { signal }),
  ragIndexes: (id, signal) => rawRequest(`/api/rag/documents/${encodeURIComponent(id)}/indexes`, { signal }),
  startRagIndex: (id, strategy, signal) => rawRequest(`/api/rag/documents/${encodeURIComponent(id)}/indexes`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ strategy }), signal
  }),
  ragChunks: (id, offset = 0, signal) => rawRequest(`/api/rag/indexes/${encodeURIComponent(id)}/chunks?limit=10&offset=${offset}`, { signal }),
  reports: () => rawRequest('/api/reports'),
  downloadReport: async name => {
    const response = await fetch('/api/reports/download?' + new URLSearchParams({ name }), {
      headers: authHeaders(), cache: 'no-store'
    })
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || 'Не удалось скачать отчёт.')
    }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url; link.download = name
    document.body.appendChild(link); link.click(); link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
  memory: agentId => request(`/${encodeURIComponent(agentId)}/memory`),
  putMemory: (agentId, id, body) => jsonRequest(`/${encodeURIComponent(agentId)}/memory/${encodeURIComponent(id)}`, 'PUT', body),
  deleteMemory: (agentId, id, version) => jsonRequest(`/${encodeURIComponent(agentId)}/memory/${encodeURIComponent(id)}`, 'DELETE', { version }),
  editTask: (agentId, chatId, body) => jsonRequest(chatPath(agentId, chatId) + '/task', 'PUT', body),
  advanceTask: (agentId, chatId, version) => jsonRequest(chatPath(agentId, chatId) + '/task/advance', 'POST', { version }),
  pauseTask: (agentId, chatId, version) => jsonRequest(chatPath(agentId, chatId) + '/task/pause', 'POST', { version }),
  resumeTask: (agentId, chatId, version) => jsonRequest(chatPath(agentId, chatId) + '/task/resume', 'POST', { version }),
  putInvariant: (agentId, chatId, id, body) => jsonRequest(chatPath(agentId, chatId)
    + `/invariants/${encodeURIComponent(id)}`, 'PUT', body),
  deleteInvariant: (agentId, chatId, id, version) => jsonRequest(chatPath(agentId, chatId)
    + `/invariants/${encodeURIComponent(id)}`, 'DELETE', { version }),
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
  chatHistory: (agentId, chatId) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + '/history'),
  chatRequestUsage: (agentId, chatId) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + '/usage'),
  chatSettings: (agentId, chatId) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + '/settings'),
  saveChatSettings: (agentId, chatId, body) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + '/settings', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  }),
  pendingRequest: (agentId, chatId) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + '/pending'),
  requestDetails: (agentId, chatId, id) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + `/requests/${id}`),
  approveTool: (agentId, chatId, id, body, handlers) => stream(chatPath(agentId, chatId) + `/requests/${id}/approval/stream`, body, handlers, '/api/harness/agents'),
  cancelRequest: (agentId, chatId, id) => rawRequest('/api/harness/agents' + chatPath(agentId, chatId) + `/requests/${id}`, { method: 'DELETE' }),
  sendStream: (agentId, chatId, message, handlers) =>
    stream(chatPath(agentId, chatId) + '/messages/stream', message, handlers, '/api/harness/agents')
}
function jsonRequest(path, method, body) {
  return request(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}
