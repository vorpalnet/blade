-- BLADE Analytics schema — MySQL / InnoDB.
--
-- The single source of truth for the analytics schema. Oracle / SQL Server
-- dialect scripts are regenerated from this one when needed.
--
-- Design notes:
--   * Every surrogate key is DB-assigned (AUTO_INCREMENT). The producer sends
--     facts with natural keys — (name, domain, server, created) for an
--     application, (cluster_name, vorpal_id, created) for a call — and the
--     consumer resolves them to rows. The producer used to mint the application
--     id itself, as a random 64-bit value with a stated ~1e-11 collision risk:
--     a surrogate primary key invented by the one participant with no database.
--   * created/destroyed are DATETIME (not TIMESTAMP) to dodge the 2038 limit.
--   * DATETIME(3) wherever a column takes part in a natural key. The wire carries
--     ISO-8601 instants with milliseconds; a DATETIME(0) column silently truncates
--     them, and the consumer then compares an un-truncated Date against the
--     truncated column and never matches. Every lookup would miss and insert a
--     duplicate — quietly, and at full call rate.
--   * `events.application_id` is a plain FK (no cascade); event cleanup flows
--     applications -> sessions -> events -> attributes, plus time-based retention.
--   * Table names are plural — `session` (singular) is a reserved word in Oracle,
--     so the whole set is pluralized for portability and consistency.
--
-- Database creation:
--     CREATE DATABASE IF NOT EXISTS vorpal;   -- or JDBC createDatabaseIfNotExist=true

DROP TABLE IF EXISTS attributes;
DROP TABLE IF EXISTS attribute_names;
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS event_types;
DROP TABLE IF EXISTS session_keys;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS applications;

-- application instances — unique deployments in time
CREATE TABLE applications(
   -- DB-assigned; resolved from the natural key below
   id BIGINT NOT NULL AUTO_INCREMENT,
   CONSTRAINT application_pk PRIMARY KEY(id),
   name VARCHAR(32) NOT NULL,          -- e.g. 'transfer' (no version)
   version VARCHAR(16) DEFAULT NULL,   -- e.g. '1.0.1'
   host VARCHAR(128) DEFAULT NULL,     -- e.g. engine1.replicated.vorpal.org
   domain VARCHAR(64) DEFAULT NULL,    -- weblogic cluster domain; not DNS
   server VARCHAR(64) DEFAULT NULL,    -- weblogic server name; not hostname
   tenant VARCHAR(64) DEFAULT NULL,    -- customer code for multi-tenant RLS; NULL = single-tenant
   created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
   destroyed DATETIME(3) NULL DEFAULT NULL,
   comments TEXT NULL,                 -- user-defined data (JSON)

   -- An application INSTANCE is one app, on one server, with one configuration —
   -- a restart is a new instance, deliberately. That is exactly what these four
   -- columns say, so they are the identity and the consumer resolves on them.
   -- Without this constraint two cluster members racing the same
   -- application.started would each insert their own row and every later event
   -- would attach to whichever one it happened to find.
   CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created),

   -- tenant discriminator: session/event rows reach their tenant via
   -- application_id; this index keeps the RLS predicate (tenant = :TENANT) cheap.
   INDEX idx_application_tenant (tenant)
);

CREATE TABLE sessions(
   -- DB-assigned key (producer no longer mints a session id)
   id BIGINT NOT NULL AUTO_INCREMENT,
   CONSTRAINT session_pk PRIMARY KEY(id),

   application_id BIGINT NOT NULL,
   CONSTRAINT session_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id) ON DELETE CASCADE,

   -- cluster_name is the hosting-environment id, stamped by the analytics
   -- server from its service config (AnalyticsConfig.domainId) — it
   -- differentiates domains whose WebLogic names are not unique (e.g. ten
   -- clusters all named SIPREC). vorpal_id is the call's environment-unique
   -- X-Vorpal-ID. Together they keep a call unique when several environments
   -- share one analytics DB.
   cluster_name VARCHAR(64) NOT NULL,
   vorpal_id    BIGINT      NOT NULL,

   -- Millisecond precision is load-bearing: this column is part of the natural
   -- key the consumer resolves on, and the wire carries milliseconds.
   created   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),  -- real call-start time
   destroyed DATETIME(3) NULL DEFAULT NULL,                      -- NULL while open

   INDEX idx_session_correlator (cluster_name, vorpal_id, destroyed),
   INDEX idx_session_created (created),
   INDEX idx_session_app_created (application_id, created),

   -- one OPEN session per correlator. MySQL has no filtered unique index, so a
   -- generated column holds the correlator while open and is NULL once closed
   -- (NULLs don't collide in a UNIQUE index).
   open_key VARCHAR(96) AS (CASE WHEN destroyed IS NULL
                THEN CONCAT(cluster_name, ':', vorpal_id) END) VIRTUAL,
   CONSTRAINT session_open_uk UNIQUE (open_key)
);

-- index keys referencing a session (selectors, e.g. 'Cisco-Gucid' or 'caller')
CREATE TABLE session_keys(
   session_id BIGINT NOT NULL,
   CONSTRAINT session_key_fk1 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,
   name VARCHAR(64) NOT NULL,
   value VARCHAR(128) NOT NULL,
   CONSTRAINT session_key_pk PRIMARY KEY(session_id, name, value),
   INDEX idx_session_key_lookup (name, value)
);

-- normalized event-name lookup; populated lazily by the consumer
CREATE TABLE event_types(
   id SMALLINT NOT NULL AUTO_INCREMENT,
   CONSTRAINT event_type_pk PRIMARY KEY(id),
   name VARCHAR(64) NOT NULL,
   CONSTRAINT event_type_uk UNIQUE (name)
);

-- events associated with a session (and/or application for sessionless events)
CREATE TABLE events(
   id BIGINT NOT NULL AUTO_INCREMENT,
   CONSTRAINT event_pk PRIMARY KEY(id),

   application_id BIGINT NOT NULL,
   CONSTRAINT event_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id),                 -- plain FK (no cascade); see header

   session_id BIGINT NULL,                        -- null for sessionless events
   CONSTRAINT event_fk2 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,

   event_type_id SMALLINT NOT NULL,
   CONSTRAINT event_fk3 FOREIGN KEY (event_type_id)
      REFERENCES event_types(id),

   -- The CloudEvent id, so a redelivery does not become a second row.
   -- A durable topic subscription redelivers as a matter of course — a rolling
   -- restart makes it certain — and `events` has no other natural key to
   -- collide on. Nullable, so a row written by anything that does not carry one
   -- is still accepted; NULLs do not collide in a UNIQUE index.
   event_uid CHAR(36) NULL,
   CONSTRAINT event_uid_uk UNIQUE (event_uid),

   -- DATETIME, not TIMESTAMP: the 2038 limit the header calls out applies here
   -- too, and this column was the one place it had been missed.
   created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

   INDEX idx_event_session (session_id, created),
   INDEX idx_event_type_created (event_type_id, created),
   INDEX idx_event_created (created)
);

-- normalized attribute-name lookup; populated lazily by the consumer
CREATE TABLE attribute_names(
   id SMALLINT NOT NULL AUTO_INCREMENT,
   CONSTRAINT attribute_name_pk PRIMARY KEY(id),
   name VARCHAR(64) NOT NULL,
   CONSTRAINT attribute_name_uk UNIQUE (name)
);

-- key/value attributes attached to an event
CREATE TABLE attributes(
   event_id BIGINT NOT NULL,
   CONSTRAINT attribute_fk1 FOREIGN KEY (event_id)
      REFERENCES events(id) ON DELETE CASCADE,
   attribute_name_id SMALLINT NOT NULL,
   CONSTRAINT attribute_fk2 FOREIGN KEY (attribute_name_id)
      REFERENCES attribute_names(id),
   CONSTRAINT attribute_pk PRIMARY KEY(event_id, attribute_name_id),
   -- typical SIP values are <200 chars; cap at 1024 to keep rows in-page
   value VARCHAR(1024) NOT NULL
);


-- ─────────────────────────────────────────────────────────────────────────
-- Time-based retention / partitioning (operator-enabled when policy is set)
-- ─────────────────────────────────────────────────────────────────────────
-- MySQL InnoDB requires the partitioning column in every UNIQUE index, so to
-- RANGE-partition `events` by `created`, fold it into the PK and re-create:
--
--   ALTER TABLE events
--     DROP PRIMARY KEY,
--     ADD PRIMARY KEY (id, created),
--     PARTITION BY RANGE (TO_DAYS(created)) (
--       PARTITION p_2026 VALUES LESS THAN (TO_DAYS('2027-01-01')),
--       PARTITION p_2027 VALUES LESS THAN (TO_DAYS('2028-01-01')),
--       PARTITION p_future VALUES LESS THAN MAXVALUE
--     );
--   ALTER TABLE events DROP PARTITION p_2026;   -- annual retention drop
