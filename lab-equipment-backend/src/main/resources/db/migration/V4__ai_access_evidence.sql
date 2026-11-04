CREATE TABLE ai_access(run_id CHAR(36) NOT NULL,object_type VARCHAR(24) NOT NULL,object_id CHAR(36) NOT NULL,PRIMARY KEY(run_id,object_type,object_id),FOREIGN KEY(run_id) REFERENCES ai_run(id));
