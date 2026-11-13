ALTER TABLE messages
    ADD COLUMN applied BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX messages_unapplied_next_idx
    ON messages (channel, message_number)
    WHERE applied = FALSE;
