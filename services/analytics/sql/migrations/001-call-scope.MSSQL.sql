-- A call carries its tenant and department (RELEASE.md, "Analytics: a call
-- carries its tenant and department").
-- applied-if: column sessions.tenant
-- applied-if: column events.department
-- applied-if: index events.idx_event_scope
ALTER TABLE sessions ADD tenant NVARCHAR(64) NULL;
ALTER TABLE events ADD tenant NVARCHAR(64) NULL, department NVARCHAR(64) NULL;
GO
CREATE INDEX idx_event_scope ON events(tenant, department, created);
GO
