# RAG on an NVIDIA computer

**Windows + Docker Desktop (WSL2): [пошаговая инструкция на русском](WINDOWS.md).**

Compose starts Ollama on the GPU. PostgreSQL/pgvector is optional: to accelerate embedding
generation, move only Ollama and keep your existing PostgreSQL and uploaded files unchanged.
This directory does not deploy the Java application or React.

## Prerequisites

Instructions below assume native Ubuntu/Linux, Docker Engine and Docker Compose v2.
For Windows, use Docker Desktop with the WSL2 backend and working NVIDIA GPU passthrough;
do not apply Linux driver installation instructions directly to Windows.

GTX 1060 has compute capability 6.1. Current Ollama documentation requires an NVIDIA
driver 570+ for compute capabilities 5.0–6.2. Use a proprietary driver version that
actually supports GTX 1060, not an arbitrary newest/open-kernel driver.
Verify `nvidia-smi` on the host before starting containers.

Install NVIDIA Container Toolkit using its official distribution-specific instructions:
https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html

After installation, on native Linux:

```bash
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
nvidia-smi
```

Restarting Docker affects other containers on that computer.
GPU requirements: https://docs.ollama.com/gpu
Docker setup: https://docs.ollama.com/docker

## Start Ollama

Copy this directory to the GPU computer, then run commands there:

```bash
cd deploy/rag-gpu
cp -n .env.example .env
```

Edit `.env`: set `BIND_IP` to the GPU computer's LAN address, for example `192.168.1.50`.
Leave it as `127.0.0.1` if accessing through an SSH tunnel instead.
Do not use the address of your Mac or cloudvm. Prefer a fixed DHCP reservation.

```bash
docker compose config --quiet
docker compose up -d ollama
docker compose exec ollama ollama pull embeddinggemma
docker compose ps
docker compose exec ollama nvidia-smi
```

Model download is explicit and required once. Health status alone does not mean the model is installed.
The model volume survives container recreation. No CPU-only fallback configuration is provided:
if Docker cannot reserve the GPU, fix driver/toolkit configuration.

From your Mac (substitute the real IP):

```bash
curl http://192.168.1.50:11434/api/tags
curl http://192.168.1.50:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"embeddinggemma","input":"Проверка индексации документа","truncate":false}'
```

Immediately after embedding, on the GPU computer:

```bash
docker compose exec ollama ollama ps
docker compose exec ollama nvidia-smi
docker compose logs --tail=100 ollama
```

`ollama ps` should show GPU utilization/offload (ideally `100% GPU`), not `100% CPU`.
VRAM usage in nvidia-smi is supporting evidence; GPU compute utilization may fall back to zero
between short requests. Actual speedup must be measured; first request may load the model.
Embeddinggemma is much smaller than a chat LLM; 6 GB VRAM should be enough for this workload,
but successful GPU execution on this particular driver/card still needs verification.

## Connect the application (recommended: leave PostgreSQL on cloudvm)

In the terminal or IDE that launches the Java backend, change only:

```bash
export RAG_OLLAMA_BASE_URL=http://192.168.1.50:11434
export RAG_EMBEDDING_MODEL=embeddinggemma
```

Keep `RAG_ENABLED=true`, existing `RAG_DATABASE_*`, and the PostgreSQL SSH tunnel.
Restart the backend, then create a NEW indexing run from the RAG tab.
An in-progress run does not switch providers mid-flight. You do not need to reupload a document
when keeping the same DB and application data. Source files remain on the application computer.
The application calls this API; the browser never accesses Ollama directly. No CORS wildcard is needed.

Only the embedding step benefits from GPU. PDF extraction, chunking, DB latency and the
current sequential per-chunk requests remain separate sources of execution time.

## Optional: new local PostgreSQL

This is a new empty database, NOT migration of cloudvm records. To preserve existing indexes,
plan a backup/restore separately. Do not switch database addresses while indexing is running.

Set two different nonempty strong passwords in `.env`: `POSTGRES_PASSWORD` (admin) and
`RAG_DATABASE_PASSWORD` (application). Quote values containing `$` with single quotes in `.env`.
Do not commit `.env` or post passwords in chat.

```bash
docker compose --profile database up -d
docker compose exec postgres psql -U postgres -d ai_advent_rag \
  -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

The initialization script installs public.vector and creates a non-superuser application role.
On the application computer set:

```bash
export RAG_DATABASE_URL=jdbc:postgresql://192.168.1.50:5432/ai_advent_rag
export RAG_DATABASE_USERNAME=ai_advent_rag
# Set RAG_DATABASE_PASSWORD securely to the application password from .env.
```

Restart backend: Flyway creates the rag schema. Initialization scripts and passwords from
environment apply only to an EMPTY PostgreSQL volume. Editing `.env` later does not change
passwords in an existing DB; use `\password ai_advent_rag` from an authenticated admin psql session.

## Network safety and stopping

Ollama has no authentication here. Expose 11434 (and optionally 5432) only on a trusted LAN,
restrict access to the application computer with Docker-aware firewall rules, and never forward
these ports on the router. Docker published ports can bypass ordinary UFW rules; binding to
a private IP alone does not restrict other devices on that LAN. For untrusted networks, keep
loopback binding and use SSH/VPN. No TLS is provided in this local configuration.

```bash
docker compose --profile database down
```

This stops containers without deleting named volumes. Do not use `down -v` unless intentionally
deleting all database/model data. Images are pinned; update versions deliberately after testing.
