CREATE TABLE rag.harness_requests (
 owner_username text NOT NULL, chat_id uuid NOT NULL, message_id uuid NOT NULL,
 payload jsonb NOT NULL, status text NOT NULL DEFAULT 'RUNNING', usage jsonb NOT NULL DEFAULT '[]',
 usage_complete boolean NOT NULL DEFAULT true, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_username,message_id),
 FOREIGN KEY(owner_username,chat_id) REFERENCES rag.chats(owner_username,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX harness_one_active_request ON rag.harness_requests(owner_username,chat_id)
 WHERE status IN ('RUNNING','AWAITING_APPROVAL');
CREATE TABLE rag.mcp_calls (
 id uuid PRIMARY KEY, owner_username text NOT NULL, chat_id uuid NOT NULL, message_id uuid NOT NULL,
 server_id text NOT NULL, tool_name text NOT NULL, input jsonb NOT NULL, input_hash text NOT NULL,
 ordinal integer NOT NULL, status text NOT NULL, result text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_username,message_id,server_id,tool_name,input_hash),
 FOREIGN KEY(owner_username,message_id) REFERENCES rag.harness_requests(owner_username,message_id) ON DELETE CASCADE
);
