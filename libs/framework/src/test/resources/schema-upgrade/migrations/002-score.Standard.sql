-- No applied-if: setting a default twice is harmless.
ALTER TABLE events ALTER COLUMN score SET DEFAULT 0;
