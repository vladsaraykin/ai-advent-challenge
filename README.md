# AI Advent Challenge — День 4

Лаборатория сравнивает ответы OpenAI на один и тот же запрос при `temperature = 0`, `0.7` и `1.2`. Prompt, модель и лимит токенов одинаковы во всех трёх вызовах; одновременно выполняются не более двух вызовов.

## Архитектура

- Java 21 + Spring Boot: JSON REST API, orchestration и адаптер Spring AI/OpenAI.
- React JavaScript + Vite: форма, состояния ожидания/ошибок, безопасный Markdown и памятка на русском.
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

API: `POST /api/temperature-experiments` с JSON `{"prompt":"...","model":"gpt-4.1-mini","maxTokens":1000}`.

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
  curl --fail --silent http://127.0.0.1/api/temperature-experiments/models
  sudo journalctl -u ai-advent-challenge --since "5 minutes ago" \
    --no-pager -p err -q
'
```

Java должна слушать только loopback-интерфейс `127.0.0.1:8080`; внешний трафик обслуживает Nginx. Проверка конфигурационного endpoint не вызывает OpenAI API и не расходует токены.
