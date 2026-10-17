-- The test schema: what a database built today has, so every migration is in it.
CREATE TABLE events (id BIGINT NOT NULL PRIMARY KEY, type VARCHAR(64), department VARCHAR(64), score INT DEFAULT 0);
CREATE INDEX idx_event_scope ON events(department);
