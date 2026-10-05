-- Transcript order must not depend on UUID order when timestamps are equal.
ALTER TABLE rag.chat_message_archive ADD COLUMN position BIGINT;
WITH ordered AS (
 SELECT owner_username,chat_id,message_id,
        row_number() OVER (PARTITION BY owner_username,chat_id ORDER BY created_at,message_id)-1 AS position
 FROM rag.chat_message_archive
)
UPDATE rag.chat_message_archive a SET position=o.position
FROM ordered o WHERE a.owner_username=o.owner_username AND a.chat_id=o.chat_id AND a.message_id=o.message_id;
ALTER TABLE rag.chat_message_archive ALTER COLUMN position SET NOT NULL;
CREATE UNIQUE INDEX transcript_position ON rag.chat_message_archive(owner_username,chat_id,position);
