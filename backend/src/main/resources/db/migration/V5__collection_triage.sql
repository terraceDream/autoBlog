ALTER TABLE topic_articles ADD COLUMN triage_json TEXT;
ALTER TABLE topic_articles ADD COLUMN triage_hash VARCHAR(64);
ALTER TABLE topic_articles ADD COLUMN triage_tier VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE topic_articles ADD COLUMN triage_score INTEGER NOT NULL DEFAULT 0;
ALTER TABLE topic_articles ADD COLUMN triage_at VARCHAR(40);
ALTER TABLE articles ADD COLUMN original_status VARCHAR(40) NOT NULL DEFAULT 'UNCHECKED';
ALTER TABLE articles ADD COLUMN original_message TEXT NOT NULL DEFAULT '';
ALTER TABLE articles ADD COLUMN original_checked_at VARCHAR(40);
ALTER TABLE articles ADD COLUMN screening_excerpt TEXT;
UPDATE articles SET screening_excerpt=excerpt;
CREATE TABLE triage_runs (
 id VARCHAR(36) PRIMARY KEY, topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 status VARCHAR(24) NOT NULL, message TEXT NOT NULL, requested INTEGER NOT NULL,
 processed INTEGER NOT NULL DEFAULT 0, input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0,
 created_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40)
);
CREATE INDEX idx_triage_priority ON topic_articles(topic_id,triage_tier,triage_score);
