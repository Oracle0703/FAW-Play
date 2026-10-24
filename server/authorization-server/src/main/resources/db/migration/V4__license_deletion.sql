-- Keep redemption tombstones and device history: deletion must never make an old code reusable.
ALTER TABLE licenses ADD COLUMN deleted_at BIGINT;
