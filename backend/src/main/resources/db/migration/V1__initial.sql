CREATE TABLE topics (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(120) NOT NULL, description TEXT NOT NULL,
 instructions TEXT NOT NULL, keywords TEXT NOT NULL, exclusions TEXT NOT NULL, tags TEXT NOT NULL,
 language VARCHAR(12) NOT NULL, region VARCHAR(12) NOT NULL, active BOOLEAN NOT NULL,
 schedule_enabled BOOLEAN NOT NULL, cron VARCHAR(100) NOT NULL, timezone VARCHAR(80) NOT NULL,
 next_run VARCHAR(40), created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE sources (
 id VARCHAR(36) PRIMARY KEY, topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 name VARCHAR(160) NOT NULL, type VARCHAR(30) NOT NULL, media VARCHAR(20) NOT NULL,
 url VARCHAR(2048) NOT NULL, query_text VARCHAR(500) NOT NULL, channel_id VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL, last_success VARCHAR(40), created_at VARCHAR(40) NOT NULL
);
CREATE TABLE articles (
 id VARCHAR(36) PRIMARY KEY, canonical_url VARCHAR(2048) NOT NULL, url_hash VARCHAR(64) NOT NULL UNIQUE,
 title VARCHAR(2000) NOT NULL, url VARCHAR(2048) NOT NULL, media VARCHAR(20) NOT NULL,
 source_name VARCHAR(300) NOT NULL, author VARCHAR(500) NOT NULL, external_id VARCHAR(2048) NOT NULL,
 excerpt TEXT NOT NULL, coverage VARCHAR(30) NOT NULL, published_at VARCHAR(40), collected_at VARCHAR(40) NOT NULL
);
CREATE TABLE topic_articles (
 topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 article_id VARCHAR(36) NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
 matched_keywords TEXT NOT NULL, status VARCHAR(20) NOT NULL,
 PRIMARY KEY (topic_id, article_id)
);
CREATE TABLE runs (
 id VARCHAR(36) PRIMARY KEY, topic_id VARCHAR(36) NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
 trigger_type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL, started_at VARCHAR(40) NOT NULL,
 finished_at VARCHAR(40), message TEXT NOT NULL
);
CREATE TABLE run_sources (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
 source_name VARCHAR(160) NOT NULL, source_type VARCHAR(30) NOT NULL,
 status VARCHAR(20) NOT NULL, fetched INTEGER NOT NULL, added INTEGER NOT NULL,
 duplicates INTEGER NOT NULL, filtered INTEGER NOT NULL, message TEXT NOT NULL
);
CREATE INDEX idx_article_published ON articles(published_at);
CREATE INDEX idx_topic_status ON topic_articles(topic_id, status);
CREATE INDEX idx_runs_topic ON runs(topic_id, started_at);
CREATE INDEX idx_source_topic ON sources(topic_id);
