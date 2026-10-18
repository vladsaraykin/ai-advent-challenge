-- Deliberately no cascading chat FK: deleting a conversation must not erase task worktree recovery metadata.
CREATE TABLE rag.project_workspaces (
 id uuid PRIMARY KEY,
 owner_username text NOT NULL,
 agent_id text NOT NULL,
 revision bigint NOT NULL DEFAULT 0,
 state jsonb NOT NULL DEFAULT '{}'::jsonb,
 updated_at timestamptz NOT NULL DEFAULT now()
);
