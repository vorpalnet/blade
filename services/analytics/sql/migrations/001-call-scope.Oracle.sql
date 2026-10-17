-- A call carries its tenant and department (RELEASE.md, "Analytics: a call
-- carries its tenant and department").
-- applied-if: column sessions.tenant
-- applied-if: column events.department
-- applied-if: index events.idx_event_scope
ALTER TABLE sessions ADD (tenant VARCHAR2(64 CHAR));
ALTER TABLE events ADD (tenant VARCHAR2(64 CHAR), department VARCHAR2(64 CHAR));
CREATE INDEX idx_event_scope ON events(tenant, department, created);
