# AI Advent Challenge

A Java 21 / Spring Boot / Spring AI web application for experimenting with OpenAI sampling parameters. It provides a one-page Thymeleaf chat UI and stores conversation history in a local JSON file. Previous user and assistant messages are replayed to the model on every request.

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
| `CHAT_HISTORY_FILE` | `./data/chat-history.json` | Local conversation file |
| `APP_LOG_PROMPTS` | `false` | Log full system/user prompts; enable only when their content is not sensitive |

The UI sends `temperature`, `top_p`, `max_tokens`, `seed`, and `frequency_penalty` through Spring AI. `top_k` is an optional provider-specific field: leave it blank with OpenAI, or set it when using an OpenAI-compatible provider that supports it (for example vLLM or Ollama's OpenAI-compatible endpoint).

## Test and package

```bash
mvn test
mvn package
```

API keys are read only from the environment and are never written to chat history.
