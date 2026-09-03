# Project development rules

## Product scope

- The application implements only the current AI Advent challenge task. Do not keep earlier challenge screens or backend feature code unless explicitly requested.
- Keep the OpenAI API key and provider access on the backend. Never expose credentials to React or log full prompts by default.
- The Day 4 experiment changes only `temperature`; prompt, model, token limit, and all other sampling settings must remain identical across calls.

## Architecture

- Use Java 21 and Spring Boot for a JSON REST API, and React JavaScript with Vite for the UI.
- Separate HTTP DTOs/controllers, application orchestration, domain values, and the Spring AI/OpenAI adapter.
- Use immutable Java records for DTOs/domain results and constructor injection for components.
- Experimental temperatures are server-owned constants. Preserve result order independently of completion order.
- Treat partial provider failures as first-class results; do not discard successful generations.
- Bound concurrency and shut executors down cleanly.

## Frontend

- Keep components focused and accessible; all controls need labels and loading/error state must be announced.
- Use responsive layouts and retain the user's prompt after submission.
- Render model Markdown without enabling raw HTML.
- Do not commit `node_modules`, frontend build output, or generated Maven assets.

## Build and tests

- Maven must produce one runnable JAR containing the compiled React application.
- Keep `package-lock.json` committed and use `npm ci` for reproducible frontend builds.
- Backend tests cover validation, exact temperatures, identical request parameters, bounded concurrency, stable ordering, token/time metadata, and partial/all failures.
- Frontend tests cover empty, loading, success, and failure states plus the temperature guide.
- Run focused tests during development, then `npm test`, `npm run build`, `mvn test`, and `mvn package` for broad changes.

## Safety and operations

- Return sanitized provider errors; never expose raw upstream response bodies.
- Preserve deployment proxy settings and existing server-side user data during migrations.
- Do not add CORS for the production same-origin deployment.
