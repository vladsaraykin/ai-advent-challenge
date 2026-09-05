# AI Advent Challenge — День 5

Лаборатория отправляет один и тот же запрос трём выбранным пользователем GPT-моделям OpenAI и показывает ответы без автоматической оценки качества.

Для каждой позиции — «слабая», «средняя» и «сильная» — модель выбирается отдельно. Одинаковую модель выбрать дважды нельзя.

Для каждой модели измеряются время ответа, входные/выходные токены и оценочная стоимость. Одновременно выполняются не более двух вызовов.

## Архитектура

- Java 21 + Spring Boot: JSON REST API, orchestration и адаптер Spring AI/OpenAI.
- React JavaScript + Vite: форма, состояния ожидания/ошибок, безопасный Markdown и сопоставимые карточки результатов.
- Maven устанавливает Node, выполняет `npm ci`, собирает React и включает `frontend/dist` в runnable JAR.

## Требования

- Java 21;
- Maven 3.9+;
- ключ OpenAI API в переменной `OPENAI_API_KEY`.

Устанавливать Node.js для полной Maven-сборки необязательно: `frontend-maven-plugin` скачивает закреплённую версию в `target/node`.

## Разработка

### Запуск всего приложения через Spring Boot

```bash
export OPENAI_API_KEY="ваш-api-ключ"
mvn spring-boot:run
```

Maven установит frontend-зависимости, соберёт React и запустит backend. Приложение будет доступно по адресу <http://localhost:8080>.

### Раздельный запуск backend и frontend

Backend запускается из корня проекта:

```bash
export OPENAI_API_KEY="ваш-api-ключ"
mvn spring-boot:run
```

Frontend запускается во втором терминале:

```bash
cd frontend
npm ci
npm run dev
```

Vite откроет UI на <http://localhost:5173> и будет проксировать `/api` на Spring Boot по адресу `http://localhost:8080`.

## Проверки

```bash
cd frontend && npm test && npm run build
mvn test
mvn package
```

API: `POST /api/model-comparisons` с JSON `{"prompt":"...","maxTokens":1000,"models":["gpt-4o-mini","gpt-5-mini","gpt-5.6-sol"]}`. Порядок массива соответствует карточкам «слабая», «средняя», «сильная». Каталог моделей и тарифов: `GET /api/model-comparisons/models`.

Допустимый лимит ответа `maxTokens`: от 64 до 32768 токенов. Он передаётся OpenAI как `max_completion_tokens` и включает видимые выходные и reasoning-токены.

## Логи сравнений

Каждое сравнение получает `requestId`. По нему в логах связываются входящий запрос, отдельные вызовы OpenAI и итоговая статистика. Preview запроса приводится к одной строке и ограничивается 1000 символами, секреты и тела ошибок OpenAI не логируются.

На виртуальной машине смотреть поток логов можно так:

```bash
sudo journalctl -u ai-advent-challenge -f
```

Только события сравнений и вызовов OpenAI:

```bash
sudo journalctl -u ai-advent-challenge --since today | grep -E 'comparison_|openai_call_'
```

Итоговая запись `comparison_completed` содержит фактическое число начатых запросов к OpenAI (`outboundCalls`), успешные и неуспешные вызовы, общее время, токены и оценочную стоимость.

Стоимость рассчитывается по токенам на основании стандартных тарифов OpenAI за 1 млн токенов, зафиксированных для задания. Источник тарифов и перечня моделей: <https://developers.openai.com/api/docs/models> (проверено 05.09.2026).

| Модель | Входные токены | Выходные токены |
| --- | ---: | ---: |
| GPT-4o Mini | $0.15 | $0.60 |
| GPT-4.1 Mini | $0.40 | $1.60 |
| GPT-4.1 | $2.00 | $8.00 |
| GPT-5 Nano | $0.05 | $0.40 |
| GPT-5 Mini | $0.25 | $2.00 |
| GPT-5 | $1.25 | $10.00 |
| GPT-5.6 Luna | $0.20 | $1.20 |
| GPT-5.6 Terra | $2.00 | $12.00 |
| GPT-5.6 Sol | $4.00 | $20.00 |

## Production-сборка

Из корня проекта выполните:

```bash
mvn clean package
```

Эта команда запускает `npm ci`, собирает React, выполняет Java-тесты и создаёт единый runnable JAR:

```text
target/ai-advent-challenge-0.0.1-SNAPSHOT.jar
```

Локально production-артефакт можно запустить так:

```bash
export OPENAI_API_KEY="ваш-api-ключ"
java -jar target/ai-advent-challenge-0.0.1-SNAPSHOT.jar
```

## Деплой и запуск на `cloudvm`

На виртуальной машине уже должны существовать:

- Java 21;
- systemd-сервис `ai-advent-challenge.service`;
- Nginx, проксирующий HTTP-запросы на `127.0.0.1:8080`;
- защищённый `/etc/ai-advent-challenge.env` с `OPENAI_API_KEY` и настройками proxy.

Файл `/etc/ai-advent-challenge.env` нельзя копировать из репозитория, перезаписывать или выводить в лог. Его ожидаемые права — `0600`.

Сначала соберите приложение локально:

```bash
mvn clean package
```

Загрузите JAR во временный файл:

```bash
scp target/ai-advent-challenge-0.0.1-SNAPSHOT.jar \
  cloudvm:/tmp/ai-advent-challenge.jar
```

Установите артефакт и перезапустите сервис:

```bash
ssh cloudvm '
  set -eu
  sudo install -o vlad -g vlad -m 0644 \
    /tmp/ai-advent-challenge.jar \
    /opt/ai-advent-challenge/ai-advent-challenge.jar
  rm -f /tmp/ai-advent-challenge.jar
  sudo systemctl restart ai-advent-challenge
'
```

Проверьте приложение без расходования OpenAI-токенов:

```bash
ssh cloudvm '
  sudo systemctl is-active ai-advent-challenge
  sudo systemctl is-active nginx
  sudo ss -ltn "sport = :8080"
  curl --fail --silent http://127.0.0.1/
  curl --fail --silent http://127.0.0.1/api/model-comparisons/models
  sudo journalctl -u ai-advent-challenge --since "5 minutes ago" \
    --no-pager -p err -q
'
```

Java должна слушать только loopback-интерфейс `127.0.0.1:8080`; внешний трафик обслуживает Nginx. Проверка конфигурационного endpoint не вызывает OpenAI API и не расходует токены.
