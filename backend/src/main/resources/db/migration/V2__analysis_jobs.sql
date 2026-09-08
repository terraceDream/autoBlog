CREATE TABLE analysis_jobs (
 id VARCHAR(36) PRIMARY KEY,
 topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 request_hash VARCHAR(64) NOT NULL,
 mode VARCHAR(20) NOT NULL,
 direction TEXT NOT NULL,
 status VARCHAR(30) NOT NULL,
 article_count INTEGER NOT NULL,
 input_chars INTEGER NOT NULL,
 input_json TEXT NOT NULL,
 result_json TEXT,
 input_tokens BIGINT,
 output_tokens BIGINT,
 cached_tokens BIGINT,
 message TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 finished_at VARCHAR(40)
);
CREATE INDEX idx_analysis_topic ON analysis_jobs(topic_id,created_at);
CREATE INDEX idx_analysis_cache ON analysis_jobs(topic_id,request_hash,status);
