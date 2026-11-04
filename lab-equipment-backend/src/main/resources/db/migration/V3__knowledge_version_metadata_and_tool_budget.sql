ALTER TABLE ai_run ADD COLUMN tool_calls INT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_version ADD COLUMN title VARCHAR(160),ADD COLUMN model VARCHAR(120),ADD COLUMN category VARCHAR(80),ADD COLUMN visibility VARCHAR(24),ADD COLUMN lab_id CHAR(36),ADD COLUMN published_at DATETIME(3);
UPDATE knowledge_version v JOIN knowledge_document d ON d.id=v.document_id SET v.title=d.title,v.model=d.model,v.category=d.category,v.visibility=d.visibility,v.lab_id=d.lab_id;
UPDATE knowledge_version SET published_at=created_at WHERE published=TRUE;
