-- A comment with a ; in it does not cut a statement. (HSQLDB has no CREATE OR
-- REPLACE VIEW; the shipped scripts use their database's own form.)
DROP VIEW v_events IF EXISTS;
CREATE VIEW v_events AS SELECT id, type, '--not a comment' AS note FROM events;
