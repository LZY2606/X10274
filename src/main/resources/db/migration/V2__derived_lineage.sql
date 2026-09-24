ALTER TABLE raw_instance ADD COLUMN media_sop_class_uid TEXT;

CREATE TABLE invalidation_report (
  report_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  trigger_event_id TEXT NOT NULL,
  report_json TEXT NOT NULL,
  seq INTEGER NOT NULL
);

CREATE TABLE counterexample_entry (
  entry_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  variant TEXT NOT NULL,
  title TEXT NOT NULL,
  accepted INTEGER NOT NULL,
  baseline_event_id TEXT,
  variant_event_id TEXT,
  report_json TEXT NOT NULL
);

CREATE TABLE client_head (
  branch_id TEXT PRIMARY KEY,
  head_event_id TEXT NOT NULL,
  head_seq INTEGER NOT NULL
);
