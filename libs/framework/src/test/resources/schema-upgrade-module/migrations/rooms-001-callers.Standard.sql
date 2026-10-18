-- A module's table, which its application used to create itself.
-- applied-if: table callers
CREATE TABLE callers (caller_key VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(200));
