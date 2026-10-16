CREATE TABLE rag.code_reviews (
    owner_username VARCHAR(128) NOT NULL,
    id UUID NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    request_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED','INTERRUPTED')),
    sources_json JSONB,
    metrics_json JSONB,
    usage_incomplete BOOLEAN NOT NULL DEFAULT true,
    result_json JSONB,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_username, id)
);
