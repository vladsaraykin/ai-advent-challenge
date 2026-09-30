# Локальный reranker для Дня 23

Отдельный HTTP-сервис, embeddinggemma/Ollama и PostgreSQL остаются прежними.
Модель [cross-encoder/mmarco-mMiniLMv2-L12-H384-v1](https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1)
обрабатывает пары вопрос–текст. [CrossEncoder](https://sbert.net/docs/package_reference/cross_encoder/model.html)
использует явно заданный Sigmoid: оценки в диапазоне 0–1, но **не вероятность корректности ответа**.
Порог 0.2 — стартовое значение для ручной проверки, не универсальная настройка.

## Запуск Windows + Docker Desktop / Linux / macOS

Из корня репозитория:

```bash
docker compose -f deploy/reranker/compose.yaml up -d --build
docker compose -f deploy/reranker/compose.yaml logs -f reranker
curl http://127.0.0.1:18090/health
```

Сервис запускается на CPU: NVIDIA/CUDA не нужны. На Windows PowerShell используйте
`curl.exe` вместо `curl`. Первый старт скачивает веса Hugging Face, требует интернета
и может занять несколько минут. Веса сохраняются в named volume; предусмотрите несколько
ГБ свободного места/памяти для образа, зависимостей и модели. Health станет доступен
после загрузки модели. Сервис не запускается автоматически вместе с Java-приложением.

Backend:

```bash
export RAG_RERANKER_URL=http://127.0.0.1:18090
export RAG_RERANKER_TIMEOUT_SECONDS=60
export RAG_CANDIDATE_K=20
export RAG_FINAL_K=5
export RAG_RERANKER_THRESHOLD=0.2
```

Для компьютера в LAN перед запуском Compose в PowerShell задайте
`$env:BIND_IP="0.0.0.0"`, разрешите TCP 18090 в Windows Firewall только с адреса
компьютера backend. На backend `RAG_RERANKER_URL=http://<IP-Windows>:18090`.
Не открывайте порт в интернет: у внутреннего сервиса нет аутентификации.
В UI адрес сервиса не передаётся. Перезапустите backend после изменения окружения.
Для остановки: `docker compose -f deploy/reranker/compose.yaml stop`.

## Прямая проверка

В PowerShell:

```powershell
$body = @{ query = "Какие артефакты нужны для передачи сессии?"; documents = @("Передача сессии требует progress.md и feature-list.json.", "На обед приготовили омлет.") } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:18090/rerank -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

Ответ `scores` имеет ту же длину и порядок, что `documents`; сортирует и фильтрует Java.
Нельзя ожидать конкретных чисел до запуска реальной модели.

Длинный чанк разделяется на перекрывающиеся окна 400 токенов (шаг 336), оценка чанка —
максимум оценок его окон. Поисковая пара ограничена 512 токенами модели; вопрос для
reranker ограничивается первыми 96 токенами. Это ограничение относится только к оценке,
полный исходный вопрос и полные выбранные чанки отправляются модели ответа.
Длинные чанки получают больше попыток высокого score — учитывайте это при подборе порога.

Один inference одновременно; новый конкурентный вызов получает 429. Backend показывает
явную ошибку без молчаливой подмены базовым RAG. Поддерживаются 1–50 документов по 30000
символов, суммарно до 500000 символов. Тексты/вопросы не сохраняются и не логируются сервисом.
