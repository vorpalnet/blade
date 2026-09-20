-- Numbers an agent marked. The agent desktop inserts a row; the call-blocking
-- `spam` connector reads it until expires_at passes. treatment is what happens
-- to the caller: tarpit for robocalls, review to send a harasser to the review
-- voicemail, decline for a plain 603. Spammers
-- rotate numbers, so a mark that never expires ends up blocking whoever is
-- given the number next.
--
-- Portable across Oracle, MySQL, PostgreSQL and SQL Server (use DATETIME2 for
-- TIMESTAMP on SQL Server).

CREATE TABLE spam_numbers (
    tn          VARCHAR(15)  NOT NULL PRIMARY KEY,  -- ten digits, as ${ani}
    treatment   VARCHAR(16)  DEFAULT 'tarpit' NOT NULL,
    reason      VARCHAR(64),
    marked_by   VARCHAR(64),
    marked_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP    NOT NULL
);

CREATE INDEX spam_numbers_expires ON spam_numbers (expires_at);

-- Marking a number for seven days (Oracle):
--   INSERT INTO spam_numbers (tn, treatment, reason, marked_by, expires_at)
--   VALUES ('2025550150', 'tarpit', 'robocall', 'agent-1234', CURRENT_TIMESTAMP + INTERVAL '7' DAY);
--
-- Clearing expired rows is housekeeping only; expired rows never block:
--   DELETE FROM spam_numbers WHERE expires_at < CURRENT_TIMESTAMP;
