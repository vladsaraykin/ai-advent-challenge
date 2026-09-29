CREATE TABLE rag.questions (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(128) NOT NULL,
    index_id UUID NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (owner_username, index_id) REFERENCES rag.index_runs(owner_username,id) ON DELETE CASCADE
);
CREATE INDEX questions_owner_index ON rag.questions(owner_username,index_id,created_at DESC);
