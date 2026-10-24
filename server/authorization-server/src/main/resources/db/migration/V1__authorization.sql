CREATE TABLE license_accounts (
  subject VARCHAR(255) PRIMARY KEY,
  last_self_unbind BIGINT
);
CREATE TABLE licenses (
  id VARCHAR(36) PRIMARY KEY,
  subject VARCHAR(255) NOT NULL UNIQUE REFERENCES license_accounts(subject),
  device_limit INTEGER NOT NULL CHECK (device_limit BETWEEN 1 AND 100),
  valid_until BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE devices (
  id VARCHAR(36) PRIMARY KEY,
  license_id VARCHAR(36) NOT NULL REFERENCES licenses(id),
  key_sha256 VARCHAR(64) NOT NULL,
  public_key VARCHAR(512) NOT NULL,
  name VARCHAR(100) NOT NULL,
  enrolled_at BIGINT NOT NULL,
  revoked_at BIGINT,
  UNIQUE(license_id, key_sha256)
);
CREATE INDEX devices_license_idx ON devices(license_id);
CREATE TABLE activation_codes (
  code_hash VARCHAR(64) PRIMARY KEY,
  device_limit INTEGER NOT NULL CHECK (device_limit BETWEEN 1 AND 100),
  valid_until BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  redeemed_by VARCHAR(255),
  redeemed_at BIGINT
);
CREATE TABLE challenges (
  id VARCHAR(36) PRIMARY KEY,
  purpose VARCHAR(10) NOT NULL,
  app_id VARCHAR(255) NOT NULL,
  public_key VARCHAR(512) NOT NULL,
  key_sha256 VARCHAR(64) NOT NULL,
  nonce VARCHAR(64) NOT NULL,
  expires_at BIGINT NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX challenges_expiry_idx ON challenges(expires_at);
CREATE TABLE audit_events (
  id VARCHAR(36) PRIMARY KEY,
  actor VARCHAR(255) NOT NULL,
  action VARCHAR(40) NOT NULL,
  target VARCHAR(255) NOT NULL,
  occurred_at BIGINT NOT NULL
);
