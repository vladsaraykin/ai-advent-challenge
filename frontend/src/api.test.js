import { afterEach, describe, expect, it, vi } from 'vitest'
import { agentApi } from './api'

const encodedStream = chunks => new ReadableStream({
  start(controller) {
    const encoder = new TextEncoder()
    chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk)))
    controller.close()
  }
})

afterEach(() => { vi.unstubAllGlobals(); agentApi.clearCredentials() })

describe('SSE chat API', () => {
  it('sends HTTP Basic credentials to protected endpoints', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ username: 'alice' }), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetch)
    agentApi.setCredentials('alice', 'secret-123')
    await expect(agentApi.me()).resolves.toEqual({ username: 'alice' })
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe(`Basic ${btoa('alice:secret-123')}`)
  })

  it('loads MCP servers from the authenticated backend endpoint', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: 'filesystem', tools: [] }]), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetch)
    agentApi.setCredentials('alice', 'secret-123')

    await expect(agentApi.mcpServers()).resolves.toEqual([{ id: 'filesystem', tools: [] }])
    expect(fetch.mock.calls[0][0]).toBe('/api/mcp/servers')
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe(`Basic ${btoa('alice:secret-123')}`)
  })

  it('calls task pause and resume commands with optimistic versions', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'one' }), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetch)
    await agentApi.pauseTask('architect', 'one', 4)
    await agentApi.resumeTask('architect', 'one', 5)
    expect(fetch.mock.calls[0][0]).toBe('/api/agents/architect/chats/one/task/pause')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST', body: '{"version":4}' })
    expect(fetch.mock.calls[1][0]).toBe('/api/agents/architect/chats/one/task/resume')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', body: '{"version":5}' })
  })

  it('parses events split across network chunks and preserves text whitespace', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(encodedStream([
      'event: started\r\ndata: {"type":"STARTED"}\r\n\r\nevent: delta\r\nda',
      'ta: {"text":" строка\\n"}\r\n\r\nevent: completed\r\ndata: {"chat":{"id":"one"}}\r\n\r\n'
    ]), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })))
    const deltas = []
    let completed

    await agentApi.sendStream('architect', 'one', { messageId: 'id', content: 'text' }, {
      delta: event => deltas.push(event.text),
      completed: event => { completed = event.chat }
    })

    expect(deltas).toEqual([' строка\n'])
    expect(completed).toEqual({ id: 'one' })
  })

  it('surfaces a sanitized SSE error event', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(encodedStream([
      'event: error\ndata: {"message":"OpenAI недоступен"}\n\n'
    ]), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(agentApi.sendStream('architect', 'one', { messageId: 'id', content: 'text' }))
      .rejects.toThrow('OpenAI недоступен')
  })
})
