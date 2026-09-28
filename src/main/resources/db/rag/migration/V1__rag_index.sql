-- pgvector is provisioned by an administrator, not by the application role.
-- Files, API endpoints and indexing orchestration are added in later steps.
CREATE TABLE rag.documents (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(128) NOT NULL CHECK (length(trim(owner_username)) > 0),
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    storage_key UUID NOT NULL UNIQUE,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    extracted_text TEXT,
    extraction_metadata JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(extraction_metadata) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_username, id)
);
CREATE INDEX documents_owner_created ON rag.documents (owner_username, created_at DESC);

-- An independent run per strategy. Never overwrite an earlier successful run.
CREATE TABLE rag.index_runs (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(128) NOT NULL,
    document_id UUID NOT NULL,
    strategy VARCHAR(24) NOT NULL CHECK (strategy IN ('FIXED_SIZE', 'STRUCTURAL')),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'EXTRACTING', 'CHUNKING', 'EMBEDDING', 'COMPLETED', 'FAILED')),
    embedding_model VARCHAR(255) NOT NULL,
    embedding_dimensions INTEGER NOT NULL CHECK (embedding_dimensions = 768),
    chunking_parameters JSONB NOT NULL CHECK (jsonb_typeof(chunking_parameters) = 'object'),
    input_sha256 VARCHAR(64) CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    total_chunks INTEGER NOT NULL DEFAULT 0 CHECK (total_chunks >= 0),
    embedded_chunks INTEGER NOT NULL DEFAULT 0 CHECK (embedded_chunks BETWEEN 0 AND total_chunks),
    prompt_tokens BIGINT CHECK (prompt_tokens >= 0),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    error_code VARCHAR(64), -- Sanitized application code only, never raw upstream errors.
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (owner_username, document_id) REFERENCES rag.documents (owner_username, id) ON DELETE CASCADE,
    UNIQUE (owner_username, id),
    CHECK ((status IN ('COMPLETED', 'FAILED')) = (finished_at IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR (total_chunks > 0 AND embedded_chunks = total_chunks))
);
CREATE INDEX runs_owner_document ON rag.index_runs (owner_username, document_id, created_at DESC);

CREATE TABLE rag.chunks (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(128) NOT NULL,
    run_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    source VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    section TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL CHECK (length(content) > 0),
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    token_count INTEGER CHECK (token_count >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    embedding public.vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_username, run_id) REFERENCES rag.index_runs (owner_username, id) ON DELETE CASCADE,
    UNIQUE (owner_username, run_id, ordinal)
);
-- No ANN index yet: day 21 stores embeddings; retrieval is a separate feature.
