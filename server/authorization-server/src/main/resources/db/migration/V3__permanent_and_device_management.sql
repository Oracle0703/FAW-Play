-- NULL expiry means a permanent purchase, not an unlimited offline ticket.
ALTER TABLE licenses ALTER COLUMN valid_until DROP NOT NULL;
ALTER TABLE activation_codes ALTER COLUMN valid_until DROP NOT NULL;
ALTER TABLE licenses ADD COLUMN activation_limit INTEGER CHECK (activation_limit BETWEEN 1 AND 100000);
ALTER TABLE activation_codes ADD COLUMN activation_limit INTEGER CHECK (activation_limit BETWEEN 1 AND 100000);
ALTER TABLE licenses ADD COLUMN note VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE activation_codes ADD COLUMN note VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN note VARCHAR(500) NOT NULL DEFAULT '';
-- Existing licenses retain their previous allowance; no historical activation cap is imposed.
