-- Fresh user-owned storage. No import and no automatic deletion of previous data.
CREATE TABLE rag.users (
 username text PRIMARY KEY, password_hash text NOT NULL, created_at timestamptz NOT NULL,
 profile jsonb NOT NULL, profile_version bigint NOT NULL DEFAULT 0 CHECK(profile_version>=0)
);
CREATE TABLE rag.chats (
 owner_username text NOT NULL REFERENCES rag.users(username), id uuid NOT NULL, agent_id text NOT NULL,
 parent_id uuid, payload jsonb NOT NULL, working_memory jsonb NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(owner_username,id),
 FOREIGN KEY(owner_username,parent_id) REFERENCES rag.chats(owner_username,id) ON DELETE CASCADE
);
CREATE INDEX chats_owner_agent ON rag.chats(owner_username,agent_id,updated_at DESC);
CREATE TABLE rag.long_term_memory (
 owner_username text NOT NULL REFERENCES rag.users(username), agent_id text NOT NULL,
 version bigint NOT NULL DEFAULT 0, payload jsonb NOT NULL, PRIMARY KEY(owner_username,agent_id)
);
CREATE TABLE rag.chat_settings (
 owner_username text NOT NULL, chat_id uuid NOT NULL, version bigint NOT NULL DEFAULT 0, payload jsonb NOT NULL,
 PRIMARY KEY(owner_username,chat_id),
 FOREIGN KEY(owner_username,chat_id) REFERENCES rag.chats(owner_username,id) ON DELETE CASCADE
);
CREATE TABLE rag.chat_message_archive (
 owner_username text NOT NULL, chat_id uuid NOT NULL, message_id uuid NOT NULL,
 created_at timestamptz NOT NULL, payload jsonb NOT NULL,
 PRIMARY KEY(owner_username,chat_id,message_id),
 FOREIGN KEY(owner_username,chat_id) REFERENCES rag.chats(owner_username,id) ON DELETE CASCADE
);
CREATE TABLE rag.document_files (
 storage_key uuid PRIMARY KEY, content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 20971520)
);
