PRAGMA foreign_keys = ON;

CREATE TABLE schema_version (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE import_batch (
  batch_id TEXT PRIMARY KEY,
  case_id TEXT,
  created_at TEXT NOT NULL,
  rolled_back INTEGER NOT NULL DEFAULT 0,
  note TEXT
);

CREATE TABLE source_file (
  source_id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL REFERENCES import_batch(batch_id),
  case_id TEXT,
  rel_path TEXT NOT NULL,
  media_type TEXT,
  bytes_sha256 TEXT NOT NULL,
  bytes BLOB NOT NULL,
  size_bytes INTEGER NOT NULL,
  supersedes_source_id TEXT REFERENCES source_file(source_id),
  imported_at TEXT NOT NULL
);

CREATE TABLE raw_tag (
  source_id TEXT NOT NULL REFERENCES source_file(source_id) ON DELETE CASCADE,
  tag_path TEXT NOT NULL,
  tag_name TEXT,
  vr TEXT,
  raw_repr TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'tag',
  ord INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (source_id, tag_path)
);

CREATE TABLE dicom_instance (
  subject_key TEXT PRIMARY KEY,
  first_seen_source_id TEXT NOT NULL REFERENCES source_file(source_id),
  current_source_id TEXT NOT NULL REFERENCES source_file(source_id),
  current_batch_id TEXT NOT NULL,
  is_multiframe INTEGER NOT NULL DEFAULT 0,
  n_frames INTEGER NOT NULL DEFAULT 1,
  sop_class_uid TEXT,
  transfer_syntax_uid TEXT,
  transfer_syntax_supported INTEGER,
  has_geometry INTEGER
);

CREATE TABLE instance_frame (
  frame_key TEXT PRIMARY KEY,
  subject_key TEXT NOT NULL REFERENCES dicom_instance(subject_key),
  frame_no INTEGER,
  functional_group TEXT
);

CREATE TABLE parameter_set (
  parameter_set_id TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  params_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE interpretation (
  interpretation_id TEXT PRIMARY KEY,
  rule_version TEXT NOT NULL,
  parameter_set_id TEXT NOT NULL REFERENCES parameter_set(parameter_set_id),
  batch_id TEXT NOT NULL REFERENCES import_batch(batch_id),
  created_at TEXT NOT NULL,
  trigger TEXT NOT NULL,
  status TEXT NOT NULL
);

CREATE TABLE geometric_fact (
  fact_id TEXT PRIMARY KEY,
  frame_key TEXT NOT NULL REFERENCES instance_frame(frame_key),
  subject_key TEXT NOT NULL,
  interpretation_id TEXT NOT NULL REFERENCES interpretation(interpretation_id),
  field_name TEXT NOT NULL,
  raw_value TEXT NOT NULL,
  unit TEXT,
  basis TEXT NOT NULL,
  numeric_json TEXT,
  valid INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE diagnostic (
  diagnostic_id TEXT PRIMARY KEY,
  interpretation_id TEXT NOT NULL REFERENCES interpretation(interpretation_id),
  subject_key TEXT,
  frame_key TEXT,
  code TEXT NOT NULL,
  severity TEXT NOT NULL,
  message TEXT NOT NULL,
  raw_value TEXT,
  location_tag TEXT
);

CREATE TABLE dependency (
  dependency_id TEXT PRIMARY KEY,
  interpretation_id TEXT NOT NULL REFERENCES interpretation(interpretation_id),
  dep_type TEXT NOT NULL,
  dep_key TEXT NOT NULL,
  scope_json TEXT NOT NULL
);

CREATE TABLE invalidation (
  invalidation_id TEXT PRIMARY KEY,
  old_interpretation_id TEXT NOT NULL,
  new_interpretation_id TEXT,
  reason TEXT NOT NULL,
  dep_type TEXT NOT NULL,
  dep_key TEXT NOT NULL,
  scope_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE volume_candidate (
  candidate_id TEXT PRIMARY KEY,
  interpretation_id TEXT NOT NULL REFERENCES interpretation(interpretation_id),
  volume_key TEXT NOT NULL,
  frame_of_reference_uid TEXT,
  orientation_hash TEXT,
  status TEXT NOT NULL,
  reason TEXT,
  member_count INTEGER NOT NULL,
  ordered INTEGER NOT NULL DEFAULT 0,
  geometry_json TEXT
);

CREATE TABLE candidate_member (
  candidate_id TEXT NOT NULL REFERENCES volume_candidate(candidate_id),
  ord INTEGER NOT NULL,
  frame_key TEXT NOT NULL REFERENCES instance_frame(frame_key),
  spatial_coord REAL,
  spacing_residual_mm REAL,
  raw_instance_number TEXT,
  instance_number_matches_spatial INTEGER,
  PRIMARY KEY (candidate_id, ord)
);

CREATE TABLE branch (
  branch_id TEXT PRIMARY KEY,
  parent_branch_id TEXT REFERENCES branch(branch_id),
  forked_after_event_seq INTEGER,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE event (
  event_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  branch_id TEXT NOT NULL REFERENCES branch(branch_id),
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  actor TEXT NOT NULL,
  created_at TEXT NOT NULL,
  basis_event_seq INTEGER
);

CREATE TABLE head (
  branch_id TEXT PRIMARY KEY REFERENCES branch(branch_id),
  head_event_seq INTEGER NOT NULL
);
