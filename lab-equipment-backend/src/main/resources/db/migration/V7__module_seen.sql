CREATE TABLE module_seen (
 user_id CHAR(36) NOT NULL,
 module_key VARCHAR(32) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 seen_at DATETIME(3) NOT NULL,
 PRIMARY KEY(user_id,module_key),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
