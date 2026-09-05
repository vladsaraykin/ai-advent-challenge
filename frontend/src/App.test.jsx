import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

const models = [
  { model: 'gpt-4o-mini', displayName: 'GPT-4o Mini', inputUsdPerMillion: 0.15, outputUsdPerMillion: 0.6 },
  { model: 'gpt-5-mini', displayName: 'GPT-5 Mini', inputUsdPerMillion: 0.25, outputUsdPerMillion: 2 },
  { model: 'gpt-5.6-sol', displayName: 'GPT-5.6 Sol', inputUsdPerMillion: 4, outputUsdPerMillion: 20 }
]
const tiers = models.map((model, index) => ({ ...model, level: ['WEAK', 'MEDIUM', 'STRONG'][index] }))
const response = { durationMs: 1250, totalUsage: { promptTokens: 30, completionTokens: 60, totalTokens: 90 }, estimatedTotalCostUsd: 0.0005,
  results: tiers.map((tier, index) => ({ tier, status: 'SUCCESS', answer: `## Ответ ${index + 1}\n\n**текст**`, durationMs: 300 + index,
    usage: { promptTokens: 10, completionTokens: 20, totalTokens: 30 }, estimatedCostUsd: 0.0001 + index * 0.0001 })) }
const renderApp = props => render(<App getModels={() => Promise.resolve(models)} {...props} />)

describe('model laboratory', () => {
  it('loads selectable models and explains manual quality review', async () => {
    renderApp()
    expect(await screen.findAllByText(/GPT-4o Mini/)).not.toHaveLength(0)
    expect(screen.getAllByText(/GPT-5 Mini/)).not.toHaveLength(0)
    expect(screen.getAllByText(/GPT-5.6 Sol/)).not.toHaveLength(0)
    expect(screen.getByLabelText('Средняя модель')).toHaveValue('gpt-5-mini')
    expect(screen.getByRole('heading', { name: 'Качество оцениваете вы' })).toBeInTheDocument()
  })

  it('disables submission while loading and sends only prompt and common limit', async () => {
    let resolve
    const submit = vi.fn(() => new Promise(done => { resolve = done }))
    renderApp({ submit })
    await screen.findAllByText(/GPT-4o Mini/)
    await userEvent.type(screen.getByLabelText('Введите ваш запрос'), 'Тестовая задача')
    await userEvent.click(screen.getByRole('button', { name: /Сравнить модели/ }))
    expect(screen.getByRole('button')).toBeDisabled()
    expect(screen.getAllByRole('status').some(item => item.textContent.includes('Запрос отправлен трём моделям'))).toBe(true)
    resolve(response)
    await waitFor(() => expect(screen.getAllByRole('heading', { name: /Ответ/ })).toHaveLength(3))
    expect(submit).toHaveBeenCalledWith({ prompt: 'Тестовая задача', maxTokens: 1000,
      models: ['gpt-4o-mini', 'gpt-5-mini', 'gpt-5.6-sol'] })
    expect(screen.getByText('Всего токенов: 90')).toBeInTheDocument()
    expect(screen.getByText('Итого: $0.000500')).toBeInTheDocument()
  })

  it('retains prompt after a request failure', async () => {
    renderApp({ submit: () => Promise.reject(new Error('Сервис недоступен')) })
    await screen.findAllByText(/GPT-4o Mini/)
    const field = screen.getByLabelText('Введите ваш запрос')
    await userEvent.type(field, 'Не потеряй меня')
    await userEvent.click(screen.getByRole('button', { name: /Сравнить модели/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Сервис недоступен')
    expect(field).toHaveValue('Не потеряй меня')
  })

  it('keeps successful cards on partial failure and strips raw HTML', async () => {
    const partial = { ...response, results: [
      { ...response.results[0], answer: '<script>alert(1)</script>\n\n**безопасно**' },
      { tier: models[1], status: 'ERROR', error: 'Безопасная ошибка', durationMs: 20, usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 }, estimatedCostUsd: 0 },
      response.results[2]
    ] }
    const { container } = renderApp({ submit: () => Promise.resolve(partial) })
    await screen.findAllByText(/GPT-4o Mini/)
    await userEvent.type(screen.getByLabelText('Введите ваш запрос'), 'Частичный сбой')
    await userEvent.click(screen.getByRole('button', { name: /Сравнить модели/ }))
    expect(await screen.findByText('Безопасная ошибка')).toBeInTheDocument()
    expect(container.querySelector('script')).not.toBeInTheDocument()
    expect(screen.getByText('безопасно')).toBeInTheDocument()
    expect(screen.getAllByText('Готово')).toHaveLength(2)
  })
})
