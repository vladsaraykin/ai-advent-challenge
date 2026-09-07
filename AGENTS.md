# Project development rules

## Product scope

- The application implements only the current AI Advent challenge task. Do not keep earlier challenge screens or backend feature code unless explicitly requested.
- Keep the OpenAI API key and provider access on the backend. Never expose credentials to React or log full prompts by default.
- Day 6 provides independently configured agents and multiple isolated, persistent chats per agent.

## Architecture

- Use Java 21 and Spring Boot for a JSON REST API, and React JavaScript with Vite for the UI.
- Separate HTTP DTOs/controllers, application orchestration, domain values, and the Spring AI/OpenAI adapter.
- Use immutable Java records for DTOs/domain results and constructor injection for components.
- Agent definitions, model IDs, system prompts and limits are server-owned YAML configuration, one file per agent.
- An Agent encapsulates context construction, generation and response handling; controllers must not call the provider.
- Send only the selected chat's history and its agent's system prompt to the provider.
- Persist completed turns atomically. Failed calls must preserve earlier messages and allow retry without duplicated turns.
- Do not silently trim history; report the configured context limit explicitly.
- Bound concurrency and shut executors down cleanly.

## Frontend

- Keep components focused and accessible; all controls need labels and loading/error state must be announced.
- Use responsive layouts and retain the user's prompt after submission.
- Render model Markdown without enabling raw HTML.
- Do not commit `node_modules`, frontend build output, or generated Maven assets.

## Build and tests

- Maven must produce one runnable JAR containing the compiled React application.
- Keep `package-lock.json` committed and use `npm ci` for reproducible frontend builds.
- Backend tests cover YAML validation, extensible agents, context role/order, cross-chat and cross-agent isolation, restart persistence, retry, bounded concurrency and provider failures.
- Frontend tests cover agent/chat switching, empty/loading/success/error states, retry drafts, safe Markdown and metric rendering.
- Run focused tests during development, then `npm test`, `npm run build`, `mvn test`, and `mvn package` for broad changes.

## Safety and operations

- Return sanitized provider errors; never expose raw upstream response bodies.
- Preserve deployment proxy settings and existing server-side user data during migrations.
- Do not add CORS for the production same-origin deployment.
