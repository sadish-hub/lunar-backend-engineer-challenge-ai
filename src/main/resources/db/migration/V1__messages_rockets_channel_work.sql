-- messages: durable accept store (P1 writes; P2 reads for apply)
CREATE TABLE messages (
    channel           UUID         NOT NULL,
    message_number    BIGINT       NOT NULL,
    message_type      TEXT         NOT NULL,
    message_time      TIMESTAMPTZ  NULL,
    payload           JSONB        NOT NULL,
    accepted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (channel, message_number)
);

-- rockets: current-state projection (P2 writes; P3 reads)
CREATE TABLE rockets (
    id                  UUID         PRIMARY KEY,
    type                TEXT         NULL,
    speed               BIGINT       NOT NULL DEFAULT 0,
    mission             TEXT         NULL,
    exploded            BOOLEAN      NOT NULL DEFAULT FALSE,
    explosion_reason    TEXT         NULL,
    last_applied_number BIGINT       NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- channel_work: claim surface for async apply (P1 upserts; P2 claims)
CREATE TABLE channel_work (
    channel       UUID         PRIMARY KEY,
    dirty_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at    TIMESTAMPTZ  NULL,
    claimed_by    TEXT         NULL
);

CREATE INDEX channel_work_dirty_idx ON channel_work (dirty_at)
    WHERE claimed_at IS NULL;
