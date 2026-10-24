-- Independent of entitlements: uploading diagnostics never binds or activates a device.
CREATE TABLE diagnostic_storage_lock (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO diagnostic_storage_lock(id) VALUES (1);
CREATE TABLE diagnostic_reports (
  id VARCHAR(36) PRIMARY KEY,
  app_id VARCHAR(255) NOT NULL,
  key_sha256 VARCHAR(64) NOT NULL,
  version_name VARCHAR(80) NOT NULL,
  version_code BIGINT NOT NULL,
  manufacturer VARCHAR(80) NOT NULL,
  model VARCHAR(100) NOT NULL,
  firmware VARCHAR(200) NOT NULL,
  android_release VARCHAR(40) NOT NULL,
  sdk INTEGER NOT NULL,
  truncated BOOLEAN NOT NULL,
  received_at BIGINT NOT NULL,
  size_bytes INTEGER NOT NULL,
  report_sha256 VARCHAR(64) NOT NULL,
  report TEXT NOT NULL
);
CREATE INDEX diagnostic_received_idx ON diagnostic_reports(received_at, id);
CREATE INDEX diagnostic_device_idx ON diagnostic_reports(key_sha256, received_at);
CREATE INDEX diagnostic_version_idx ON diagnostic_reports(version_code, received_at);
