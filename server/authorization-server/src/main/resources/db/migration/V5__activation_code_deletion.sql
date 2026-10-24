-- Retain code fingerprints and redemption history so administrators can find deleted codes.
ALTER TABLE activation_codes ADD COLUMN deleted_at BIGINT;
