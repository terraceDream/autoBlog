CREATE TABLE autopilot_runs (
 id VARCHAR(36) PRIMARY KEY, topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 blog_url VARCHAR(300) NOT NULL, status VARCHAR(24) NOT NULL, stage VARCHAR(32) NOT NULL,
 message TEXT NOT NULL, collection_id VARCHAR(36), triage_id VARCHAR(36), editorial_id VARCHAR(36),
 analysis_id VARCHAR(36), created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE autopilot_items (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL REFERENCES autopilot_runs(id) ON DELETE CASCADE,
 issue_index INTEGER NOT NULL, title VARCHAR(2000) NOT NULL, category VARCHAR(80) NOT NULL,
 UNIQUE(run_id,issue_index)
);
ALTER TABLE blog_drafts ADD COLUMN automation_item_id VARCHAR(36) UNIQUE REFERENCES autopilot_items(id);
