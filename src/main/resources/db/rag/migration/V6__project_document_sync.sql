CREATE TABLE rag.project_document_sync (
    owner_username VARCHAR(128) NOT NULL,
    source_path VARCHAR(512) NOT NULL,
    active_sha256 VARCHAR(64),
    active_document_id UUID,
    pending_sha256 VARCHAR(64),
    pending_document_id UUID,
    pending_run_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_username, source_path),
    CHECK ((pending_sha256 IS NULL) = (pending_document_id IS NULL)),
    CHECK ((pending_document_id IS NULL) = (pending_run_id IS NULL))
);
