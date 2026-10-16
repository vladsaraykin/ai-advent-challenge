# Справочник `application.yml`

Этот документ описывает каждый настроечный ключ из `src/main/resources/application.yml`,
включая переменную окружения (если она есть), default и фактическое назначение. Секреты
в YAML не хранятся: секретные значения поступают только из окружения. После изменения
настроек перезапустите backend. При изменении этого документа проектный RAG автоматически
переиндексирует его как отдельный документ.

## Spring Boot и HTTP

| Ключ | Env / default | Назначение |
|---|---|---|
| `spring.application.name` | `ai-advent-challenge` | Имя приложения для Spring и служебных метаданных. |
| `spring.servlet.multipart.max-file-size` | `20MB` | Максимальный размер одного загружаемого RAG-файла. |
| `spring.servlet.multipart.max-request-size` | `21MB` | Максимальный размер всего multipart-запроса, включая multipart overhead. |
| `app.streaming.timeout-seconds` | `CHAT_STREAM_TIMEOUT_SECONDS`, `900` | Предельное время асинхронного HTTP/SSE-ответа. Допустимый диапазон в коде: 30–3600 секунд. Это не timeout LLM-запроса. |

`spring.servlet.multipart.file-size-threshold` намеренно удалён: прежнее значение `0`
совпадало с default Spring Boot и не меняло поведение.

## OpenAI и модели

| Ключ | Env / default | Назначение |
|---|---|---|
| `spring.ai.openai.api-key` | `OPENAI_API_KEY`, `missing-api-key` | Ключ OpenAI для backend-клиента и health probe. Задайте настоящий ключ вне Git. При sentinel-default проверка health сообщает, что API не настроен. |
| `spring.ai.openai.base-url` | `OPENAI_BASE_URL`, `https://api.openai.com/v1` | Базовый URL OpenAI-совместимого API; используется Spring AI и проверкой доступности `/models`. |
| `app.openai.proxy.enabled` | `OPENAI_PROXY_ENABLED`, `false` | Включает исходящий HTTP proxy для OpenAI-клиента. |
| `app.openai.proxy.host` | `OPENAI_PROXY_HOST`, `127.0.0.1` | Адрес HTTP proxy; проверяется только при включённом proxy. |
| `app.openai.proxy.port` | `OPENAI_PROXY_PORT`, `10809` | Порт HTTP proxy, диапазон 1–65535; проверяется только при включённом proxy. |
| `app.agents.location` | `AGENT_CONFIG_LOCATION`, `classpath:agents/*.yaml` | Шаблон файлов конфигурации агентов. Если задан внешний каталог, он заменяет bundled-набор; сохраняйте эти файлы отдельно от Git и резервных копий базы. |
| `app.rag.answer.model` | `RAG_ANSWER_MODEL`, `gpt-6.1-sol` | API ID модели для ответов RAG и query rewrite в экспериментальном RAG endpoint. |
| `app.rag.answer.max-completion-tokens` | `RAG_ANSWER_MAX_TOKENS`, `4096` | Лимит генерации ответа RAG. Query rewrite использует отдельный внутренний лимит 1024. |
| `app.rag.answer.timeout-seconds` | `RAG_ANSWER_TIMEOUT_SECONDS`, `180` | Timeout вызова RAG-модели; допустимо 5–240 секунд. |
| `app.rag.answer.input-per-million-usd` | `RAG_INPUT_PRICE`, `2.00` | Серверная цена миллиона входных токенов для расчёта RAG-стоимости. Не является тарифом, переданным браузером. |
| `app.rag.answer.cached-input-per-million-usd` | `RAG_CACHED_INPUT_PRICE`, `0.10` | Серверная цена миллиона кешированных входных токенов для RAG-расчёта. |
| `app.rag.answer.output-per-million-usd` | `RAG_OUTPUT_PRICE`, `10.00` | Серверная цена миллиона выходных токенов для RAG-расчёта. |

Общая настройка `spring.ai.openai.chat.options.model` удалена: чат передаёт выбранную
модель явно. Модели, список доступных reasoning effort и цены на токены задаются один
раз в `agent-defaults.yaml`; `default-model` определяет модель для новых чатов.
`agents/*.yaml` содержит роль, промпт и ограничения агента, но не выбирает модель.
Сохранённый выбор `/model` хранится в настройках чата и имеет приоритет над default.
Экспериментальный RAG endpoint отдельно использует `app.rag.answer.model`.

## MCP

Включены три STDIO-подключения: `filesystem`, `project-git`, `excel`. Необязательное
старое HTTP-подключение `expenses` удалено. MCP-инструменты не прикрепляются автоматически:
основной ответ получает только выбранные серверы, а фактический вызов проходит через
проверку подтверждения параметров.

| Ключ | Env / default | Назначение |
|---|---|---|
| `spring.ai.mcp.client.enabled` | `MCP_ENABLED`, `true` | Включает создание MCP-клиентов. Изменение требует перезапуска backend. |
| `spring.ai.mcp.client.initialized` | `true` | Инициализирует MCP handshake при создании клиентов. Если сервер не отвечает при старте, автонастройка Spring AI может прервать запуск приложения. |
| `spring.ai.mcp.client.name` | `ai-advent-mcp-client` | Клиентское имя в MCP handshake; не ID соединения в UI. |
| `spring.ai.mcp.client.version` | `1.0.0` | Версия клиента, передаваемая MCP-серверам. |
| `spring.ai.mcp.client.type` | `SYNC` | Используется синхронный MCP API, совместимый с текущим tool orchestration. |
| `spring.ai.mcp.client.request-timeout` | `MCP_REQUEST_TIMEOUT`, `20s` | Timeout MCP-запроса. |
| `spring.ai.mcp.client.toolcallback.enabled` | `false` | Отключает автоматическое предоставление Spring callbacks модели. Приложение формирует callbacks само, только для выбранных серверов и с учётом обязательного approval. |
| `spring.ai.mcp.client.stdio.connections.filesystem.command` | `MCP_FILESYSTEM_COMMAND`, `node` | Команда запуска filesystem MCP. |
| `...filesystem.args[0]` | `MCP_FILESYSTEM_ENTRYPOINT`, `mcp/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js` | Путь к npm entrypoint filesystem MCP. |
| `...filesystem.args[1]` | `MCP_FILESYSTEM_ROOT`, `.` | Единственный разрешённый корень filesystem MCP. Ограничьте его минимально нужным абсолютным путём; не открывайте секреты, домашнюю директорию целиком или каталоги с DB backups. |
| `spring.ai.mcp.client.stdio.connections.project-git.command` | `MCP_PROJECT_GIT_COMMAND`, `node` | Команда запуска встроенного read-only MCP-сервера проекта. |
| `...project-git.args[0]` | `MCP_PROJECT_GIT_ENTRYPOINT`, `mcp/project-git/server.mjs` | Entry point встроенного MCP для текущей ветки и списка отслеживаемых файлов. |
| `...project-git.args[1]` | `MCP_PROJECT_GIT_ROOT`, `.` | Путь к checkout, в котором MCP выполняет только разрешённые read-only git-команды. |
| `spring.ai.mcp.client.stdio.connections.excel.command` | `MCP_EXCEL_COMMAND`, `uv` | Команда запуска существующего Excel MCP. |
| `...excel.args[0..1]` | `--directory`, затем `MCP_EXCEL_DIRECTORY` (default `../excel-mcp`) | Рабочий каталог проекта Excel MCP для `uv`. |
| `...excel.args[2..5]` | `run --frozen --no-sync excel-mcp` | Фиксированные аргументы запуска; не загружают/не обновляют зависимости при старте. |

ID соединений для UI/tool routing берётся из имени MCP-сервера, а не общего `client.name`.
Неопределённый ответ MCP-инструмента после прерывания не повторяется автоматически.

## RAG и индексация

### Проектная документация

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.rag.project-docs.enabled` | `RAG_PROJECT_DOCS_ENABLED`, `true` | Включает синхронизацию проектной документации. Также требуется `app.rag.enabled=true`. |
| `app.rag.project-docs.include-code` | `RAG_PROJECT_CODE_ENABLED`, `true` | Добавляет allowlist Java/React/SQL/project-git исходников в проектный RAG для ревью. Синхронизатор заменяет изменённые и удаляет исчезнувшие управляемые файлы. Выключение флага само по себе не удаляет уже загруженный код. |
| `app.rag.project-docs.root` | `RAG_PROJECT_DOCS_ROOT`, `.` | Checkout root для README, project/docs и разрешённых исходников. Для IDEA задайте абсолютный путь репозитория. |
| `app.rag.project-docs.owner` | `RAG_PROJECT_DOCS_OWNER`, `vlad` | Профиль-владелец управляемых документов в RAG. Этот профиль должен существовать в PostgreSQL. |
| `app.rag.project-docs.initial-delay-ms` | `RAG_PROJECT_DOCS_INITIAL_DELAY_MS`, `10000` | Начальная задержка scheduler после регистрации. Первый проход уже запускается сразу после `ApplicationReadyEvent`; этот delay управляет последующим scheduled проходом, а не первым стартовым sync. |
| `app.rag.project-docs.interval-ms` | `RAG_PROJECT_DOCS_INTERVAL_MS`, `60000` | Fixed delay между плановыми проходами синхронизации. |

### AI-ревью PR

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.review.enabled` | `AI_REVIEW_ENABLED`, `true` | Включает аутентифицированные POST/GET `/api/reviews`; требует `RAG_ENABLED=true`. |
| `app.review.repository` | `AI_REVIEW_REPOSITORY`, `vladsaraykin/ai-advent-challenge` | Единственный разрешённый репозиторий PR. Не URL и не путь checkout, формат owner/repository. |
| `app.review.agent-location` | `AI_REVIEW_AGENT_LOCATION`, `classpath:review-agents/*.yaml` | YAML отдельного pipeline-агента `code-reviewer`; наследует agent-defaults.yaml, не влияет на список агентов обычного чата. |

Prompt и лимиты ревью — `review-agents/code-reviewer.yaml`, модель/reasoning/цены — общие
server-owned defaults. GitHub Actions secrets `AI_REVIEW_API_URL`, `AI_REVIEW_USERNAME`,
`AI_REVIEW_PASSWORD` и variable `AI_REVIEW_RUNNER` описаны в [code-review.md](code-review.md);
это настройки workflow, не параметры application.yml. OpenAI-ключ остаётся на backend.

### Включение RAG и параметры индекса

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.rag.enabled` | `RAG_ENABLED`, `true` | Включает RAG API/answer pipeline и project documentation sync. PostgreSQL-подключение остаётся обязательным для хранения чатов и при выключенном RAG. |
| `app.rag.chunking.max-estimated-tokens` | `RAG_CHUNK_MAX_ESTIMATED_TOKENS`, `384` | Размер чанка для нарезки; это приблизительный бюджет токенов, не лимит модели. Допустимо 32–1024. |
| `app.rag.chunking.overlap-estimated-tokens` | `RAG_CHUNK_OVERLAP_ESTIMATED_TOKENS`, `64` | Перекрытие соседних fixed-size чанков; допустимо от 0 до половины max size. |
| `app.rag.answer.top-k` | `RAG_TOP_K`, `5` | Количество результатов для обычных RAG-режимов без расширенных настроек. Диапазон 1–20. |
| `app.rag.answer.max-context-tokens` | `RAG_CONTEXT_TOKENS`, `6000` | Максимальный приблизительный токенный бюджет выбранных source chunks. Это бюджет retrieval context, не точное окно модели. Диапазон 128–12000. |

### Retrieval/reranker

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.rag.reranker.base-url` | `RAG_RERANKER_URL`, `http://127.0.0.1:18090` | HTTP base URL cross-encoder reranker. Используется для `/rerank` и `/health`. |
| `app.rag.reranker.timeout-seconds` | `RAG_RERANKER_TIMEOUT_SECONDS`, `60` | Максимальный timeout одного reranker-запроса; допустимо 5–180 секунд. |
| `app.rag.reranker.candidate-k` | `RAG_CANDIDATE_K`, `20` | Максимальное число исходных vector-search кандидатов до reranking. |
| `app.rag.reranker.final-k` | `RAG_FINAL_K`, `5` | Максимальное число кандидатов после фильтрации/reranking. |
| `app.rag.reranker.threshold` | `RAG_RERANKER_THRESHOLD`, `0.2` | Нижний порог reranker score; низшие кандидаты отбрасываются. Score не является вероятностью правильного ответа. |

### PostgreSQL и Ollama embeddings

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.rag.database.url` | `RAG_DATABASE_URL`, `jdbc:postgresql://127.0.0.1:15432/ai_advent_rag` | PostgreSQL URL для документов, embeddings, чатов и связанного состояния. Не помещайте логин/пароль в URL; query и fragment запрещены валидацией. |
| `app.rag.database.username` | `RAG_DATABASE_USERNAME`, `ai_advent_rag` | Имя PostgreSQL-роли приложения. |
| `app.rag.database.password` | `RAG_DATABASE_PASSWORD`, пустое значение | Пароль PostgreSQL; приложение требует непустое значение. Секрет задавайте в окружении или secret manager. |
| `app.rag.database.maximum-pool-size` | `RAG_DATABASE_POOL_SIZE`, `50` | Максимум соединений HikariCP; диапазон 1–50. Учитывайте лимит PostgreSQL. |
| `app.rag.database.connection-timeout` | `5s` (fixed) | Timeout получения соединения Hikari; также используется как PostgreSQL driver connect timeout. Socket timeout JDBC фиксирован в 30 секунд. |
| `app.rag.ollama.base-url` | `RAG_OLLAMA_BASE_URL`, `http://127.0.0.1:11435` | HTTP endpoint Ollama только для embedding-векторов; это не chat LLM. |
| `app.rag.ollama.model` | `RAG_EMBEDDING_MODEL`, `embeddinggemma` | Название embedding-модели Ollama. Новая модель должна быть совместима с существующими индексами. |
| `app.rag.ollama.dimensions` | `768` (fixed) | Размер embedding-вектора. Не меняйте без миграции и полной переиндексации всех embeddings. |
| `app.rag.ollama.connect-timeout` | `5s` (fixed) | Timeout установления HTTP-соединения с Ollama. |
| `app.rag.ollama.request-timeout` | `120s` (fixed) | Timeout HTTP-запроса генерации embedding. |

## Сохранение файлов и агенты

| Ключ | Env / default | Назначение |
|---|---|---|
| `app.reports.directory` | `REPORTS_DIRECTORY`, `../share/reports` | Каталог для совместимого backend endpoint `/api/reports` (список/скачивание разрешённых файлов). UI отдельного скачивания отчётов не показывает; это legacy API setting, не использовать как RAG storage. |

## Логирование

| Ключ | Значение | Назначение |
|---|---|---|
| `logging.pattern.level` | `%5p [requestId=%X{requestId} chatId=%X{chatId}]` | Добавляет request/chat correlation IDs в уровень лог-сообщений, если они есть в MDC. Не включает текст prompt/ответа. |
| `logging.level.org.springframework.ai.chat.model.MessageAggregator` | `OFF` | Отключает логгер, который способен печатать исходное upstream-содержимое. |
| `logging.level.org.apache.pdfbox` | `OFF` | Уменьшает шум PDF parser при извлечении текста. |
| `logging.level.org.apache.poi` | `OFF` | Уменьшает шум Microsoft Office parser. |
| `logging.level.io.modelcontextprotocol.client.transport.StdioClientTransport` | `WARN` | Оставляет предупреждения stdio MCP transport, подавляя подробный диагностический шум. |

## Исключённые параметры

- `spring.servlet.multipart.file-size-threshold=0` удалён как дублирующий default Spring Boot.
- `spring.ai.openai.chat.options.model` / `OPENAI_MODEL` удалён: все вызовы передают модель
  явно из конфигурации соответствующего агента или RAG.
- `spring.ai.mcp.client.streamable-http.connections.expenses` / `MCP_EXPENSES_*` удалён:
  старое необязательное подключение мешало запуску приложения, когда HTTP MCP не был
  запущен. Остальные MCP-серверы используют STDIO.
- Параметры Local MLX удалены вместе с реализацией локального chat provider. Ollama
  embeddings для RAG остаются отдельной интеграцией.

## Как документ попадает в RAG

`ProjectDocumentationSyncService` при старте и по расписанию индексирует все Markdown-файлы
из `project/docs`. Поэтому этот справочник автоматически появится/обновится в профиле,
указанном `RAG_PROJECT_DOCS_OWNER`, если backend запущен с корректным
`RAG_PROJECT_DOCS_ROOT`. Проверить можно по логам `project_docs_sync_started` и
`project_docs_sync_queued`, затем по статусу индексации в RAG UI. См. также
[rag-maintenance.md](rag-maintenance.md).
