-- BLADE Analytics — migrating an existing database to the CloudEvents sink.
--
-- `MySQL-database-schema.sql` is fresh-install DDL and will DROP every table.
-- This script is for a database that already has data in it.
--
-- Read the whole thing before running any of it. Sections 1-4 are mechanical and
-- safe. SECTION 5 IS NOT: it changes how `applications.id` is assigned, on a
-- table whose existing ids were produced by a random number generator, and it
-- needs a decision that is not mine to make. It is written out but commented.
--
-- Take a backup first. Every statement below is DDL; none of it rolls back.
--
--     mysqldump vorpal > vorpal-before-cloudevents.sql
--
-- ---------------------------------------------------------------------------
-- Why any of this is needed
-- ---------------------------------------------------------------------------
-- The producer used to fill in database rows — primary keys, foreign keys and
-- all — Java-serialize them, and put them on a queue of its own. It now
-- publishes CloudEvents to the shared bus and the sink resolves the keys.
-- Resolving means matching on natural keys, and matching means the column types
-- have to be able to hold what the wire carries.


-- ---------------------------------------------------------------------------
-- 1. Millisecond precision on every column that takes part in a natural key
-- ---------------------------------------------------------------------------
-- THE ONE THAT WOULD BE CATASTROPHIC AND SILENT. The sink resolves a call by
-- (cluster_name, vorpal_id, created), and the wire carries ISO-8601 instants
-- with milliseconds. A DATETIME column without fractional seconds truncates on
-- write; the lookup then compares an un-truncated value against the truncated
-- column, misses every time, and inserts a fresh session row for every event.
-- The service would look healthy and the table would fill with duplicates.
--
-- Widening DATETIME to DATETIME(3) does not lose data — existing values simply
-- gain .000 — but it does rewrite the table. On a large `sessions` it will take
-- a while and hold a lock; use pt-online-schema-change or a maintenance window.

ALTER TABLE applications
  MODIFY created   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY destroyed DATETIME(3) NULL DEFAULT NULL;

ALTER TABLE sessions
  MODIFY created   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY destroyed DATETIME(3) NULL DEFAULT NULL;

-- events.created was TIMESTAMP, which the schema header already said it should
-- not be: TIMESTAMP hits the 2038 limit and this was the one place it had been
-- missed. Converting is safe as long as the server's time_zone is the same one
-- the rows were written under, because TIMESTAMP stores UTC and DATETIME does
-- not. Check that before running it on a server whose zone has been changed.
ALTER TABLE events
  MODIFY created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);


-- ---------------------------------------------------------------------------
-- 2. Redelivery dedupe on `events`
-- ---------------------------------------------------------------------------
-- A durable subscription redelivers — on rolling restart, on failover, on
-- rollback — and `events` has no natural key to collide on, so without this a
-- restart duplicates rows and nothing ever says so. Nullable, so every existing
-- row (which has no CloudEvent id) stays valid; NULLs do not collide in a
-- UNIQUE index.

ALTER TABLE events
  ADD COLUMN event_uid CHAR(36) NULL AFTER event_type_id,
  ADD CONSTRAINT event_uid_uk UNIQUE (event_uid);


-- ---------------------------------------------------------------------------
-- 3. The application natural key
-- ---------------------------------------------------------------------------
-- An application instance is one app, on one server, with one configuration —
-- a restart is a new instance. The sink resolves on exactly that, and without
-- the constraint two cluster members racing the same application.started each
-- insert their own row and every later event attaches to whichever one it found.
--
-- CHECK FOR DUPLICATES FIRST. Existing rows were written with second-precision
-- `created`, so two instances of one app that started within the same second on
-- the same server would collide and the ALTER will fail:
--
--     SELECT name, domain, server, created, COUNT(*)
--       FROM applications
--      GROUP BY name, domain, server, created
--     HAVING COUNT(*) > 1;
--
-- If that returns rows, decide what to do with them before continuing — they
-- are almost certainly the same instance recorded twice, but merging them means
-- repointing sessions.application_id and events.application_id.

ALTER TABLE applications
  ADD CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created);


-- ---------------------------------------------------------------------------
-- 4. Nothing to do for `application.destroyed`
-- ---------------------------------------------------------------------------
-- The column was always nullable and writable; only the JPA mapping said
-- otherwise (`updatable = false`), which meant application.stopped could not be
-- recorded at all — the merge ran, reported success, and silently did not write
-- the column. Fixed in the entity, not in the schema.


-- ---------------------------------------------------------------------------
-- 5. applications.id — NEEDS A DECISION, NOT A SCRIPT
-- ---------------------------------------------------------------------------
-- New installs get `id BIGINT NOT NULL AUTO_INCREMENT`. Existing rows hold
-- producer-generated random 64-bit values, and MySQL will not start an
-- AUTO_INCREMENT sequence below the maximum value already present. If any
-- existing id is near the top of the signed range — and a uniform random long
-- usually has one that is — the sequence has nowhere to go and the first insert
-- fails.
--
--     SELECT MIN(id), MAX(id), COUNT(*) FROM applications;
--
-- Two ways out, and which one is right depends on whether the history matters:
--
--   (a) KEEP THE HISTORY, RENUMBER IT. Rewrite applications.id to a dense
--       sequence and repoint both foreign keys. Correct, and it preserves every
--       existing session and event. Requires downtime for the analytics writer
--       and careful ordering because of the FK constraints. Sketch:
--
--         CREATE TABLE applications_new LIKE applications;
--         ALTER TABLE applications_new MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
--         -- copy, letting the new table assign ids, keeping a map old -> new
--         -- update sessions.application_id and events.application_id from the map
--         -- swap the tables
--
--   (b) START CLEAN AND KEEP THE OLD ROWS READABLE. Leave the existing
--       `applications` table as-is under a new name, create the new one empty,
--       and accept that pre-migration sessions and events point at a table the
--       new writer no longer uses. Fast, reversible, and loses the join.
--
-- Until this is settled the sink cannot insert a new application row, which
-- means it cannot insert anything: every session and event carries a NOT NULL
-- foreign key to it.
--
-- Uncomment (a) or (b) once decided. Deliberately left commented.
