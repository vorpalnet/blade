-- applied-if: column events.department
-- applied-if: index events.idx_event_scope
ALTER TABLE events ADD COLUMN department VARCHAR(64);
CREATE INDEX idx_event_scope ON events(department);
