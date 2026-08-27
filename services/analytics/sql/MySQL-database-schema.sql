-- BLADE Analytics schema — MySQL / InnoDB.
--
-- This file and its Oracle sibling are maintained side by side and kept in step
-- by a test, not by a generator. (The Oracle file used to claim it was
-- "generated from" this one. Nothing generated it, and the two had drifted far
-- enough apart that the Oracle side could not be written to at all.)
--
-- Design notes:
--   * NO key is assigned by the database. Every id is a 64-bit hash of the
--     row's natural key, computed by the writer — see the framework's
--     v3.analytics.NaturalKey, which also explains why. The natural-key
--     UNIQUE constraints below are retained on purpose: they are what turn a
--     hash
--     collision into a failed insert instead of two calls silently becoming
--     one row.
--   * TWO KINDS OF TIMESTAMP live here, and conflating them is a bug waiting
--     to happen. `applications.created` and `sessions.created` are IDENTITY:
--     they feed the row's key and exist to disambiguate an X-Vorpal-ID that
--     is only 32 bits and gets reused. `events.created` is a FACT ABOUT TIME
--     — when that event occurred — and is what a report groups by. A
--     callStarted occurs after the session it belongs to was born, so those
--     columns legitimately differ. Never recompute a key from a stored
--     identity timestamp; read the row's id instead.
--   * created/destroyed are DATETIME (not TIMESTAMP) to dodge the 2038 limit.
--   * DATETIME(3) wherever a column takes part in a natural key. The wire
--     carries ISO-8601 instants with milliseconds, and a DATETIME(0) column
--     silently truncates them — which, now that the timestamp is an input to
--     the key, would make two nodes compute two different ids for one call.
--   * `events.application_id` is a plain FK (no cascade); event cleanup flows
--     applications -> sessions -> events, plus time-based retention.
--   * Table names are plural — `session` (singular) is a reserved word in
--     Oracle, so the whole set is pluralized for portability and consistency.
--
-- Database creation:
--     CREATE DATABASE IF NOT EXISTS vorpal;   -- or JDBC createDatabaseIfNotExist=true

DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS session_keys;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS applications;

-- application instances — unique deployments in time
CREATE TABLE applications(
   -- hash of (name, domain, server, created); never auto-assigned
   id BIGINT NOT NULL,
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
   -- a restart is a new instance, deliberately. These four columns are the
   -- identity, and the id above is their hash. Keeping the constraint means a
   -- hash collision fails loudly here rather than merging two instances.
   CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created),

   -- tenant discriminator: session/event rows reach their tenant via
   -- application_id; this index keeps the RLS predicate (tenant = :TENANT) cheap.
   INDEX idx_application_tenant (tenant)
);

CREATE TABLE sessions(
   -- hash of (cluster_name, vorpal_id, created)
   id BIGINT NOT NULL,
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

   -- Millisecond precision is load-bearing: this column is an input to the
   -- primary key, and the wire carries milliseconds. It is also what keeps the
   -- correlator unique over time — the X-Vorpal-ID is 32 bits and is reused.
   created   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),  -- real call-start time
   destroyed DATETIME(3) NULL DEFAULT NULL,                      -- NULL while open

   CONSTRAINT session_natural_uk UNIQUE (cluster_name, vorpal_id, created),

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

-- events associated with a session (and/or application for sessionless events)
CREATE TABLE events(
   -- hash of event_uid: a redelivered event computes the key it already has and
   -- collides with its own row, so replay is a no-op rather than a duplicate.
   id BIGINT NOT NULL,
   CONSTRAINT event_pk PRIMARY KEY(id),

   application_id BIGINT NOT NULL,
   CONSTRAINT event_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id),                 -- plain FK (no cascade); see header

   session_id BIGINT NULL,                        -- null for sessionless events
   CONSTRAINT event_fk2 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,

   -- The event name, inline. It lived in an event_types lookup table for a
   -- while; that saved a few bytes a row and cost an interning cache, a race
   -- between cluster members creating the same lookup row, and a join on every
   -- read. Sixty-four characters is cheaper than all three.
   type VARCHAR(64) NOT NULL,

   -- The CloudEvent id this row was built from. NOT NULL, because the primary
   -- key is derived from it. Kept alongside the key because the derivation is
   -- one way and an operator holding an id from a producer log has to be able
   -- to find the row.
   event_uid CHAR(36) NOT NULL,
   CONSTRAINT event_uid_uk UNIQUE (event_uid),

   -- DATETIME, not TIMESTAMP: the 2038 limit the header calls out applies here
   -- too, and this column was the one place it had been missed.
   created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

   -- The event's attributes as one JSON object of name to value. This replaced
   -- an attributes/attribute_names pair that cost one insert per attribute on
   -- write and a two-table join plus a string-to-number cast on read.
   payload JSON NULL,

   INDEX idx_event_session (session_id, created),
   INDEX idx_event_type_created (type, created),
   INDEX idx_event_created (created),

   -- Attribute values arrive as strings and are stored as strings, faithful to
   -- the wire. The reader's common question is numeric, so the cast lives in an
   -- index rather than in every query. MySQL 8.0.13+ for functional indexes.
   INDEX idx_event_risk ((CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.riskScore'))
                               AS DECIMAL(6,4))))
);


-- ─────────────────────────────────────────────────────────────────────────
-- Time-based retention / partitioning (operator-enabled when policy is set)
-- ─────────────────────────────────────────────────────────────────────────
-- MySQL InnoDB requires the partitioning column in every UNIQUE index, so to
-- RANGE-partition `events` by `created`, fold it into the PK and the event_uid
-- constraint, then re-create:
--
--   ALTER TABLE events
--     DROP PRIMARY KEY,
--     ADD PRIMARY KEY (id, created),
--     DROP INDEX event_uid_uk,
--     ADD UNIQUE KEY event_uid_uk (event_uid, created),
--     PARTITION BY RANGE (TO_DAYS(created)) (
--       PARTITION p_2026 VALUES LESS THAN (TO_DAYS('2027-01-01')),
--       PARTITION p_2027 VALUES LESS THAN (TO_DAYS('2028-01-01')),
--       PARTITION p_future VALUES LESS THAN MAXVALUE
--     );
--   ALTER TABLE events DROP PARTITION p_2026;   -- annual retention drop
--
-- Note what widening event_uid_uk costs: redelivery is only deduped within a
-- partition. That is acceptable because redelivery happens within minutes of
-- the original and partitions are annual, but it is a real narrowing of the
-- guarantee and should be a deliberate choice, not a surprise.
