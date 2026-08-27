-- BLADE Analytics schema — Microsoft SQL Server (2016+).
--
-- Maintained side by side with MySQL-database-schema.sql and
-- Oracle-database-schema.sql, and kept in step with them and with the JPA
-- entities by SchemaAgreementTest. That test is not optional bookkeeping: the
-- Oracle script and the entities once disagreed quietly enough that the write
-- path had never worked at all.
--
-- **2016 or later is required, and the requirement is the payload column.**
-- ISJSON and JSON_VALUE arrive in 2016. On an older server there is no way to
-- constrain or index into `events.payload`, and the attribute bag would have to
-- go back to the normalized lookup tables this schema deliberately removed. Ask
-- before deploying to anything older; it is a different design, not a different
-- syntax.
--
-- Dialect notes:
--   * NO identity columns anywhere, by design. Every id is a 64-bit hash of the
--     row's natural key computed by the writer — see the framework's
--     v3.analytics.NaturalKey. This is what makes a third database cheap: the
--     provider is never asked to generate a key, so nothing here depends on
--     what it believes this platform can do.
--   * NVARCHAR rather than VARCHAR. SIP user parts are ASCII but display names
--     are not, and a caller's name reaching an attribute value is ordinary.
--     Widths below are CHARACTERS, matching the other two schemas.
--   * DATETIME2(3) for DATETIME(3)/TIMESTAMP. Millisecond precision is
--     load-bearing where a column feeds a key — see the two-timestamps note.
--   * TWO KINDS OF TIMESTAMP live here. `applications.created` and
--     `sessions.created` are IDENTITY: they feed the row's key and disambiguate
--     an X-Vorpal-ID that is only 32 bits and gets reused. `events.created` is
--     a FACT ABOUT TIME, and is what a report groups by. Never recompute a key
--     from a stored identity timestamp; read the id.
--   * "One open session per correlator" is a FILTERED unique index, which this
--     platform expresses directly. MySQL needs a generated column for it and
--     Oracle a function-based index; this is the one place SQL Server states
--     the intent plainly.
--
-- Run as the owner of the target schema. Adjust the schema name if the
-- deployment does not use dbo.

-- REQUIRED, and not optional politeness.
--
-- SQL Server refuses to create a filtered index, or an index on a computed
-- column, unless the connection has these set — and `sqlcmd` defaults
-- QUOTED_IDENTIFIER **OFF**. Both of this schema's cleverer indexes are
-- affected: the filtered `session_open_uk`, and `idx_event_risk` over the
-- computed `risk_score`. Without these two lines the script appears to
-- succeed, the tables and foreign keys all build, and you are left with a
-- database silently missing its one-open-session guard and its risk index:
--
--   Msg 1934 ... CREATE INDEX failed because the following SET options have
--   incorrect settings: 'QUOTED_IDENTIFIER'.
--
-- Set here rather than passed as a client flag (sqlcmd -I) so the script
-- behaves the same from SSMS, Azure Data Studio, or a DBA's own tooling.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

IF OBJECT_ID('dbo.events', 'U')        IS NOT NULL DROP TABLE dbo.events;
IF OBJECT_ID('dbo.session_keys', 'U')  IS NOT NULL DROP TABLE dbo.session_keys;
IF OBJECT_ID('dbo.sessions', 'U')      IS NOT NULL DROP TABLE dbo.sessions;
IF OBJECT_ID('dbo.applications', 'U')  IS NOT NULL DROP TABLE dbo.applications;
GO

-- application instances — unique deployments in time
CREATE TABLE applications(
   id BIGINT NOT NULL,                 -- hash of (name, domain, server, created)
   CONSTRAINT application_pk PRIMARY KEY(id),
   name NVARCHAR(32) NOT NULL,         -- e.g. 'transfer' (no version)
   version NVARCHAR(16) NULL,          -- e.g. '1.0.1'
   host NVARCHAR(128) NULL,            -- e.g. engine1.example_co.vorpal.net
   domain NVARCHAR(64) NULL,           -- weblogic cluster domain; not DNS
   server NVARCHAR(64) NULL,           -- weblogic server name; not hostname
   tenant NVARCHAR(64) NULL,           -- customer code for multi-tenant RLS; NULL = single-tenant
   created DATETIME2(3) NOT NULL CONSTRAINT application_created_df DEFAULT SYSUTCDATETIME(),
   destroyed DATETIME2(3) NULL,
   comments NVARCHAR(MAX) NULL,        -- user-defined data (JSON)

   -- An application INSTANCE is one app, on one server, with one configuration —
   -- a restart is a new instance, deliberately. These four columns are the
   -- identity, and the id above is their hash. Keeping the constraint means a
   -- hash collision fails loudly here rather than merging two instances.
   CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created)
);
GO

-- tenant discriminator: session/event rows reach their tenant via
-- application_id. SQL Server's row-level security predicates read this column,
-- so it is worth its own index even though it is usually NULL.
CREATE INDEX idx_application_tenant ON applications(tenant);
GO

CREATE TABLE sessions(
   id BIGINT NOT NULL,                 -- hash of (cluster_name, vorpal_id, created)
   CONSTRAINT session_pk PRIMARY KEY(id),

   application_id BIGINT NOT NULL,
   CONSTRAINT session_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id) ON DELETE CASCADE,

   -- cluster_name is the hosting-environment id, stamped by the analytics
   -- server from its service config (AnalyticsConfig.domainId) — it
   -- differentiates domains whose WebLogic names are not unique (several
   -- clusters may all be called the same thing). vorpal_id is the call's
   -- environment-unique X-Vorpal-ID. Together they keep a call unique when
   -- several environments share one analytics database.
   cluster_name NVARCHAR(64) NOT NULL,
   vorpal_id    BIGINT       NOT NULL,

   -- Millisecond precision is load-bearing: this column is an input to the
   -- primary key, and it is what keeps the correlator unique over time, since
   -- the X-Vorpal-ID is 32 bits and is reused.
   created   DATETIME2(3) NOT NULL CONSTRAINT session_created_df DEFAULT SYSUTCDATETIME(),
   destroyed DATETIME2(3) NULL,

   CONSTRAINT session_natural_uk UNIQUE (cluster_name, vorpal_id, created)
);
GO

CREATE INDEX idx_session_correlator ON sessions(cluster_name, vorpal_id, destroyed);
CREATE INDEX idx_session_created ON sessions(created);
CREATE INDEX idx_session_app_created ON sessions(application_id, created);
GO

-- One OPEN session per correlator, said directly.
--
-- A filtered index is what the other two schemas are imitating: MySQL holds the
-- correlator in a VIRTUAL generated column that is NULL once closed, and Oracle
-- indexes a CASE expression that produces an all-NULL key for closed rows.
-- Both work; neither reads as what it means.
CREATE UNIQUE INDEX session_open_uk ON sessions(cluster_name, vorpal_id)
   WHERE destroyed IS NULL;
GO

-- index keys referencing a session (selectors, e.g. a correlation header or the caller)
CREATE TABLE session_keys(
   session_id BIGINT NOT NULL,
   CONSTRAINT session_key_fk1 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,
   name  NVARCHAR(64)  NOT NULL,
   value NVARCHAR(128) NOT NULL,
   CONSTRAINT session_key_pk PRIMARY KEY(session_id, name, value)
);
GO

CREATE INDEX idx_session_key_lookup ON session_keys(name, value);
GO

-- events associated with a session (and/or application for sessionless events)
CREATE TABLE events(
   -- hash of event_uid: a redelivered event computes the key it already has and
   -- resolves to its own row, so replay is a no-op rather than a duplicate.
   id BIGINT NOT NULL,
   CONSTRAINT event_pk PRIMARY KEY(id),

   -- Plain FK, NO cascade — the same as the other two schemas.
   --
   -- It matters more here. SQL Server refuses a constraint that gives a delete
   -- more than one cascade path to the same table (Msg 1785), and an
   -- application already reaches events through sessions. If a server still
   -- rejects the combination below, drop the CASCADE on session_fk1 and let
   -- sql/retention.sql delete in order; nothing in the writer depends on
   -- cascade behaviour.
   application_id BIGINT NOT NULL,
   CONSTRAINT event_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id),

   session_id BIGINT NULL,             -- null for sessionless events
   CONSTRAINT event_fk2 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,

   -- The event name, inline. It lived in an event_types lookup table for a
   -- while; that saved a few bytes a row and cost an interning cache, a race
   -- between cluster members creating the same lookup row, and a join on every
   -- read.
   type NVARCHAR(64) NOT NULL,

   -- The CloudEvent id this row was built from. NOT NULL, because the primary
   -- key is derived from it, and kept alongside because the derivation is one
   -- way and an operator holding an id from a producer log has to be able to
   -- find the row.
   event_uid CHAR(36) NOT NULL,
   CONSTRAINT event_uid_uk UNIQUE (event_uid),

   created DATETIME2(3) NOT NULL CONSTRAINT event_created_df DEFAULT SYSUTCDATETIME(),

   -- The event's attributes as one JSON object of name to value, replacing an
   -- attributes/attribute_names pair that cost one insert per attribute on
   -- write and a two-table join plus a cast on read.
   payload NVARCHAR(MAX) NULL,
   CONSTRAINT event_payload_json CHECK (payload IS NULL OR ISJSON(payload) = 1)
);
GO

CREATE INDEX idx_event_session ON events(session_id, created);
CREATE INDEX idx_event_type_created ON events(type, created);
CREATE INDEX idx_event_created ON events(created);
GO

-- Attribute values arrive as strings and are stored as strings, faithful to the
-- wire. The reader's common question is numeric, so the conversion lives in a
-- persisted computed column rather than in every query — SQL Server cannot
-- index a JSON_VALUE expression directly, so it is materialised first.
--
-- NULL for an event that carries no risk score, which is most of them; the
-- filtered index keeps it to the rows that do.
ALTER TABLE events ADD risk_score AS
   TRY_CAST(JSON_VALUE(payload, '$.riskScore') AS DECIMAL(6,4)) PERSISTED;
GO

-- NOT filtered, though it wants to be.
--
-- `WHERE risk_score IS NOT NULL` would skip the majority of events, which
-- carry no risk score — but SQL Server refuses a filter expression that
-- references a computed column:
--
--   Msg 10609 ... Filtered index 'idx_event_risk' cannot be created on table
--   'events' because the column 'risk_score' in the filter expression is a
--   computed column.
--
-- So the index covers every row and stores a NULL for most of them. That is
-- cheap, and the alternative — filtering on `payload IS NOT NULL` instead —
-- narrows almost nothing, because nearly every event carries a payload and
-- only some carry a risk score.
CREATE INDEX idx_event_risk ON events(risk_score);
GO


-- ─────────────────────────────────────────────────────────────────────────
-- Retention
-- ─────────────────────────────────────────────────────────────────────────
-- Nothing deletes rows automatically; see sql/retention.sql. At volume the
-- answer here is a partitioned table on `created` with a sliding window, which
-- needs a partition function and scheme this script does not presume to create
-- — filegroup layout is the DBA's decision, not the application's.
