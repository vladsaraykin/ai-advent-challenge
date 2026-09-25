import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import ReportDownloads from './ReportDownloads'

it('downloads a real listed filename and displays download failures', async () => {
  const api = { reports: vi.fn().mockResolvedValue([{ name: 'отчёт.xlsx', sizeBytes: 1024 }]),
    downloadReport: vi.fn().mockRejectedValue(new Error('Файл удалён')) }
  render(<ReportDownloads api={api} refreshKey="one" />)
  await userEvent.click(screen.getByText(/Скачать отчёты/))
  await userEvent.click(await screen.findByRole('button', { name: 'Скачать отчёт.xlsx' }))
  expect(api.downloadReport).toHaveBeenCalledWith('отчёт.xlsx')
  expect(await screen.findByRole('alert')).toHaveTextContent('Файл удалён')
  await userEvent.click(screen.getByRole('button', { name: 'Обновить файлы' }))
  await waitFor(() => expect(api.reports).toHaveBeenCalledTimes(2))
})
