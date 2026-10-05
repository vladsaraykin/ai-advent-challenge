import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import RetrievalSettings from './RetrievalSettings'

const settings = { retrieval: { candidateK: 20, finalK: 5, threshold: .2 } }

it('saves explicit retrieval limits and threshold', async () => {
  const onSave = vi.fn()
  render(<RetrievalSettings settings={settings} onSave={onSave} />)
  await userEvent.click(screen.getByText('Параметры RAG-поиска'))
  const threshold = screen.getByLabelText('Порог реранкера')
  await userEvent.clear(threshold)
  await userEvent.type(threshold, '0.45')
  await userEvent.click(screen.getByRole('button', { name: 'Сохранить параметры поиска' }))
  await waitFor(() => expect(onSave).toHaveBeenCalledWith({ retrieval: { ...settings.retrieval, threshold: .45 } }))
})

it('rejects a final limit larger than the candidate pool', async () => {
  const onSave = vi.fn()
  render(<RetrievalSettings settings={settings} onSave={onSave} />)
  await userEvent.click(screen.getByText('Параметры RAG-поиска'))
  const candidates = screen.getByLabelText('Top-K до фильтрации')
  await userEvent.clear(candidates)
  await userEvent.type(candidates, '2')
  await userEvent.click(screen.getByRole('button', { name: 'Сохранить параметры поиска' }))
  expect(screen.getByRole('alert')).toHaveTextContent('не может превышать')
  expect(onSave).not.toHaveBeenCalled()
})
