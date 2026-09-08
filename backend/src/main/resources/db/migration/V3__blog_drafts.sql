CREATE TABLE blog_drafts (
 id VARCHAR(36) PRIMARY KEY,
 analysis_id VARCHAR(36) NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
 issue_index INTEGER NOT NULL,
 direction TEXT NOT NULL,
 status VARCHAR(30) NOT NULL,
 input_json TEXT NOT NULL,
 result_json TEXT,
 message TEXT NOT NULL,
 input_tokens BIGINT,
 output_tokens BIGINT,
 blog_url VARCHAR(300),
 remote_url VARCHAR(1000),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_drafts_analysis ON blog_drafts(analysis_id,issue_index,created_at);
