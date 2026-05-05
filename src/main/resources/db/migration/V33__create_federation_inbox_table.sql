-- Create durable inbox queue for inbound ActivityPub activities.
-- Raw payloads are stored first and processed asynchronously with retries.

CREATE TABLE federation_inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Delivery / routing
    recipient_username VARCHAR(255) NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    actor_uri VARCHAR(512),
    object_uri VARCHAR(512),

    -- Raw ActivityPub payload
    payload_json JSONB NOT NULL,

    -- Processing lifecycle
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,

    -- Timestamps
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at TIMESTAMP,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_federation_inbox_status
        CHECK (status IN ('pending', 'processing', 'done', 'error'))
);

-- Indexes for claiming and operations
CREATE INDEX idx_federation_inbox_status_next_attempt
    ON federation_inbox(status, next_attempt_at);
CREATE INDEX idx_federation_inbox_recipient_status
    ON federation_inbox(recipient_username, status);
CREATE INDEX idx_federation_inbox_actor_uri
    ON federation_inbox(actor_uri);
CREATE INDEX idx_federation_inbox_object_uri
    ON federation_inbox(object_uri);
CREATE INDEX idx_federation_inbox_received_at
    ON federation_inbox(received_at DESC);

COMMENT ON TABLE federation_inbox IS 'Durable queue for inbound ActivityPub deliveries awaiting asynchronous processing';
COMMENT ON COLUMN federation_inbox.recipient_username IS 'Local username whose inbox received the ActivityPub delivery';
COMMENT ON COLUMN federation_inbox.activity_type IS 'Top-level ActivityPub activity type, e.g. Follow, Create, Like';
COMMENT ON COLUMN federation_inbox.actor_uri IS 'ActivityPub actor URI from the inbound activity';
COMMENT ON COLUMN federation_inbox.object_uri IS 'ActivityPub object URI when directly extractable from the inbound activity';
COMMENT ON COLUMN federation_inbox.payload_json IS 'Full validated inbound ActivityPub payload as JSONB';
COMMENT ON COLUMN federation_inbox.status IS 'Queue state: pending, processing, done, or error';
COMMENT ON COLUMN federation_inbox.attempt_count IS 'Number of processing attempts already performed';
COMMENT ON COLUMN federation_inbox.next_attempt_at IS 'Earliest timestamp when this entry may be retried';
COMMENT ON COLUMN federation_inbox.last_error IS 'Last processing error message, if any';
COMMENT ON COLUMN federation_inbox.received_at IS 'Timestamp when the inbound delivery was accepted into the durable inbox';
COMMENT ON COLUMN federation_inbox.processing_started_at IS 'Timestamp when the current or last processing attempt claimed the entry';
COMMENT ON COLUMN federation_inbox.processed_at IS 'Timestamp when processing completed successfully';
