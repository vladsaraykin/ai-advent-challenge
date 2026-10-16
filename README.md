# AI Advent Challenge — агент с RAG, памятью и MCP

Java 21 / Spring Boot и React-приложение для работы с диалоговыми агентами, памятью,
RAG и MCP. Документация для ассистента разработчика находится в [`project/docs`](project/docs/README.md).
Описание архитектуры и сценариев обновляется вместе с кодом; список известных
подключённых сервисов и баз данных — в [инфраструктурной заметке](project/docs/infrastructure.md).

Поддержка Markdown-проектной документации и её автоматической синхронизации описана
в [правилах актуализации RAG](project/docs/rag-maintenance.md).

День 32: GitHub Actions получает diff собственного PR, а агент `code-reviewer`
анализирует его с документацией и кодом из RAG. Проверенный отчёт с багами,
архитектурными замечаниями и рекомендациями появляется в комментарии PR.
Настройка secrets и запуск — [автоматическое ревью кода](project/docs/code-review.md).

## Интерфейс

- **Чат**: выбор агента, история и ветки, RAG-переключатель, SSE-статусы, ответы и источники.
  Enter отправляет; Shift+Enter добавляет строку. Запрос показывается сразу,
  composer очищается сразу, при ошибке восстанавливается черновик.
  Свойства чата находятся в панели «Память и настройки» после создания чата;
  суммарный расход отображается под полем ввода. Блок скачивания общих отчётов удалён из UI.
- **RAG**: PDF / DOC / DOCX загрузка через UI; README/Markdown проекта индексируются автоматически.
  Также есть fixed-size / structural chunking,
  индексация и статистика PostgreSQL. OCR для сканов не предусмотрен.
- **MCP**: подключения, статусы, инструменты, описания и входные схемы.
- **Память и настройки**: сворачиваемая правая панель с задачей, этапами, инвариантами,
  стратегией и MCP-серверами. Профиль пользователя — в верхней панели.

Обычный чат — режим по умолчанию. RAG и выбранные MCP-серверы сохраняются **по чатам**,
могут изменяться для следующих запросов. При включённом RAG ищем по всем готовым
документам текущего пользователя: один завершённый индекс на документ с подходящей
embedding-моделью; приоритет STRUCTURAL, затем последний завершённый индекс.
`README.md` и `project/docs/**/*.md` автоматически синхронизируются с RAG-профилем
`vlad` при запуске и каждую минуту. Новая версия заменяет старую только после
успешной индексации; остальные документы профиля синхронизатор не удаляет.
С дня 32 `RAG_PROJECT_CODE_ENABLED=true` также включает исходники Java, React, SQL
и project-git MCP в управляемый проектный индекс; секреты и build output не индексируются.

## RAG-запрос в диалоге

1. Проверяем владельца, checkpoint, паузу, повтор UUID и фиксируем настройки запроса.
2. LLM раскрывает «это / второй пункт» по недавней истории, summary и памяти задачи.
3. Ollama создаёт embedding; PostgreSQL ищет кандидатов; cross-encoder reranker
   отсекает их по порогу, top-K и бюджету целых чанков.
4. Агент проверяет этап/инварианты, извлекает рабочую память и строит prompt
   из профиля, выбранной истории, памяти и найденных источников.
5. Основная модель отвечает с доступом только к выбранным MCP-инструментам.
6. RAG-ответ должен содержать JSON с ссылками и дословными цитатами.
   Сервер проверяет завершение, источники и цитаты; сырой JSON не транслируется.
7. Завершённый ход, история и память сохраняются транзакционно.
   Неотвеченные уточняющие вопросы извлекаются отдельным вызовом.

Пустой итоговый контекст возвращает «Не знаю… Уточните вопрос…» без основного вызова
генерации; rewrite/память/guard при этом могут вызываться.
Ошибка embedding/reranker никогда не превращается в ответ без RAG.

Источники показывают файл, раздел, chunk_id и полный чанк. Цитаты проверяются на
происхождение, **не на семантическое доказательство каждого утверждения**.
Реранкер получает исходное сообщение плюс раскрытый разговорный контекст,
генерация — исходное сообщение. Оценки реранкера не являются вероятностями
правильности ответа.

## MCP и подтверждения

Можно выбрать до восьми серверов. Каждый фактический вызов, включая чтение,
требует отдельного разрешения точных аргументов: read-only аннотация не является
гарантией безопасности.

Карточка содержит сервер, инструмент и параметры.
Разрешить — продолжить исходный запрос; отклонить — сообщить модели отказ;
отменить запрос — прекратить продолжение. Следующий инструмент требует нового
разрешения. Перезагрузка восстанавливает ожидающую карточку.

Действия доступны только на Execution / Validation, не на Planning, Done или паузе.
План сначала утверждает человек. До 24 разных вызовов за запрос.
Совпадающие названия инструментов выбранных серверов отклоняются.

Успешные результаты сохраняются и используются при продолжении **без повторного
внешнего действия**. Неизвестный результат после сбоя требует ручной проверки;
автоматическое повторение запрещено. Продолжение вновь вызывает LLM:
другие аргументы означают новый вызов и новое разрешение.

Результат MCP — источник фактического вывода инструмента, не независимая проверка
правильности действия. Внешние отчёты остаются в разрешённой папке MCP и могут
скачиваться через существующий механизм отчётов.

## Память и состояния

Общие server-owned defaults: src/main/resources/agent-defaults.yaml;
YAML агента их переопределяет. У всех bundled-агентов включены memory layers,
инварианты и lifecycle.

- Краткосрочная память — история/summary конкретного чата или ветки.
- Рабочая — цель, требования, ограничения, решения, вопросы, glossary **terms**,
  проект и детерминированный этап задачи.
- Долговременная — отдельно подтверждённые записи владельца для агента,
  GLOBAL или PROJECT (только совпадающий projectKey).
- Профиль — имя, стиль, формат, ограничения, применяемые к основным ответам.

Planning → Execution → Validation → Done: переходы выполняет приложение по кнопке
пользователя с guards. LLM не подтверждает этапы.
Пауза сохраняет задачу; новый чат начинает новую рабочую память; ветки независимы.

При создании выбираются Summary, Sliding Window, Sticky Facts или Branching.
Сокращённый контекст **не удаляет полный transcript** из PostgreSQL и UI,
включая Summary. Лимит символов контекста — не точный token-limit модели.

## Хранение и расходы

PostgreSQL + pgvector обязательна. RAG_DATABASE_* теперь задают общую базу приложения,
**даже при RAG_ENABLED=false**.

Схема rag: BCrypt-аккаунты/профили, чаты с отдельной working_memory,
полный архив сообщений, долговременная память, настройки, журнал запросов/usage,
MCP-подтверждения/результаты, документы/индексы/чанки.
Оригиналы файлов — bytea; приватный scratch-файл существует только при разборе PDF/Word.

Flyway применяет миграции, но **не очищает базу и не импортирует старые JSON-файлы**.
Старые файловые аккаунты не видны: зарегистрируйте профиль заново.
Старые каталоги автоматически не удаляются. Для демонстрации можно использовать
отдельную чистую базу. Поддерживается **один backend-инстанс**:
блокировки генерации и restart recovery не являются распределёнными leases.

Ответ показывает модель, время, input/output/total tokens и оценку USD по YAML-тарифам.
Общий журнал показывает известные расходы на память, guards, summary, rewrite,
ответы и неуспешные попытки. При отсутствующем provider usage (например после остановки
MCP для разрешения) выводится **неполная статистика**, не полный счёт провайдера.
Embedding usage и время поиска сохраняются в retrieval trace.
Наследованные ответы веток не оплачиваются повторно.

## Разработка и запуск

Нужны Java 21+, Maven, Node.js 22+, PostgreSQL с pgvector,
Ollama (embeddinggemma, 768 измерений), reranker из deploy/reranker.
[Windows/WSL2 GPU-инструкция](deploy/rag-gpu/README.md).

~~~bash
export OPENAI_API_KEY="ваш-ключ"
export RAG_DATABASE_URL="jdbc:postgresql://192.168.1.34:5432/ai_advent_rag"
export RAG_DATABASE_USERNAME="ai_advent_rag"
export RAG_DATABASE_POOL_SIZE=50
read -s RAG_DATABASE_PASSWORD
export RAG_DATABASE_PASSWORD
export RAG_ENABLED=true
export RAG_OLLAMA_BASE_URL="http://192.168.1.34:11434"
export RAG_RERANKER_URL="http://192.168.1.34:8000"
export MCP_FILESYSTEM_ROOT="/абсолютный/путь/к/share"
mvn spring-boot:run
~~~

Отдельный терминал:

~~~bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
~~~

UI: http://localhost:5173; backend: http://localhost:8080.
Vite проксирует /api. В IDEA задайте те же переменные в Run Configuration.
Для удалённого Basic auth обязателен HTTPS.

Модели/prompts/лимиты/три тарифа (input/cached input/output) — agents/*.yaml.
Внешний каталог: AGENT_CONFIG_LOCATION=file:/абсолютный/путь/*.yaml;
он заменяет bundled-набор. После изменений перезапустите backend.
Proxy: OPENAI_PROXY_ENABLED/HOST/PORT. Ключи не передаются React.

Подключаются проектные MCP-серверы через stdio: filesystem (`MCP_FILESYSTEM_ROOT`),
project-git (`MCP_PROJECT_GIT_ROOT`) и excel (`MCP_EXCEL_DIRECTORY`).
Без MCP: MCP_ENABLED=false. Не давайте filesystem-MCP доступ к секретам или backup.

## Проверки

~~~bash
cd frontend
npm ci
npm test
npm run build
cd ..
mvn test
mvn package
~~~

PostgreSQL integration tests — **только отдельная тестовая база**:

~~~bash
export RAG_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:15435/day25_test"
export RAG_TEST_DATABASE_USERNAME="day25_test"
export RAG_TEST_DATABASE_PASSWORD="пароль-тестовой-базы"
mvn -Dtest=HarnessPostgresTest,RagPostgresTest test
~~~

Mock-тесты: два диалога по 12 ходов с проверкой цели/терминов/источников на каждом ответе.
Это проверка контрактов, не качества реальной модели.
[Два ручных сценария по 12 сообщений](homework25.md).

## HTTP API

- /api/auth/register, /api/auth/me, /api/profile — аккаунт и профиль.
- /api/agents, /api/agents/{agentId}/chats — каталог, чаты, удаление и ветки.
- /api/agents/{agentId}/chats/{chatId}/task — память задачи;
  /advance, /pause, /resume — этапы.
- /api/agents/{agentId}/memory — отдельно подтверждённая долговременная память.
- Harness prefix: /api/harness/agents/{agentId}/chats/{chatId}:
  GET /history, GET|PUT /settings, POST /messages/stream,
  GET /pending, GET /usage, GET /requests/{requestId},
  POST /requests/{requestId}/approval/stream, DELETE /requests/{requestId}.
- /api/harness/knowledge/stats — статистика текущего владельца.
- `/api/rag/documents` — загрузка/просмотр; `DELETE /api/rag/documents/{id}` удаляет документ владельца.
- `/api/rag/documents/{document}/indexes`, `/api/rag/indexes/{run}/chunks` — индексация и просмотр чанков.
- /api/mcp/servers — discovery без выполнения инструментов.

SSE: retrieving, проверки, обновление памяти, generating, delta, approval,
syncing_questions, completed/error. Approval закрывает поток без удержания worker
до решения пользователя. Незавершённый assistant-текст не сохраняется.

## Production-сборка и VM

~~~bash
mvn clean package
java -jar target/ai-advent-challenge-0.0.1-SNAPSHOT.jar
~~~

Maven выполняет npm ci, собирает React и создаёт один runnable JAR.
UI-тесты запускайте отдельно через npm test.
Для filesystem-MCP нужен mcp/node_modules рядом с JAR или абсолютный entrypoint.

На cloudvm нужны Java 21+, Node, systemd, Nginx, доступ к PostgreSQL/Ollama/reranker.
Существующий /etc/ai-advent-challenge.env **не перезаписывать**:
добавьте недостающие RAG_DATABASE_*, RAG_ENABLED, URL сервисов и MCP-настройки.
Сохраните proxy, ключ, внешние YAML и backup PostgreSQL.

~~~bash
scp target/ai-advent-challenge-0.0.1-SNAPSHOT.jar cloudvm:/tmp/ai-advent-challenge.jar
ssh cloudvm 'sudo install -o vlad -g vlad -m 0644 /tmp/ai-advent-challenge.jar /opt/ai-advent-challenge/ai-advent-challenge.jar'
ssh cloudvm 'sudo systemctl restart ai-advent-challenge'
ssh cloudvm 'sudo systemctl is-active ai-advent-challenge; sudo journalctl -u ai-advent-challenge --since "5 minutes ago" --no-pager -p err -q'
~~~

systemd: WorkingDirectory=/opt/ai-advent-challenge,
EnvironmentFile=/etc/ai-advent-challenge.env, bind 127.0.0.1:8080.
Nginx: same-origin HTTPS, SSE buffering/cache выключены, достаточный read timeout.
PostgreSQL/Ollama/reranker не открывать в интернет; production CORS не требуется.
