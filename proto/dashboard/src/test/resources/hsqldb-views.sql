-- The reporting views, stood up as plain tables for AnalyticsReportsTest.
--
-- Same names and columns as services/analytics/sql/*-analytics-views.sql; the
-- dashboard's entities map only these, so a column renamed there and not here
-- fails the test, which is the point. Times are UTC; the test pins "now" at
-- 2026-10-03 12:00:00 (a Saturday).

CREATE TABLE v_calls (
   call_id VARCHAR(20) PRIMARY KEY, cluster_name VARCHAR(64), vorpal_id VARCHAR(32),
   started_at TIMESTAMP, ended_at TIMESTAMP, duration_seconds DOUBLE,
   application_id VARCHAR(20), application VARCHAR(32), application_version VARCHAR(16),
   host VARCHAR(128), domain VARCHAR(64), server VARCHAR(64), tenant VARCHAR(64));

CREATE TABLE v_events (
   event_id VARCHAR(20) PRIMARY KEY, event_type VARCHAR(64), occurred_at TIMESTAMP, event_uid VARCHAR(36),
   call_id VARCHAR(20), application_id VARCHAR(20), application VARCHAR(32), tenant VARCHAR(64),
   cluster_name VARCHAR(64), vorpal_id VARCHAR(32));

CREATE TABLE v_call_risk (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), assessed_at TIMESTAMP, event_type VARCHAR(64),
   tenant VARCHAR(64), application VARCHAR(32), cluster_name VARCHAR(64), vorpal_id VARCHAR(32),
   risk_score DOUBLE, risk_band VARCHAR(16), trigger_signal VARCHAR(32), suspect_streak DOUBLE,
   signal_acoustic DOUBLE, signal_signaling DOUBLE, signal_provenance DOUBLE, signal_behavior DOUBLE,
   contribution_acoustic DOUBLE, contribution_signaling DOUBLE, contribution_provenance DOUBLE,
   contribution_behavior DOUBLE);

CREATE TABLE v_call_risk_summary (
   call_id VARCHAR(20) PRIMARY KEY, tenant VARCHAR(64), cluster_name VARCHAR(64),
   first_assessed_at TIMESTAMP, last_assessed_at TIMESTAMP, assessments BIGINT,
   peak_risk_score DOUBLE, peak_band_rank INTEGER, peak_risk_band VARCHAR(7));

CREATE TABLE v_conversation (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), occurred_at TIMESTAMP, event_type VARCHAR(64),
   tenant VARCHAR(64), application VARCHAR(32), cluster_name VARCHAR(64), vorpal_id VARCHAR(32),
   said VARCHAR(4000), intent VARCHAR(4000), entity VARCHAR(4000), addressed VARCHAR(4000),
   caller VARCHAR(4000), destination VARCHAR(4000));

-- c1, c2: overlapping conference calls at 08:00 (peak concurrency 2).
-- c3: an agent call yesterday that published answered AND connected (counts once).
-- c4: a meetings session that never closed, Sep 29 (a leak: listed, not concurrent).
-- c5: a sample-generator call (excluded unless asked for).
-- c6: an agent call still up since 11:30 (open, and concurrent).
INSERT INTO v_calls VALUES ('c1', 'SIPREC-03', '000000C1', TIMESTAMP '2026-10-03 08:00:00', TIMESTAMP '2026-10-03 08:01:00', 60,  'a1', 'conference', NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c2', 'SIPREC-03', '000000C2', TIMESTAMP '2026-10-03 08:00:30', TIMESTAMP '2026-10-03 08:02:30', 120, 'a1', 'conference', NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c3', 'SIPREC-03', '000000C3', TIMESTAMP '2026-10-02 09:00:00', TIMESTAMP '2026-10-02 09:00:30', 30,  'a2', 'agent',      NULL, 'h1', 'd', 'engine1', NULL);
INSERT INTO v_calls VALUES ('c4', 'SIPREC-03', '000000C4', TIMESTAMP '2026-09-29 18:00:00', NULL,                             NULL, 'a3', 'meetings',   NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c5', 'sample',    '000000C5', TIMESTAMP '2026-10-03 07:00:00', TIMESTAMP '2026-10-03 07:10:00', 600, 'a9', 'gateway',    '2.9.6', 'h9', 'd', 'engine1', NULL);
INSERT INTO v_calls VALUES ('c6', 'SIPREC-03', '000000C6', TIMESTAMP '2026-10-03 11:30:00', NULL,                             NULL, 'a2', 'agent',      NULL, 'h1', 'd', 'engine1', NULL);

INSERT INTO v_events VALUES ('e1',  'callStarted',   TIMESTAMP '2026-10-03 08:00:00', 'u1',  'c1', 'a1', 'conference', NULL, 'SIPREC-03', '000000C1');
INSERT INTO v_events VALUES ('e2',  'callStarted',   TIMESTAMP '2026-10-02 09:00:00', 'u2',  'c3', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C3');
INSERT INTO v_events VALUES ('e3',  'callAnswered',  TIMESTAMP '2026-10-02 09:00:01', 'u3',  'c3', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C3');
INSERT INTO v_events VALUES ('e4',  'callConnected', TIMESTAMP '2026-10-02 09:00:02', 'u4',  'c3', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C3');
INSERT INTO v_events VALUES ('e5',  'callCompleted', TIMESTAMP '2026-10-02 09:00:30', 'u5',  'c3', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C3');
INSERT INTO v_events VALUES ('e6',  'callStarted',   TIMESTAMP '2026-10-03 11:30:00', 'u6',  'c6', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C6');
INSERT INTO v_events VALUES ('e7',  'callAnswered',  TIMESTAMP '2026-10-03 11:30:01', 'u7',  'c6', 'a2', 'agent',      NULL, 'SIPREC-03', '000000C6');
INSERT INTO v_events VALUES ('e8',  'callStarted',   TIMESTAMP '2026-10-03 07:00:00', 'u8',  'c5', 'a9', 'gateway',    NULL, 'sample',    '000000C5');
INSERT INTO v_events VALUES ('e9',  'callAnswered',  TIMESTAMP '2026-10-03 07:00:01', 'u9',  'c5', 'a9', 'gateway',    NULL, 'sample',    '000000C5');
INSERT INTO v_events VALUES ('e10', 'start',         TIMESTAMP '2026-10-03 06:00:00', 'u10', NULL, 'a1', 'conference', NULL, NULL,        NULL);
INSERT INTO v_events VALUES ('e11', 'stop',          TIMESTAMP '2026-10-03 05:59:00', 'u11', NULL, 'a1', 'conference', NULL, NULL,        NULL);

INSERT INTO v_call_risk VALUES ('r1', 'c1', TIMESTAMP '2026-10-03 08:00:10', 'callRiskAssessed', NULL, 'conference', 'SIPREC-03', '000000C1', 0.60, 'WATCH',   'ACOUSTIC', 1, 0.9, 0.5, 0.5, 0.5, 2.0, 0.1, 0.2, 0.0);
INSERT INTO v_call_risk VALUES ('r2', 'c1', TIMESTAMP '2026-10-03 08:00:40', 'callRiskFlagged',  NULL, 'conference', 'SIPREC-03', '000000C1', 0.95, 'SUSPECT', 'CONTENT',  2, 0.9, 0.8, 0.9, 0.6, 2.5, 1.4, 3.0, 0.6);
INSERT INTO v_call_risk VALUES ('r3', 'c3', TIMESTAMP '2026-10-02 09:00:10', 'callRiskAssessed', NULL, 'agent',      'SIPREC-03', '000000C3', 0.10, 'CLEAR',   'ACOUSTIC', 0, 0.5, 0.6, 0.5, 0.5, -1.0, 0.5, 0.2, 0.0);
INSERT INTO v_call_risk VALUES ('r4', 'c5', TIMESTAMP '2026-10-03 07:00:10', 'callRiskFlagged',  NULL, 'gateway',    'sample',    '000000C5', 0.99, 'SUSPECT', 'CONTENT',  3, 0.9, 0.9, 0.9, 0.9, 3.0, 3.0, 3.0, 3.0);

INSERT INTO v_call_risk_summary VALUES ('c1', NULL, 'SIPREC-03', TIMESTAMP '2026-10-03 08:00:10', TIMESTAMP '2026-10-03 08:00:40', 2, 0.95, 3, 'SUSPECT');
INSERT INTO v_call_risk_summary VALUES ('c2', NULL, 'SIPREC-03', TIMESTAMP '2026-10-03 08:00:35', TIMESTAMP '2026-10-03 08:00:35', 1, 0.70, 2, 'WATCH');
INSERT INTO v_call_risk_summary VALUES ('c3', NULL, 'SIPREC-03', TIMESTAMP '2026-10-02 09:00:10', TIMESTAMP '2026-10-02 09:00:10', 1, 0.10, 1, 'CLEAR');
INSERT INTO v_call_risk_summary VALUES ('c5', NULL, 'sample',    TIMESTAMP '2026-10-03 07:00:10', TIMESTAMP '2026-10-03 07:00:10', 1, 0.99, 3, 'SUSPECT');

INSERT INTO v_conversation VALUES ('s1', 'c1', TIMESTAMP '2026-10-03 08:00:05', 'callerSaid',  NULL, 'conference', 'SIPREC-03', '000000C1', 'what is the weather', 'weather', 'Chicago', 'yes', NULL, NULL);
INSERT INTO v_conversation VALUES ('s2', 'c1', TIMESTAMP '2026-10-03 08:00:20', 'callerSaid',  NULL, 'conference', 'SIPREC-03', '000000C1', 'and tomorrow',        'weather', 'tomorrow', 'yes', NULL, NULL);
INSERT INTO v_conversation VALUES ('s3', 'c3', TIMESTAMP '2026-10-02 09:00:05', 'callerSaid',  NULL, 'agent',      'SIPREC-03', '000000C3', 'book tuesday',        'booking', 'tuesday', 'no',  NULL, NULL);
INSERT INTO v_conversation VALUES ('s4', 'c1', TIMESTAMP '2026-10-03 08:00:00', 'callStarted', NULL, 'conference', 'SIPREC-03', '000000C1', NULL, NULL, NULL, NULL, '+15550100', '+15550199');
