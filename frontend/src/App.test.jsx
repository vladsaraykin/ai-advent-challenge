import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

const response = { durationMs: 1100, results: [0, .7, 1.2].map(temperature => ({
  temperature, status: 'SUCCESS', answer: `## Ответ ${temperature}\n\n**текст**`, durationMs: 300,
  usage: { promptTokens: 4, completionTokens: 6, totalTokens: 10 }
})) }
const config = { models: ['gpt-4.1-mini'], temperatures: [0, .7, 1.2] }
const renderApp = props => render(<App loadConfig={() => Promise.resolve(config)} {...props} />)

describe('temperature lab', () => {
  it('shows empty state and the complete temperature guide', () => {
    renderApp()
    expect(screen.queryByText('Три версии ответа')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Памятка по temperature' })).toBeInTheDocument()
    expect(screen.getByText('1,5–2,0')).toBeInTheDocument()
  })

  it('announces loading, disables submit and renders three markdown results', async () => {
    let resolve
    const submit = vi.fn(() => new Promise(done => { resolve = done }))
    renderApp({ submit })
    await userEvent.type(screen.getByLabelText('Запрос'), 'Тестовая задача')
    await userEvent.click(screen.getByRole('button', { name: /Запустить эксперимент/ }))
    expect(screen.getByRole('button')).toBeDisabled()
    expect(screen.getAllByRole('status').some(item => item.textContent.includes('Выполняем три запроса'))).toBe(true)
    resolve(response)
    await waitFor(() => expect(screen.getAllByRole('heading', { name: /Ответ/ })).toHaveLength(3))
    expect(submit).toHaveBeenCalledWith({ prompt: 'Тестовая задача', model: 'gpt-4.1-mini', maxTokens: 1000 })
  })

  it('shows request failure and retains prompt', async () => {
    renderApp({ submit: () => Promise.reject(new Error('Сервис недоступен')) })
    const field = screen.getByLabelText('Запрос')
    await userEvent.type(field, 'Не потеряй меня')
    await userEvent.click(screen.getByRole('button', { name: /Запустить эксперимент/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Сервис недоступен')
    expect(field).toHaveValue('Не потеряй меня')
  })

  it('keeps successful answers on a partial failure and skips raw HTML', async () => {
    const partial = { ...response, results: [
      { ...response.results[0], answer: '<script>alert(1)</script>\n\n**безопасно**' },
      { temperature: .7, status: 'ERROR', error: 'Безопасная ошибка', durationMs: 20,
        usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 } },
      response.results[2]
    ] }
    const { container } = renderApp({ submit: () => Promise.resolve(partial) })
    await userEvent.type(screen.getByLabelText('Запрос'), 'Частичный сбой')
    await userEvent.click(screen.getByRole('button', { name: /Запустить эксперимент/ }))

    expect(await screen.findByText('Безопасная ошибка')).toBeInTheDocument()
    expect(container.querySelector('script')).not.toBeInTheDocument()
    expect(screen.getByText('безопасно')).toBeInTheDocument()
    expect(screen.getAllByText('Готово')).toHaveLength(2)
  })
})
