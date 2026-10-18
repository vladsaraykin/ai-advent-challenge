import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import MessageComposer from './MessageComposer'

function setup() {
  const onSubmit = vi.fn(event => event.preventDefault())
  const onChange = vi.fn()
  function ControlledComposer() {
    const [draft, setDraft] = useState('')
    return <MessageComposer draft={draft} onChange={value => { onChange(value); setDraft(value) }}
      onSubmit={onSubmit} pending={false} disabled={false} />
  }
  return { ...render(<ControlledComposer />), onChange, onSubmit }
}

it('shows and filters chat commands when the draft starts with slash, and inserts the selected command', async () => {
  const user = userEvent.setup()
  const { onChange, onSubmit } = setup()
  const input = screen.getByRole('textbox', { name: 'Ваше сообщение' })

  await user.type(input, '/')
  expect(screen.getByRole('listbox', { name: 'Команды чата' })).toBeInTheDocument()
  expect(screen.getByRole('option', { name: /\/help.*Задать вопрос о проекте/ })).toBeInTheDocument()
  await user.type(input, 'h')
  expect(screen.getByRole('option', { name: /\/help/ })).toBeInTheDocument()

  await user.click(screen.getByRole('option', { name: /\/help/ }))
  expect(onChange).toHaveBeenLastCalledWith('/help ')
  expect(onSubmit).not.toHaveBeenCalled()
})

it('supports keyboard selection and keeps ordinary message input free of the command menu', async () => {
  const user = userEvent.setup()
  const { onChange } = setup()
  const input = screen.getByRole('textbox', { name: 'Ваше сообщение' })

  await user.type(input, 'обычный текст')
  expect(screen.queryByRole('listbox', { name: 'Команды чата' })).not.toBeInTheDocument()
  await user.clear(input)
  await user.type(input, '/')
  await user.keyboard('{ArrowDown}{Enter}')
  expect(onChange).toHaveBeenLastCalledWith('/help ')
})

it('closes command suggestions on Escape', async () => {
  const user = userEvent.setup()
  setup()
  const input = screen.getByRole('textbox', { name: 'Ваше сообщение' })
  await user.type(input, '/')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('listbox', { name: 'Команды чата' })).not.toBeInTheDocument()
})

it('offers an explicit exit from file mode without losing the user draft', async () => {
  const user = userEvent.setup()
  const { onChange, onSubmit } = setup()
  await user.type(screen.getByRole('textbox', { name: 'Ваше сообщение' }), '/ch')
  await user.click(screen.getByRole('option', { name: /\/chat.*Завершить файловый режим/ }))
  expect(onChange).toHaveBeenLastCalledWith('/chat ')
  expect(onSubmit).not.toHaveBeenCalled()
})

it('offers live file operations by goal through /files', async () => {
  const user = userEvent.setup()
  const { onChange, onSubmit } = setup()
  await user.type(screen.getByRole('textbox', { name: 'Ваше сообщение' }), '/f')
  await user.click(screen.getByRole('option', { name: /\/files.*проанализировать файлы/i }))
  expect(onChange).toHaveBeenLastCalledWith('/files ')
  expect(onSubmit).not.toHaveBeenCalled()
})

it('opens model and supported reasoning choices through /model without submitting a chat message', async () => {
  const user = userEvent.setup()
  const onSubmit = vi.fn(event => event.preventDefault())
  const onModelSelection = vi.fn().mockResolvedValue(true)
  function Controlled() {
    const [draft, setDraft] = useState('/')
    return <MessageComposer draft={draft} onChange={setDraft} onSubmit={onSubmit} pending={false} disabled={false}
      defaultModel="gpt-4.1-mini" modelOptions={[
        { id: 'gpt-4.1-mini', label: 'GPT-4.1 mini', reasoningEfforts: [] },
        { id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol', reasoningEfforts: ['low', 'medium', 'high', 'xhigh', 'max'], defaultReasoningEffort: 'medium' }
      ]} onModelSelection={onModelSelection} />
  }
  render(<Controlled />)
  await user.click(screen.getByRole('option', { name: /\/model.*выбрать модель/i }))
  expect(screen.getByRole('region', { name: 'Выбор модели чата' })).toBeInTheDocument()
  await user.selectOptions(screen.getByRole('combobox', { name: 'Модель чата' }), 'gpt-5.6-sol')
  await user.selectOptions(screen.getByRole('combobox', { name: 'Уровень reasoning' }), 'xhigh')
  await user.click(screen.getByRole('button', { name: 'Применить' }))
  expect(onModelSelection).toHaveBeenCalledWith({ model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' })
  expect(onSubmit).not.toHaveBeenCalled()
})
