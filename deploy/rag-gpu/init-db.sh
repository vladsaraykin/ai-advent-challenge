#!/bin/sh
# Runs only when PostgreSQL initializes an empty volume.
: "${RAG_DATABASE_PASSWORD:?Set RAG_DATABASE_PASSWORD before initializing PostgreSQL}"
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_password="$RAG_DATABASE_PASSWORD" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE ROLE ai_advent_rag LOGIN PASSWORD :'app_password';
GRANT CONNECT, CREATE ON DATABASE ai_advent_rag TO ai_advent_rag;
GRANT USAGE ON SCHEMA public TO ai_advent_rag;
SQL
