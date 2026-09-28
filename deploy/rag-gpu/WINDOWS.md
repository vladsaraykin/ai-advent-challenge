# Windows + Docker Desktop + GTX 1060 6 GB

Рекомендуемый вариант: переносим только Ollama. PostgreSQL остаётся на cloudvm,
Java и React — на Mac. Существующие документы и индексы не нужно переносить.

## 1. Подготовка Windows

1. Установите Windows-драйвер NVIDIA, поддерживающий GTX 1060 и WSL2.
   По текущей документации Ollama для compute capability 6.1 требуется драйвер 570+.
   Проверяйте поддержку именно этой карты в выбранной версии драйвера.
2. Обновите WSL из PowerShell:

   ```powershell
   wsl --update
   wsl --status
   nvidia-smi
   ```

   Если WSL не установлен: `wsl --install`, затем перезагрузка по запросу Windows.
3. В Docker Desktop включите Settings → General → Use the WSL 2 based engine.
   Используйте Linux containers. Команды ниже выполняются в PowerShell через Docker Desktop,
   отдельный Docker Engine в Ubuntu/WSL устанавливать не требуется.

**Не устанавливайте Linux-драйвер NVIDIA в WSL.** Для Docker Desktop также не выполняйте
Linux-команды `nvidia-ctk runtime configure` и `systemctl restart docker` из соседней инструкции.

Документация: [Docker GPU](https://docs.docker.com/desktop/features/gpu/),
[NVIDIA WSL](https://docs.nvidia.com/cuda/wsl-user-guide/index.html),
[Ollama GPU](https://docs.ollama.com/gpu).

## 2. Скопируйте каталог и запустите Ollama

Скопируйте весь каталог `deploy/rag-gpu` на Windows, например в `C:\ai-advent\rag-gpu`.
Файлы пока находятся в локальном рабочем проекте, а не обязательно в удалённом Git.

```powershell
cd C:\ai-advent\rag-gpu
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

Узнайте IPv4 Windows-компьютера в домашней сети через `ipconfig`, например `192.168.1.50`.
В `.env` задайте `BIND_IP=192.168.1.50` (не адрес виртуального адаптера WSL).
Для запуска только Ollama пароли PostgreSQL оставьте пустыми.

```powershell
docker compose config --quiet
docker compose up -d ollama
docker compose exec ollama ollama pull embeddinggemma
docker compose ps
docker compose exec ollama nvidia-smi
```

Модель скачивается один раз; хранится в постоянном Docker volume.
Если порт 11434 занят установленной Windows-версией Ollama, сначала остановите её.

## 3. Ограничьте доступ по сети

Ollama в этом Compose не имеет аутентификации. Используйте только доверенную домашнюю сеть,
не делайте проброс порта на роутере. Для доступа с Mac создайте узкое правило Windows Firewall
в PowerShell **от администратора**, заменив оба адреса:

```powershell
New-NetFirewallRule -DisplayName 'Ollama from Mac' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11434 -LocalAddress 192.168.1.50 -RemoteAddress 192.168.1.20 -Profile Private
```

`192.168.1.20` — IP Mac, `192.168.1.50` — Windows. У домашнего подключения должен быть профиль Private.
Проверьте существующие широкие разрешающие правила Docker Desktop: узкое правило не отменяет их.
Не отключайте firewall. Если доступ всё ещё закрыт, проверьте привязку порта, профиль сети,
правила Hyper-V/WSL и изоляцию клиентов Wi-Fi. Сначала проверяйте Windows-IP, а не внутренний WSL-IP.

## 4. Проверка с Mac и проверка GPU

На Mac:

```bash
curl http://192.168.1.50:11434/api/tags
curl http://192.168.1.50:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"embeddinggemma","input":"Проверка GPU","truncate":false}'
```

Сразу после запроса в PowerShell на Windows:

```powershell
docker compose exec ollama ollama ps
docker compose logs --tail=100 ollama
```

В `ollama ps` ожидается `100% GPU`, а не `100% CPU`. Сам факт запуска контейнера
и успешный `nvidia-smi` ещё не доказывают ускорение модели. В WSL часть показателей nvidia-smi
ограничена. При CPU-only результате проверьте журнал, поддержку драйвера и GPU.
6 GB должно хватать для небольшой embeddinggemma; реальное ускорение нужно измерить,
оно не гарантируется конкретным коэффициентом. Первый запрос включает загрузку модели.

## 5. Подключение приложения

В терминале Mac, из которого запускается Java:

```bash
export RAG_ENABLED=true
export RAG_OLLAMA_BASE_URL=http://192.168.1.50:11434
export RAG_EMBEDDING_MODEL=embeddinggemma
```

Оставьте прежние `RAG_DATABASE_*` и SSH-туннель к PostgreSQL. Перезапустите backend
и создайте новый запуск индексации во вкладке RAG. Не нужно менять настройки Vite или CORS.

Весь PDF всё ещё делится и обрабатывается последовательно. GPU ускоряет эмбеддинги,
но не сетевые обращения, SQL и извлечение текста.

## Опционально: PostgreSQL на Windows

Если хотите перенести и БД, сначала решите, нужен ли перенос старых индексов: профиль Compose
создаёт НОВУЮ базу. Резервное копирование/восстановление в эти команды не входит.

Задайте в `.env` разные сильные `POSTGRES_PASSWORD` и `RAG_DATABASE_PASSWORD`, затем:

```powershell
docker compose --profile database up -d
docker compose exec postgres psql -U postgres -d ai_advent_rag -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

Откройте 5432 только для Mac аналогичным узким правилом firewall. На Mac задайте
`RAG_DATABASE_URL=jdbc:postgresql://192.168.1.50:5432/ai_advent_rag`,
`RAG_DATABASE_USERNAME=ai_advent_rag` и пароль приложения, затем перезапустите backend.
Приложение использует отдельную роль без superuser. Сохраняйте LF в `init-db.sh`.
Скрипт и пароли окружения применяются только при первоначальной инициализации пустого volume.

Остановка без удаления данных:

```powershell
docker compose --profile database down
```

Не используйте `down -v`: это удалит volumes с БД и моделями. Не выключайте Docker Desktop
и не отправляйте Windows в сон во время индексации.
