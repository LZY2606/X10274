ALTER TABLE source_file ADD COLUMN stage TEXT NOT NULL DEFAULT 'initial';
ALTER TABLE interpretation ADD COLUMN case_id TEXT;
ALTER TABLE volume_candidate ADD COLUMN accepted INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_event_branch ON event(branch_id, event_seq);
CREATE INDEX idx_fact_interp ON geometric_fact(interpretation_id);
