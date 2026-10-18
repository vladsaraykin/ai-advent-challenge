# Разработка и конфигурация

## Требования

- Java 21+
- Maven
- Node.js 22+
- PostgreSQL 16 с расширением pgvector
- Доступ к OpenAI API для облачного чата
- При включённом RAG: Ollama embedding endpoint и HTTP reranker

## Запуск

Задайте конфигурацию через переменные окружения — значения с секретами не помещайте
в Git и не передавайте в браузер:

```bash
export OPENAI_API_KEY="..."
export RAG_DATABASE_URL="jdbc:postgresql://127.0.0.1:15432/ai_advent_rag"
export RAG_DATABASE_USERNAME="ai_advent_rag"
read -s RAG_DATABASE_PASSWORD
export RAG_DATABASE_PASSWORD
export RAG_DATABASE_POOL_SIZE=50
export RAG_ENABLED=true
export RAG_OLLAMA_BASE_URL="http://127.0.0.1:11435"
export RAG_RERANKER_URL="http://127.0.0.1:18090"
export MCP_ENABLED=true
export MCP_PROJECT_GIT_ROOT="/Users/vsaraikin/IdeaProjects/ai-advent-challenge"
export RAG_PROJECT_DOCS_ROOT="/Users/vsaraikin/IdeaProjects/ai-advent-challenge"
mvn spring-boot:run
```

Порты `15432` и `11435` — пример локальных SSH-forward адресов, а не гарантированный
режим работы. Используйте реальные адреса вашей сети и не публикуйте БД/ Ollama в
Интернет.

UI в отдельном терминале:

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

Откройте `http://localhost:5173`; backend по умолчанию слушает `8080`.
После изменения Java-конфигурации или backend-кода перезапустите backend; после
изменения UI перезапустите Vite, если HMR не применил обновление.

## Сборка и тесты

```bash
cd frontend && npm ci && npm test && npm run build
cd ..
mvn test
mvn package
```

Maven собирает фронтенд и включает его ресурсы в runnable JAR. PostgreSQL integration
tests должны запускаться только против выделенной тестовой базы. См. команды
`RAG_TEST_DATABASE_*` в основном README.

### PostgreSQL-тесты через Docker

```bash
docker pull pgvector/pgvector:0.8.6-pg16-trixie
node automation/postgres-tests.mjs
# При разработке — только связанные integration-тесты:
node automation/postgres-tests.mjs -Dtest=ProjectWorkspacePostgresTest,McpPermissionsPostgresTest
```

Скрипт создаёт собственный Docker-контейнер PostgreSQL 16/pgvector с одноразовыми
данными в tmpfs, случайным паролем и случайным портом только на 127.0.0.1. Создаются
отдельные свежие базы rag_test, workspace_test, permissions_test и review_test;
URL тестов всегда формируются скриптом, рабочие адреса из окружения не используются.
После тестов контейнер останавливается и удаляется (`--rm`), существующие контейнеры,
volumes и PostgreSQL на хосте не затрагиваются. Нужен запущенный Docker daemon.
Внешние Ollama/provider opt-in переменные очищаются: эти проверки не расходуют API.
MCP/workspace suites отказываются работать с уже существующей схемой rag.
Локально установленные initdb/pg_ctl для этих тестов не нужны.

## Конфигурационные точки

- `src/main/resources/application.yml` — адреса зависимостей, таймауты и пределы;
  полный перечень с назначением и defaults — в [application-configuration.md](application-configuration.md).
- `src/main/resources/agents/*.yaml` и `agent-defaults.yaml` — агенты, prompt,
  стратегия контекста и серверные ограничения.
- `OPENAI_API_KEY`, `OPENAI_BASE_URL` — облачный LLM. Модели ответов задаются
  в серверных `agents/*.yaml` и `RAG_ANSWER_MODEL`, а не общим `OPENAI_MODEL`.
- `RAG_DATABASE_URL`, `RAG_DATABASE_USERNAME`, `RAG_DATABASE_PASSWORD` — основная
  PostgreSQL база (обязательна и при `RAG_ENABLED=false`).
- `RAG_OLLAMA_BASE_URL`, `RAG_EMBEDDING_MODEL` — embedding API; сейчас модель по
  умолчанию `embeddinggemma`, размер вектора 768.
- `RAG_RERANKER_URL`, `RAG_CANDIDATE_K`, `RAG_FINAL_K`,
  `RAG_RERANKER_THRESHOLD` — reranker и retrieval limits.
- `RAG_PROJECT_DOCS_ROOT`, `RAG_PROJECT_DOCS_OWNER`, `RAG_PROJECT_DOCS_INTERVAL_MS` —
  корень checkout, профиль-владелец и период синхронизации README/Markdown в RAG.
- `MCP_ENABLED` и `MCP_*` — подключение MCP-серверов и их рабочие каталоги.
- `MCP_PROJECT_GIT_ROOT` — абсолютный путь к checkout, который читает сервер
  `project-git`; при запуске приложения из корня репозитория значение по умолчанию `.`.

Для команды `/help` выберите `project-git` в свойствах чата. При `MCP_ENABLED=false`
MCP-вкладка показывает настроенные подключения как недоступные и указывает, как
включить клиент. При сбое синхронизации документации backend пишет в лог её root,
профиль-владелец и класс первопричины; проверьте, что root указывает на checkout,
содержащий `README.md` и `project/docs`.

Полный перечень фактических зависимостей и последние известные адреса — в
[infrastructure.md](infrastructure.md). Внешняя конфигурация агентов может заменить
bundled-каталог; не перезаписывайте её при развёртывании.
