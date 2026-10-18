ALTER TABLE rag.mcp_calls ADD COLUMN tool_signature text;

CREATE TABLE rag.mcp_tool_permissions (
 owner_username text NOT NULL,
 chat_id uuid NOT NULL,
 server_id text NOT NULL,
 tool_name text NOT NULL,
 tool_signature text NOT NULL,
 granted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_username,chat_id,server_id,tool_name),
 FOREIGN KEY(owner_username,chat_id) REFERENCES rag.chats(owner_username,id) ON DELETE CASCADE
);
