-- A call carries its tenant and department (RELEASE.md, "Analytics: a call
-- carries its tenant and department").
-- applied-if: column sessions.tenant
-- applied-if: column events.department
-- applied-if: index events.idx_event_scope
ALTER TABLE sessions ADD COLUMN tenant VARCHAR(64) NULL;
ALTER TABLE events ADD COLUMN tenant VARCHAR(64) NULL, ADD COLUMN department VARCHAR(64) NULL,
   ADD INDEX idx_event_scope (tenant, department, created);
