# Project development rules

## Product scope

- The application implements only the current AI Advent challenge task. Do not keep earlier challenge screens or backend feature code unless explicitly requested.
- Keep the OpenAI API key and provider access on the backend. Never expose credentials to React or log full prompts by default.
- Day 25 unifies agent chat, memory, MCP and RAG in one harness. Keep Chat / RAG / MCP tabs, authenticated profiles, the four context strategies, SSE and token/USD accounting. Quality comparisons remain manual.
- Day 27 adds per-chat OPENAI / LOCAL_MLX selection. Freeze the provider in each request snapshot; route all helper calls to it and never fall back to cloud. Local token usage comes from MLX, not an OpenAI tokenizer. Zero local USD means no API charge. Keep local MCP disabled unless explicitly configured after tool-call verification.
- Persist all user-owned application data in PostgreSQL: accounts/profiles, complete transcripts, task/long-term memory, chat settings, request usage, MCP approvals/results, document originals, chunks and embeddings. Do not import or automatically delete previous filesystem data. YAML configuration and external MCP reports are separate concerns.
- Normal chat is the default. RAG is a persisted per-chat switch; when enabled search all completed documents belonging to that user, selecting one eligible index per document. Never silently fall back to ungrounded generation or bypass reranker failures.
- RAG answers require server-validated source references and verbatim quotes. Withhold structured drafts until validation. Empty eligible context skips primary answer generation (helper calls can still occur). Quote provenance is not semantic entailment.
- Persist selected MCP servers per chat (up to eight). Reject ambiguous tool names and bound orchestration to 24 distinct calls. Every actual tool invocation requires explicit approval of its exact parameters; discovery requires no approval. Successful calls are cached across continuation; unknown write results must never be automatically repeated.

## Architecture

- Use Java 21 and Spring Boot for JSON REST endpoints and SSE response streaming, and React JavaScript with Vite for the UI.
- Separate HTTP DTOs/controllers, application orchestration, domain values, and the Spring AI/OpenAI adapter.
- Use immutable Java records for DTOs/domain results and constructor injection for components.
- Agent definitions, model IDs, system prompts and limits are server-owned YAML configuration, one file per agent.
- Context strategy defaults, window sizes, summary/facts prompts and generation limits are server-owned per-agent YAML configuration. Choose the strategy when creating a chat and persist it for that chat.
- Input, cached-input and output prices are server-owned YAML values and must not be trusted from the browser.
- An Agent encapsulates context construction, generation and response handling; controllers must not call the provider.
- Use Spring AI's MCP client for protocol negotiation, discovery and model tool callbacks. Expose tools only from the MCP servers selected for the primary answer; never attach tools to summary, extraction or guard calls. Each callback routes to its originating server. A local filesystem MCP process must be restricted to explicit roots and remain separate from persisted chat storage concerns.
- Encapsulate retention and memory preparation in ContextStrategy implementations. Build provider context only from the selected chat or branch.
- Summary retains recent messages plus cumulative summary; Sliding Window sends only recent complete turns but preserves the full conversation in storage and UI; Sticky Facts preserves the full conversation, updates key-value memory from recent complete turns, and sends facts plus those turns; Branching retains checkpoint history plus its own continuation.
- Keep Sliding Window and Sticky Facts provider contexts separate from persisted history. Never save their truncated provider views or archive usage for messages that remain in full history.
- Window sizes count individual messages, not turns, and must be even. Retain complete user/assistant pairs. Preserve deduplication fingerprints and usage aggregates when removing text.
- Validate facts as a bounded string-to-string JSON object. A failed extraction must not overwrite memory or discard messages; retry must not duplicate a completed turn.
- Persist a checkpoint and both child branches atomically. Freeze the source dialogue after branching. Branch continuations are independent and inherited history is not charged twice in local branch statistics.
- Confirm chat deletion in UI, explicitly including descendant branches. Preserve siblings and checkpoint read-only state; reject deletion while any affected chat is generating a response.
- Treat conversation text as data during summarization. Preserve requirements, facts, decisions, constraints and open questions without inventing information.
- Persist completed turns and successful summary updates atomically. Failed generation or compression must preserve recoverable history and allow retry without duplicated turns.
- Preserve cumulative token and cost metrics when messages move into the summary archive, including the cost of summary calls.
- Explain each strategy's retention in UI. If its prepared context exceeds the configured character limit, return a clear error; this is not the model's exact token limit.
- Stream responses as typed SSE events. Do not persist partial assistant output when a stream fails before completion.
- Bound concurrency and shut executors down cleanly.
- Query rewrite resolves conversational references from recent history, summary and task memory. Final generation receives the original message; reranker receives the original message plus the resolved search context. Disable rewrite tools, validate completion, and account for its tokens/USD separately. Apply threshold, final top-K and whole-chunk budget before citation numbering; scores are not answer probabilities.
- The complete transcript archive is independent of the provider context, including Summary. Save completed turns, archive entries and successful task updates in one transaction. Never save a partial assistant reply. Pending MCP proposals and known usage must survive refresh/restart.
- Freeze retrieval/settings in a request snapshot before tool confirmation. Resume the same request ID; do not execute callbacks from the legacy chat API without approval context. Tools may execute only in Execution/Validation, never while paused, Planning or Done.
- A PostgreSQL request ledger keeps known paid calls, including failed validation/retries. Mark usage incomplete when interrupted tool rounds do not return metrics; do not present known subtotal as the full provider bill.

## Memory layers

- Memory layers and lifecycle are enabled for all bundled agents through shared server-owned `agent-defaults.yaml`, with per-agent overrides. `Chat.messages`/summary is short-term; task-local working memory is a separate JSONB column; long-term memory is a separate table isolated by owner, agent and GLOBAL/PROJECT scope.
- Task memory includes goal, clarified requirements, constraints, decisions, unresolved questions and a bounded key/value glossary (`terms`). The user may inspect/edit each field. The model proposes data but cannot confirm stages.
- One chat is one task. New chats must not inherit working memory; branches copy task state independently and reset inherited extraction usage. Never import another project's entries without an explicit matching project key.
- LLM extraction only proposes task data and memory candidates; validate strict JSON and source quotes. Never let the model advance stages or persist long-term entries. Long-term writes require explicit UI confirmation/manual editing.
- Confirm stages through deterministic transitions; requirement changes invalidate confirmation. Persist task updates with completed turns, and retain prior state on failed extraction or generation. Avoid duplicate facts extraction for layered agents.
- After generating an answer for a nonempty task, extract its unanswered clarification questions separately; validate source quotes and merge with unresolved questions before atomic turn persistence. This step must not alter decisions or long-term memory. Announce `syncing_questions`, count the extra call, and preserve prior state on failure. The next user-message extraction resolves answered/cancelled questions.
- Long-term entries use optimistic versions and atomic writes. Keep resolved proposal IDs so deleting an accepted entry does not resurrect the old candidate. Deleting a chat does not delete explicitly saved long-term memory; explain this in UI.
- HTTP Basic authentication identifies the active profile. Store BCrypt password hashes, never plaintext passwords, and isolate chats and long-term memory by authenticated username.
- User profiles contain display name, response style, response format and explicit constraints. Keep them separate from chat/task memory and add the active profile to every primary answer prompt; explicit current-request instructions override profile defaults.
- Profile/settings/long-term updates use optimistic versions and database transactions. Back up PostgreSQL together with external configuration. Only a single backend instance is currently supported: generation locks and restart recovery are not a distributed lease protocol.

## Frontend

- Keep components focused and accessible; all controls need labels and loading/error state must be announced.
- Use the approved light visual concept: top navigation, dialogue sidebar, primary conversation, collapsible task inspector. Keep indexing/discovery in their own tabs; no separate legacy RAG answer experiment screen in the production UI.
- Show the submitted prompt immediately and clear the composer immediately; retain/restore a retry draft on failure. Keep pending confirmation visible until resolved, including after reload.
- Render streamed deltas incrementally and announce generation and summarization states without blocking the rest of the UI.
- Render model Markdown without enabling raw HTML.
- Show per-response model, duration, input/output/total tokens and USD cost, plus cumulative chat and context-summary metrics.
- Do not commit `node_modules`, frontend build output, or generated Maven assets.

## Build and tests

- Maven must produce one runnable JAR containing the compiled React application.
- Keep `package-lock.json` committed and use `npm ci` for reproducible frontend builds.
- Backend tests cover YAML, pricing and compression validation; token/cost calculation; extensible agents; compressed-context role/order; cross-chat and cross-agent isolation; restart persistence; idempotent retry (including archived message IDs); bounded concurrency; SSE events; summary failures; and provider failures.
- Frontend tests cover agent/chat/branch switching, strategy selection, empty/loading/streaming/summarizing/updating-facts/success/error states, retry drafts, safe Markdown, context-memory display and token/cost metric rendering.
- MCP tests cover successful initialization metadata, paginated tool discovery, sanitized discovery failures, exact selected-server resolution, opt-in request forwarding and the authenticated MCP tab.
- Test all four strategies with the same multi-turn fixture, facts replacement/deletion/failure, window eviction and retry, concurrent branch writes, checkpoint persistence and cross-agent isolation. Mock the provider for reproducible tests.
- Run focused tests during development, then `npm test`, `npm run build`, `mvn test`, and `mvn package` for broad changes.

## Safety and operations

- Return sanitized provider errors; never expose raw upstream response bodies.
- Never log full user messages, generated answers, system prompts, summaries, API keys or raw provider bodies by default. Correlate calls with request/chat IDs and log only operational metadata.
- Preserve deployment proxy settings and existing server-side user data during migrations.
- Preserve external agent YAML files, environment/proxy configuration and PostgreSQL during deployment; never reset user data automatically. External agent configuration replaces the bundled agent set and receives shared defaults unless explicitly overridden.
- Keep production SSE proxy buffering disabled and keep the Spring Boot listener on loopback behind Nginx.
- Do not add CORS for the production same-origin deployment.
