-- BLADE Analytics schema — Oracle (12c+).
--
-- Maintained side by side with MySQL-database-schema.sql and kept in step by a
-- test, not by a generator. This file previously said it was "generated from"
-- the MySQL one; nothing generated it, and by the time anyone ran it against a
-- real Oracle database the two had drifted enough that the write path did not
-- work at all.
--
-- Oracle dialect notes:
--   * Types: BIGINT -> NUMBER(19); VARCHAR(n) -> VARCHAR2(n CHAR); TEXT -> CLOB;
--     DATETIME(3) -> TIMESTAMP (which is fractional by default, 6 digits);
--     DEFAULT CURRENT_TIMESTAMP(3) -> DEFAULT SYSTIMESTAMP.
--   * NO identity columns and NO sequences, by design. Every id is a 64-bit
--     hash of the row's natural key computed by the writer (see
--     model/NaturalKey.java). This is not merely a preference on Oracle: with
--     DB-assigned keys the provider cannot use identity columns here at all —
--     no EclipseLink Oracle platform reports native identity support, so it
--     silently substitutes one default sequence shared by every table.
--   * JSON is a CLOB with an IS JSON check rather than the native 21c+ JSON
--     type, so this script runs unchanged on a 19c Autonomous Database.
--     Swapping to the native type on 23ai is a column change, not a redesign.
--   * "One open session per correlator" uses a function-based unique index
--     (closed rows index to an all-NULL key, which Oracle does not store, so
--     only open rows are checked) in place of MySQL's VIRTUAL generated
--     open_key.
--   * There is no DROP TABLE IF EXISTS — the PL/SQL block drops each table if
--     present. An Oracle schema is a DBA-provisioned user; run as that user.

-- Drop existing objects (ignores anything not present). The list includes the
-- three tables this schema no longer has, so re-running it over an older
-- deployment cleans up rather than leaving orphans behind.
BEGIN
   FOR t IN (SELECT table_name FROM user_tables
             WHERE table_name IN
               ('ATTRIBUTES','ATTRIBUTE_NAMES','EVENTS','EVENT_TYPES',
                'SESSION_KEYS','SESSIONS','APPLICATIONS')) LOOP
      EXECUTE IMMEDIATE 'DROP TABLE '||t.table_name||' CASCADE CONSTRAINTS';
   END LOOP;
   -- SEQ_GEN_IDENTITY was the provider's own fallback sequence, needed only
   -- because the schema used to ask for identity columns. Nothing generates
   -- keys now, so it is dropped and not recreated.
   FOR s IN (SELECT sequence_name FROM user_sequences
             WHERE sequence_name = 'SEQ_GEN_IDENTITY') LOOP
      EXECUTE IMMEDIATE 'DROP SEQUENCE '||s.sequence_name;
   END LOOP;
END;
/

-- application instances — unique deployments in time
CREATE TABLE applications(
   id        NUMBER(19) NOT NULL,           -- hash of (name, domain, server, created)
   CONSTRAINT application_pk PRIMARY KEY(id),
   name      VARCHAR2(32 CHAR) NOT NULL,
   version   VARCHAR2(16 CHAR),
   host      VARCHAR2(128 CHAR),
   domain    VARCHAR2(64 CHAR),             -- weblogic cluster domain; not DNS
   server    VARCHAR2(64 CHAR),             -- weblogic server name; not hostname
   tenant    VARCHAR2(64 CHAR),             -- customer code for multi-tenant RLS; NULL = single-tenant
   created   TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
   destroyed TIMESTAMP,
   comments  CLOB,

   -- The identity these four columns describe is what `id` above hashes.
   -- Retained so a hash collision fails the insert instead of merging two
   -- application instances into one row.
   CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created)
);

-- tenant discriminator: session/event rows reach their tenant via application_id
CREATE INDEX idx_application_tenant ON applications(tenant);

CREATE TABLE sessions(
   id NUMBER(19) NOT NULL,                  -- hash of (cluster_name, vorpal_id, created)
   CONSTRAINT session_pk PRIMARY KEY(id),

   application_id NUMBER(19) NOT NULL,
   CONSTRAINT session_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id) ON DELETE CASCADE,

   -- cluster_name is the hosting-environment id, stamped by the analytics
   -- server from its service config (AnalyticsConfig.domainId). vorpal_id is
   -- the call's environment-unique X-Vorpal-ID. Together they keep a call
   -- unique when several environments share one analytics DB.
   cluster_name VARCHAR2(64 CHAR) NOT NULL,
   vorpal_id    NUMBER(19)        NOT NULL,

   -- Millisecond precision is load-bearing: this column is an input to the
   -- primary key, and it is what keeps the correlator unique over time, since
   -- the X-Vorpal-ID is 32 bits and is reused.
   created   TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
   destroyed TIMESTAMP,

   CONSTRAINT session_natural_uk UNIQUE (cluster_name, vorpal_id, created)
);

CREATE INDEX idx_session_correlator ON sessions(cluster_name, vorpal_id, destroyed);
CREATE INDEX idx_session_created ON sessions(created);
CREATE INDEX idx_session_app_created ON sessions(application_id, created);

-- one OPEN session per correlator
CREATE UNIQUE INDEX session_open_uk ON sessions(
   CASE WHEN destroyed IS NULL THEN cluster_name END,
   CASE WHEN destroyed IS NULL THEN vorpal_id    END);

-- index keys referencing a session (selectors, e.g. 'Cisco-Gucid' or 'caller')
CREATE TABLE session_keys(
   session_id NUMBER(19) NOT NULL,
   CONSTRAINT session_key_fk1 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,
   name  VARCHAR2(64 CHAR)  NOT NULL,
   value VARCHAR2(128 CHAR) NOT NULL,
   CONSTRAINT session_key_pk PRIMARY KEY(session_id, name, value)
);

CREATE INDEX idx_session_key_lookup ON session_keys(name, value);

-- events associated with a session (and/or application for sessionless events)
CREATE TABLE events(
   -- hash of event_uid: a redelivered event computes the key it already has and
   -- collides with its own row, so replay is a no-op rather than a duplicate.
   id NUMBER(19) NOT NULL,
   CONSTRAINT event_pk PRIMARY KEY(id),

   application_id NUMBER(19) NOT NULL,
   CONSTRAINT event_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id),                -- plain FK (no cascade)

   session_id     NUMBER(19),                     -- null for sessionless events
   CONSTRAINT event_fk2 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,

   -- The event name, inline. It lived in an event_types lookup table for a
   -- while; that saved a few bytes a row and cost an interning cache, a race
   -- between cluster members creating the same lookup row, and a join on every
   -- read.
   type VARCHAR2(64 CHAR) NOT NULL,

   -- The CloudEvent id this row was built from. NOT NULL, because the primary
   -- key is derived from it, and kept alongside because the derivation is one
   -- way.
   event_uid CHAR(36 CHAR) NOT NULL,
   CONSTRAINT event_uid_uk UNIQUE (event_uid),

   created TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,

   -- The event's attributes as one JSON object of name to value, replacing an
   -- attributes/attribute_names pair that cost one insert per attribute on
   -- write and a two-table join plus a cast on read.
   payload CLOB,
   CONSTRAINT event_payload_json CHECK (payload IS JSON)
);

CREATE INDEX idx_event_session ON events(session_id, created);
CREATE INDEX idx_event_type_created ON events(type, created);
CREATE INDEX idx_event_created ON events(created);

-- Attribute values arrive as strings and are stored as strings, faithful to the
-- wire. The reader's common question is numeric, so the conversion lives in an
-- index rather than in every query.
CREATE INDEX idx_event_risk ON events(JSON_VALUE(payload, '$.riskScore' RETURNING NUMBER));


-- ─────────────────────────────────────────────────────────────────────────
-- Time-based retention
-- ─────────────────────────────────────────────────────────────────────────
-- Either a scheduled time-based DELETE on events.created, or interval/range
-- partitioning on the same column (Enterprise Edition). Unlike MySQL, Oracle
-- does not require the partitioning key to appear in every unique index, so
-- partitioning here does not narrow the event_uid redelivery guarantee.
