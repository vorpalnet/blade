-- The analytics schema in HSQLDB dialect, for AnalyticsWritePathTest.
--
-- The same four tables and the same foreign keys as the shipped schemas — the
-- FKs are the point, since the test exists to catch a writer that inserts a
-- child before its parent. What is deliberately NOT reproduced is the dialect
-- machinery neither database shares: MySQL's VIRTUAL generated open_key and
-- both functional indexes on the JSON payload. Those are storage and query
-- concerns, not write-ordering ones.
--
-- SchemaAgreementTest checks this file against the entities too, so it cannot
-- drift away from the two that ship.

CREATE TABLE applications(
   id BIGINT NOT NULL,
   CONSTRAINT application_pk PRIMARY KEY(id),
   name VARCHAR(32) NOT NULL,
   version VARCHAR(16) DEFAULT NULL,
   host VARCHAR(128) DEFAULT NULL,
   domain VARCHAR(64) DEFAULT NULL,
   server VARCHAR(64) DEFAULT NULL,
   tenant VARCHAR(64) DEFAULT NULL,
   created TIMESTAMP NOT NULL,
   destroyed TIMESTAMP DEFAULT NULL,
   comments LONGVARCHAR DEFAULT NULL,
   CONSTRAINT application_natural_uk UNIQUE (name, domain, server, created)
);

CREATE TABLE sessions(
   id BIGINT NOT NULL,
   CONSTRAINT session_pk PRIMARY KEY(id),
   application_id BIGINT NOT NULL,
   CONSTRAINT session_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id) ON DELETE CASCADE,
   cluster_name VARCHAR(64) NOT NULL,
   vorpal_id BIGINT NOT NULL,
   created TIMESTAMP NOT NULL,
   destroyed TIMESTAMP DEFAULT NULL,
   CONSTRAINT session_natural_uk UNIQUE (cluster_name, vorpal_id, created)
);

CREATE TABLE session_keys(
   session_id BIGINT NOT NULL,
   CONSTRAINT session_key_fk1 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,
   name VARCHAR(64) NOT NULL,
   value VARCHAR(128) NOT NULL,
   CONSTRAINT session_key_pk PRIMARY KEY(session_id, name, value)
);

CREATE TABLE events(
   id BIGINT NOT NULL,
   CONSTRAINT event_pk PRIMARY KEY(id),
   application_id BIGINT NOT NULL,
   CONSTRAINT event_fk1 FOREIGN KEY (application_id)
      REFERENCES applications(id),
   session_id BIGINT DEFAULT NULL,
   CONSTRAINT event_fk2 FOREIGN KEY (session_id)
      REFERENCES sessions(id) ON DELETE CASCADE,
   type VARCHAR(64) NOT NULL,
   event_uid CHAR(36) NOT NULL,
   CONSTRAINT event_uid_uk UNIQUE (event_uid),
   created TIMESTAMP NOT NULL,
   payload LONGVARCHAR DEFAULT NULL
);
