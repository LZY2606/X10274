PRAGMA foreign_keys = ON;

CREATE TABLE schema_version (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE branch (
  branch_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  parent_branch_id TEXT,
  forked_from_event_id TEXT
);

CREATE TABLE event (
  event_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL REFERENCES branch(branch_id),
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  parent_event_id TEXT,
  client_head_event_id TEXT,
  created_at TEXT NOT NULL,
  UNIQUE(branch_id, seq)
);

CREATE TABLE raw_instance (
  instance_id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL,
  ordinal_in_batch INTEGER NOT NULL,
  file_name TEXT NOT NULL,
  sop_class_uid TEXT,
  sop_instance_uid TEXT,
  series_instance_uid TEXT,
  study_instance_uid TEXT,
  frame_of_reference_uid TEXT,
  instance_number INTEGER,
  transfer_syntax_uid TEXT,
  transfer_syntax_supported INTEGER NOT NULL,
  media_stored INTEGER NOT NULL,
  fatal_diagnostic TEXT,
  raw_tags_json TEXT NOT NULL,
  raw_bytes BLOB NOT NULL,
  raw_bytes_hash TEXT NOT NULL,
  raw_bytes_size INTEGER NOT NULL,
  pixel_frame_count INTEGER NOT NULL,
  rows INTEGER,
  columns INTEGER
);

CREATE TABLE batch (
  batch_id TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  ordinal INTEGER NOT NULL
);

CREATE TABLE batch_member (
  batch_id TEXT NOT NULL REFERENCES batch(batch_id),
  instance_id TEXT NOT NULL REFERENCES raw_instance(instance_id),
  ordinal INTEGER NOT NULL,
  PRIMARY KEY (batch_id, instance_id)
);

CREATE INDEX idx_raw_sop ON raw_instance(sop_instance_uid);
CREATE INDEX idx_event_branch ON event(branch_id, seq);
