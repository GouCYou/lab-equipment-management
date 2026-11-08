ALTER TABLE conversation ADD COLUMN pinned_at DATETIME(3) NULL;
CREATE INDEX idx_conversation_pinned ON conversation(user_id,pinned_at,updated_at);
