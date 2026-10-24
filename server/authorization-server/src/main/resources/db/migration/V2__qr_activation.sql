-- Existing account codes keep their original meaning; new QR codes use device mode.
ALTER TABLE activation_codes ADD COLUMN redemption_mode VARCHAR(10) NOT NULL DEFAULT 'account'
  CHECK (redemption_mode IN ('account', 'device'));
CREATE TABLE activation_sessions (
  id VARCHAR(36) PRIMARY KEY,
  browser_token_hash VARCHAR(64) NOT NULL UNIQUE,
  poll_token_hash VARCHAR(64) NOT NULL,
  app_id VARCHAR(255) NOT NULL,
  public_key VARCHAR(512) NOT NULL,
  key_sha256 VARCHAR(64) NOT NULL,
  device_name VARCHAR(100) NOT NULL,
  expires_at BIGINT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  cancelled BOOLEAN NOT NULL DEFAULT FALSE,
  license_id VARCHAR(36) REFERENCES licenses(id),
  device_id VARCHAR(36) REFERENCES devices(id)
);
CREATE INDEX activation_sessions_expiry_idx ON activation_sessions(expires_at);
