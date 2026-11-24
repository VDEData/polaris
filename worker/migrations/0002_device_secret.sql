-- The hash of each install's own secret: only the install that registered a phone can change,
-- test or remove it. Rows from before this have none until their next registration claims them.
ALTER TABLE devices ADD COLUMN secret_hash TEXT;
