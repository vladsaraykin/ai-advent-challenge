# Пользователи: регистрация и вход

Документ описывает фактическую реализацию пользовательского доступа в UI, API и PostgreSQL.

## Краткая схема

- Регистрация выполняется отдельным публичным запросом `POST /api/auth/register`.
- Отдельного endpoint `login` нет: вход — это HTTP Basic-аутентификация при обращении к защищённому `GET /api/auth/me`.
- Все остальные `/api/**` требуют аутентификации; статические ресурсы и регистрация доступны без неё.
- CSRF отключён. При удалённом доступе нужен HTTPS, поскольку Basic-заголовок передаётся с каждым запросом.

## Регистрация

### Через UI

1. На экране входа нажать **«Создать новый профиль»**.
2. Заполнить логин, отображаемое имя и пароль.
3. Нажать **«Создать и войти»**.

UI вызывает регистрацию, затем сохраняет Basic-учётные данные в `sessionStorage` под ключом `agent-lab.basic` и проверяет их через `/api/auth/me`. Логин перед сохранением обрезается по краям и переводится в нижний регистр.

### Через API

```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "alice",
  "password": "secret-123",
  "displayName": "Alice"
}
```

Успешный ответ: `201 Created` и созданный профиль пользователя.

Ограничения:

- `username`: 3–40 символов; первый символ — латинская буква или цифра, далее допустимы латинские буквы, цифры, `.`, `_`, `-`;
- сервер удаляет пробелы по краям логина и приводит его к нижнему регистру;
- `password`: 8–200 символов;
- `displayName`: не более 80 символов. API-валидация требует поле, но сервис подставляет нормализованный логин, если значение пустое или состоит из пробелов;
- повторный логин отклоняется с `400 Bad Request` и сообщением о существующем пользователе.

При создании профиль получает `version = 0`, пустые ограничения и значения по умолчанию: стиль «Кратко и по существу», формат «Структурированный Markdown».

## Вход (log in)

### Через UI

1. Ввести логин и пароль на экране **«Войти в свой профиль»**.
2. Нажать **«Войти»**.
3. UI формирует Base64 от строки `username:password`, сохраняет его в `sessionStorage` и вызывает `GET /api/auth/me` с заголовком `Authorization: Basic ...`.
4. При успехе сервер возвращает профиль, и приложение открывает основной интерфейс. При ошибке UI удаляет сохранённые credentials.

При загрузке приложения сохранённые credentials также проверяются через `/api/auth/me`. Выход очищает их из `sessionStorage` и сбрасывает пользовательское состояние UI.

### Через API

```bash
curl -u 'alice:secret-123' http://localhost:8080/api/auth/me
```

Тот же Basic-заголовок нужно передавать во всех защищённых `/api/**` запросах. Сервер приводит имя пользователя из Basic-аутентификации к нижнему регистру, находит запись в БД и проверяет пароль BCrypt. Сессия или токен не создаются.

Ошибочные или отсутствующие credentials дают `401 Unauthorized`:

```json
{"message":"Требуется вход в профиль"}
```

## Хранение в Database

Миграция создаёт таблицу `rag.users`:

- `username text PRIMARY KEY` — нормализованный логин;
- `password_hash text NOT NULL` — BCrypt-хеш, пароль в открытом виде не хранится;
- `created_at timestamptz NOT NULL`;
- `profile jsonb NOT NULL` — профиль пользователя;
- `profile_version bigint NOT NULL DEFAULT 0 CHECK(profile_version >= 0)` — версия для оптимистической блокировки.

Чаты, долговременная память и настройки привязаны к `owner_username`, поэтому данные разделяются между пользователями внешними ключами и составными первичными ключами.

## Связанные исходники

- UI регистрации и входа: `frontend/src/components/AuthScreen.jsx:3-35`.
- Basic-заголовок, `sessionStorage` и auth API: `frontend/src/api.js:1-7`, `frontend/src/api.js:56-75`.
- Проверка credentials при старте и выход: `frontend/src/App.jsx:199-200`, `frontend/src/App.jsx:386-389`.
- REST API и валидация DTO: `src/main/java/com/github/vladsaraykin/aichat/user/api/UserController.java:12-32`.
- Нормализация, BCrypt и начальный профиль: `src/main/java/com/github/vladsaraykin/aichat/user/application/UserService.java:11-27`.
- Допустимый формат логина: `src/main/java/com/github/vladsaraykin/aichat/user/domain/UserAccount.java:5-13`.
- HTTP Basic и правила доступа: `src/main/java/com/github/vladsaraykin/aichat/config/SecurityConfiguration.java:13-31`.
- SQL-запросы пользователей: `src/main/java/com/github/vladsaraykin/aichat/user/infrastructure/JdbcUserRepository.java:14-38`.
- Схема таблиц: `src/main/resources/db/rag/migration/V3__agent_harness.sql:1-24`.
