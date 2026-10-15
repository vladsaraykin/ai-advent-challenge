---
name: react-js
description: Build, review, and maintain React JavaScript frontends, including components, state, forms, API integration, accessibility, and frontend tests. Use for React UI work; do not use for backend-only tasks.
---

# React JavaScript

Inspect the existing package scripts, dependencies, routing, component structure, and styling conventions before changing the frontend. Keep the current framework and JavaScript/TypeScript choice; do not migrate tooling as part of an unrelated feature.

## Components and state

- Keep components focused on one UI responsibility. Extract shared UI only when it is genuinely reused; keep feature-specific components close to their feature.
- Use function components and hooks. Keep derived values derived instead of duplicating them in state, and place state at the lowest common owner that needs it.
- Represent loading, empty, success, and error states explicitly. Avoid effects for values that can be computed during render or handled in event callbacks.
- Use stable domain IDs for list keys and controlled inputs for editable forms.

## API and financial data

- Put HTTP calls behind a small API module or existing data layer; keep transport details out of presentational components.
- Treat server responses as the source of truth. Show clear errors and avoid claiming a financial change succeeded until the server confirms it.
- Keep money in the API's decimal-string or integer-minor-unit representation. Do not use floating-point arithmetic for totals; format values with `Intl.NumberFormat` for display.
- Do not store credentials or sensitive financial data in browser storage unless the established application design requires it and protects it appropriately.

## Quality and accessibility

- Use semantic HTML, labels for inputs, keyboard-operable controls, visible focus, and accessible names for icon-only buttons.
- Follow the repository's lint, formatter, test, and CSS conventions. Add or update focused component and interaction tests when a test setup exists.
- Keep responsive behavior intentional and avoid fixed-width layouts that overflow on narrow screens.
- Before finishing, run the relevant frontend checks available in package scripts and summarize any checks that could not be run.
