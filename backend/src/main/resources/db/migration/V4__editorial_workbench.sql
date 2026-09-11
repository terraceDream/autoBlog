ALTER TABLE articles ADD COLUMN signals TEXT NOT NULL DEFAULT '{}';
CREATE TABLE editorial_settings (
 topic_id VARCHAR(36) PRIMARY KEY REFERENCES topics(id) ON DELETE CASCADE,
 audience VARCHAR(300) NOT NULL, automatic BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE editorial_runs (
 id VARCHAR(36) PRIMARY KEY, topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 collection_id VARCHAR(36), analysis_id VARCHAR(36), status VARCHAR(30) NOT NULL,
 message TEXT NOT NULL, selection_json TEXT, created_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40)
);
