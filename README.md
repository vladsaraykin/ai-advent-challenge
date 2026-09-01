# AI Advent Challenge

A Java 21 / Spring Boot / Spring AI web application for experimenting with OpenAI prompts and sampling parameters.

- `/` is a parameterized chat with file-backed conversation history.
- `/movies` is a movie expert with two independent chat modes. First, the user can find a movie in a conversation with model defaults. Then they can manually repeat the dialogue in controlled mode with an explicit output format, answer limits, a stop sequence, and custom sampling settings.

The controlled movie answer supports Markdown and strict JSON. JSON recommendations contain the title, the expert's 0–10 rating, release year or series run, fit, difference, and mood.

## Run with OpenAI

```bash
export OPENAI_API_KEY="your-api-key"
mvn spring-boot:run
```

Open <http://localhost:8080>.

Optional environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Complete OpenAI-compatible API base URL, including `/v1` when required |
| `OPENAI_MODEL` | `gpt-4.1-mini` | Default model configured on the server |
| `OPENAI_PROXY_ENABLED` | `false` | Route OpenAI HTTP requests through a proxy |
| `OPENAI_PROXY_HOST` | `127.0.0.1` | OpenAI HTTP proxy host |
| `OPENAI_PROXY_PORT` | `10809` | OpenAI HTTP proxy port |
| `CHAT_HISTORY_FILE` | `./data/chat-history.json` | Local conversation file |
| `APP_LOG_PROMPTS` | `false` | Log full system/user prompts; enable only when their content is not sensitive |

The UI sends `temperature`, `top_p`, `max_tokens`, `seed`, and `frequency_penalty` through Spring AI. `top_k` is an optional provider-specific field: leave it blank with OpenAI, or set it when using an OpenAI-compatible provider that supports it (for example vLLM or Ollama's OpenAI-compatible endpoint).

## Test and package

```bash
mvn test
mvn package
```

API keys are read only from the environment and are never written to chat history.
