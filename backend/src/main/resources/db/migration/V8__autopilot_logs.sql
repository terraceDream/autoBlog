CREATE TABLE autopilot_logs (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL REFERENCES autopilot_runs(id) ON DELETE CASCADE,
 status VARCHAR(24) NOT NULL, stage VARCHAR(32) NOT NULL, message TEXT NOT NULL, created_at VARCHAR(40) NOT NULL
);
INSERT INTO autopilot_logs(id,run_id,status,stage,message,created_at)
 SELECT id,id,status,stage,message,updated_at FROM autopilot_runs;
