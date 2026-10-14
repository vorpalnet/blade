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

-- Accounts: jeff called Radiology (c3) and again through Sales then Radiology
-- (c6); pat called Radiology only (c3, a shared line).
CREATE TABLE v_call_keys (
   call_id VARCHAR(20), key_name VARCHAR(64), key_value VARCHAR(128), tenant VARCHAR(64),
   PRIMARY KEY (call_id, key_name, key_value));
INSERT INTO v_call_keys VALUES ('c3', 'account', 'jeff', 'uhg');
INSERT INTO v_call_keys VALUES ('c6', 'account', 'jeff', 'uhg');
INSERT INTO v_call_keys VALUES ('c3', 'account', 'pat', 'uhg');
INSERT INTO v_call_keys VALUES ('c3', 'caller', 'sip:+16465550100@carrier.example', 'uhg');
INSERT INTO v_call_keys VALUES ('c3', 'area', '312', 'uhg');
INSERT INTO v_call_keys VALUES ('c1', 'origin', '192.0.2.10', NULL);
INSERT INTO v_call_keys VALUES ('c2', 'origin', '192.0.2.11', NULL);
INSERT INTO v_call_keys VALUES ('c3', 'origin', '198.51.100.7', 'uhg');

-- Where calls were sent, and the legs servers refused: two 503s and a 408 in
-- the window, a callee's 486, a 407 challenge, and a 503 from before it.
CREATE TABLE v_call_routing (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), occurred_at TIMESTAMP, event_type VARCHAR(64),
   tenant VARCHAR(64), department VARCHAR(64), application VARCHAR(32), cluster_name VARCHAR(64),
   vorpal_id VARCHAR(32), destination VARCHAR(256), endpoint VARCHAR(256), tier VARCHAR(64),
   failovers INTEGER, status INTEGER, reason VARCHAR(128), responder VARCHAR(64), retry_after INTEGER);
INSERT INTO v_call_routing VALUES ('r1', 'c1', TIMESTAMP '2026-10-03 11:50:00', 'branchFailed', NULL, NULL, 'transfer', 'east-1', '000000C1', 'sip:+15550100@pbx.example.net', NULL, NULL, NULL, 503, 'Service Unavailable', '198.51.100.20', 30);
INSERT INTO v_call_routing VALUES ('r2', 'c2', TIMESTAMP '2026-10-03 11:55:00', 'branchFailed', NULL, NULL, 'transfer', 'east-1', '000000C2', 'sip:+15550100@pbx.example.net', NULL, NULL, NULL, 503, 'Service Unavailable', '198.51.100.20', 60);
INSERT INTO v_call_routing VALUES ('r3', 'c2', TIMESTAMP '2026-10-03 11:56:00', 'branchFailed', NULL, NULL, 'transfer', 'east-1', '000000C2', 'sip:bob@10.0.0.9', NULL, NULL, NULL, 408, 'Request Timeout', NULL, NULL);
INSERT INTO v_call_routing VALUES ('r4', 'c1', TIMESTAMP '2026-10-03 11:57:00', 'branchFailed', NULL, NULL, 'transfer', 'east-1', '000000C1', 'sip:bob@pbx.example.net', NULL, NULL, NULL, 486, 'Busy Here', '198.51.100.20', NULL);
INSERT INTO v_call_routing VALUES ('r5', 'c1', TIMESTAMP '2026-10-03 09:00:00', 'branchFailed', NULL, NULL, 'transfer', 'east-1', '000000C1', 'sip:bob@pbx.example.net', NULL, NULL, NULL, 503, 'Service Unavailable', '198.51.100.20', NULL);
INSERT INTO v_call_routing VALUES ('r7', 'c2', TIMESTAMP '2026-10-03 11:59:00', 'branchFailed', NULL, NULL, 'listener', 'east-1', '000000C2', 'sip:bob@pbx.example.net:5999', NULL, NULL, NULL, 500, 'not sent: Failed to send request', NULL, NULL);
INSERT INTO v_call_routing VALUES ('r20', 'c30', TIMESTAMP '2026-10-03 09:00:29', 'branchFailed', NULL, NULL, 'transfer', 'sample', '000000D0', 'sip:bob@10.0.0.9', NULL, NULL, NULL, 408, 'Request Timeout', NULL, NULL);
INSERT INTO v_call_routing VALUES ('r21', 'c31', TIMESTAMP '2026-10-03 09:10:29', 'branchFailed', NULL, NULL, 'transfer', 'sample', '000000D1', 'sip:bob@pbx.example.net', NULL, NULL, NULL, 486, 'Busy Here', '198.51.100.20', NULL);
INSERT INTO v_call_routing VALUES ('r22', 'c34', TIMESTAMP '2026-10-03 09:40:01', 'branchFailed', NULL, NULL, 'agent', 'sample', '000000D4', 'sip:bob@pbx.example.net', NULL, NULL, NULL, 487, 'Request Terminated', '198.51.100.20', NULL);
INSERT INTO v_call_routing VALUES ('r6', 'c1', TIMESTAMP '2026-10-03 11:58:00', 'callDeclined', NULL, NULL, 'transfer', 'east-1', '000000C1', 'sip:bob@pbx.example.net', NULL, NULL, NULL, 503, NULL, NULL, NULL);

CREATE TABLE v_events (
   event_id VARCHAR(20) PRIMARY KEY, event_type VARCHAR(64), occurred_at TIMESTAMP, event_uid VARCHAR(36),
   call_id VARCHAR(20), application_id VARCHAR(20), application VARCHAR(32), tenant VARCHAR(64),
   cluster_name VARCHAR(64), vorpal_id VARCHAR(32), department VARCHAR(64));

CREATE TABLE v_call_risk (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), assessed_at TIMESTAMP, event_type VARCHAR(64),
   tenant VARCHAR(64), application VARCHAR(32), cluster_name VARCHAR(64), vorpal_id VARCHAR(32),
   risk_score DOUBLE, risk_band VARCHAR(16), trigger_signal VARCHAR(32), suspect_streak DOUBLE,
   signal_acoustic DOUBLE, signal_signaling DOUBLE, signal_provenance DOUBLE, signal_behavior DOUBLE,
   contribution_acoustic DOUBLE, contribution_signaling DOUBLE, contribution_provenance DOUBLE,
   contribution_behavior DOUBLE, department VARCHAR(64));

CREATE TABLE v_call_risk_summary (
   call_id VARCHAR(20) PRIMARY KEY, tenant VARCHAR(64), cluster_name VARCHAR(64),
   first_assessed_at TIMESTAMP, last_assessed_at TIMESTAMP, assessments BIGINT,
   peak_risk_score DOUBLE, peak_band_rank INTEGER, peak_risk_band VARCHAR(7));

CREATE TABLE v_conversation (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), occurred_at TIMESTAMP, event_type VARCHAR(64),
   tenant VARCHAR(64), application VARCHAR(32), cluster_name VARCHAR(64), vorpal_id VARCHAR(32),
   said VARCHAR(4000), intent VARCHAR(4000), entity VARCHAR(4000), addressed VARCHAR(4000),
   caller VARCHAR(4000), destination VARCHAR(4000), department VARCHAR(64));

CREATE TABLE v_app_samples (
   event_id VARCHAR(20) PRIMARY KEY, sampled_at TIMESTAMP, application VARCHAR(32), node VARCHAR(64),
   weblogic_cluster VARCHAR(64), seconds BIGINT, published BIGINT, failures BIGINT, invites BIGINT,
   sip_errors BIGINT, restarted VARCHAR(5), active_app_sessions BIGINT, domain VARCHAR(64));

-- Samples: meetings on engine0 dropped 3 events over two minutes today and
-- restarted once; agent dropped none. One old sample, Sep 1, is outside even
-- the 30-day window.
INSERT INTO v_app_samples VALUES ('p1', TIMESTAMP '2026-10-03 10:00:00', 'meetings', 'engine0', 'BEA_ENGINE_TIER_CLUST', 60, 40, 1, 5, 0, 'false', 3, 'east-1');
INSERT INTO v_app_samples VALUES ('p2', TIMESTAMP '2026-10-03 10:01:00', 'meetings', 'engine0', 'BEA_ENGINE_TIER_CLUST', 20, 12, 2, 1, 1, 'true', 2, 'east-1');
INSERT INTO v_app_samples VALUES ('p3', TIMESTAMP '2026-10-02 09:00:00', 'agent',    'engine1', 'BEA_ENGINE_TIER_CLUST', 60, 8,  0, 2, 0, 'false', NULL, 'east-1');
INSERT INTO v_app_samples VALUES ('p4', TIMESTAMP '2026-09-01 09:00:00', 'agent',    'engine1', 'BEA_ENGINE_TIER_CLUST', 60, 99, 9, 9, 0, 'false', NULL, 'east-1');

CREATE TABLE v_server_samples (
   event_id VARCHAR(20) PRIMARY KEY, sampled_at TIMESTAMP, node VARCHAR(64), weblogic_cluster VARCHAR(64),
   state VARCHAR(32), restarted VARCHAR(5), seconds BIGINT, received BIGINT, rejected BIGINT,
   app_sessions_created BIGINT, routed BIGINT, routing_fallbacks BIGINT, undeployed_bypasses BIGINT,
   routing_cycles BIGINT, active_app_sessions BIGINT, active_sip_sessions BIGINT, heap_used_pct BIGINT,
   threads_stuck BIGINT, threads_hogging BIGINT, threads_pending BIGINT, domain VARCHAR(64), cpu_pct BIGINT);

-- engine0 refused 4 messages at 10:01 under load (CPU 82% that minute), and FSMAR routed one
-- request past an undeployed application; its app sessions stayed up
-- after the SIP sessions went (9 against 0 at the last sample: a leak).
-- AdminServer has no SIP container, so its SIP readings are absent.
INSERT INTO v_server_samples VALUES ('v1', TIMESTAMP '2026-10-03 10:00:00', 'engine0', 'BEA_ENGINE_TIER_CLUST', 'RUNNING', 'false', 60, 300, 0, 5, 100, 0, 0, 0, 12, 20, 61, 0, 1, 0, 'east-1', 35);
INSERT INTO v_server_samples VALUES ('v2', TIMESTAMP '2026-10-03 10:01:00', 'engine0', 'BEA_ENGINE_TIER_CLUST', 'RUNNING', 'false', 60, 500, 4, 6, 150, 2, 1, 0, 9, 0, 78, 1, 3, 2, 'east-1', 82);
INSERT INTO v_server_samples VALUES ('v4', TIMESTAMP '2026-10-03 11:59:00', 'engine0', 'BEA_ENGINE_TIER_CLUST', 'RUNNING', 'false', 60, 10, 0, 1, 10, 0, 0, 0, 2, 2, 44, 0, 0, 0, 'west-1', 12);
INSERT INTO v_server_samples VALUES ('v3', TIMESTAMP '2026-10-03 10:00:30', 'AdminServer', NULL, 'RUNNING', 'false', 60, 0, 0, 0, NULL, NULL, NULL, NULL, NULL, NULL, 40, 0, 0, 0, 'east-1', 4);

CREATE TABLE v_app_counters (
   row_id VARCHAR(240) PRIMARY KEY, sampled_at TIMESTAMP, application VARCHAR(32), node VARCHAR(64),
   counter VARCHAR(200), delta BIGINT, domain VARCHAR(64));
CREATE TABLE v_app_timings (
   row_id VARCHAR(240) PRIMARY KEY, sampled_at TIMESTAMP, application VARCHAR(32), node VARCHAR(64),
   timing VARCHAR(200), recordings BIGINT, sum_millis BIGINT, domain VARCHAR(64));

-- meetings counted 3 ears failures over two samples and timed 6 turns
-- averaging 1500 ms; agent counted one INVITE.
INSERT INTO v_app_counters VALUES ('p1:ears.failures', TIMESTAMP '2026-10-03 10:00:00', 'meetings', 'engine0', 'ears.failures', 1, 'east-1');
INSERT INTO v_app_counters VALUES ('p2:ears.failures', TIMESTAMP '2026-10-03 10:01:00', 'meetings', 'engine0', 'ears.failures', 2, 'east-1');
INSERT INTO v_app_counters VALUES ('p2:sip.requests.INVITE', TIMESTAMP '2026-10-03 10:01:00', 'meetings', 'engine0', 'sip.requests.INVITE', 5, 'east-1');
INSERT INTO v_app_counters VALUES ('p3:sip.requests.INVITE', TIMESTAMP '2026-10-02 09:00:00', 'agent', 'engine1', 'sip.requests.INVITE', 1, 'east-1');
INSERT INTO v_app_timings VALUES ('p1:turn.latency', TIMESTAMP '2026-10-03 10:00:00', 'meetings', 'engine0', 'turn.latency', 2, 2400, 'east-1');
INSERT INTO v_app_timings VALUES ('p2:turn.latency', TIMESTAMP '2026-10-03 10:01:00', 'meetings', 'engine0', 'turn.latency', 4, 6600, 'east-1');

CREATE TABLE v_call_disposition (
   event_id VARCHAR(20) PRIMARY KEY, call_id VARCHAR(20), dispositioned_at TIMESTAMP, tenant VARCHAR(64),
   application VARCHAR(32), cluster_name VARCHAR(64), vorpal_id VARCHAR(32), outcome VARCHAR(64),
   treatment VARCHAR(64), blocked VARCHAR(5), agent VARCHAR(64), caller_identity VARCHAR(64), action VARCHAR(64),
   reasons VARCHAR(400), face INTEGER, department VARCHAR(64));

-- Agents judged c1 (peak SUSPECT) a scam and blocked it, c2 (peak WATCH) a
-- scam, c3 (peak CLEAR) legitimate, and c6 (never scored) legitimate.
INSERT INTO v_call_disposition VALUES ('d1', 'c1', TIMESTAMP '2026-10-03 08:02:00', NULL, 'agent', 'east-1', '000000C1', 'scam', 'ended', 'true', 'reviewer1', NULL, 'block', 'pressure', 7, NULL);
INSERT INTO v_call_disposition VALUES ('d2', 'c2', TIMESTAMP '2026-10-03 08:03:00', NULL, 'agent', 'east-1', '000000C2', 'scam', 'ended', 'false', 'reviewer1', NULL, NULL, NULL, 6, NULL);
INSERT INTO v_call_disposition VALUES ('d3', 'c3', TIMESTAMP '2026-10-02 09:01:00', 'uhg', 'agent', 'east-1', '000000C3', 'legitimate', 'served', 'false', 'reviewer2', NULL, NULL, NULL, 1, 'radiology');
INSERT INTO v_call_disposition VALUES ('d4', 'c6', TIMESTAMP '2026-10-03 11:31:00', 'uhg', 'agent', 'east-1', '000000C6', 'legitimate', 'served', 'false', 'reviewer2', NULL, NULL, NULL, 2, 'radiology');

-- c1, c2: overlapping conference calls at 08:00 (peak concurrency 2).
-- c3: an agent call yesterday that published answered AND connected (counts once).
-- c4: a meetings session that never closed, Sep 29 (a leak: listed, not concurrent).
-- c5: a sample-generator call (excluded unless asked for).
-- c6: an agent call still up since 11:30 (open, and concurrent).
-- c7, c8: ONE call split in two, Sep 20 (outside the 7-day window the older
--         tests use). Same Vorpal-ID, rows born 3 s apart: listener found no
--         X-Vorpal-ID ts and keyed its own row. c7 also reached agent, which
--         published an event about it but was not the application its row names.
-- c9: an agent call yesterday that completed without ever being answered
--     (events only: no call row).
INSERT INTO v_calls VALUES ('c1', 'east-1', '000000C1', TIMESTAMP '2026-10-03 08:00:00', TIMESTAMP '2026-10-03 08:01:00', 60,  'a1', 'conference', NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c2', 'east-1', '000000C2', TIMESTAMP '2026-10-03 08:00:30', TIMESTAMP '2026-10-03 08:02:30', 120, 'a1', 'conference', NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c3', 'east-1', '000000C3', TIMESTAMP '2026-10-02 09:00:00', TIMESTAMP '2026-10-02 09:00:30', 30,  'a2', 'agent',      NULL, 'h1', 'd', 'engine1', 'uhg');
INSERT INTO v_calls VALUES ('c4', 'east-1', '000000C4', TIMESTAMP '2026-09-29 18:00:00', NULL,                             NULL, 'a3', 'meetings',   NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c5', 'sample',    '000000C5', TIMESTAMP '2026-10-03 07:00:00', TIMESTAMP '2026-10-03 07:10:00', 600, 'a9', 'gateway',    '2.9.6', 'h9', 'd', 'engine1', NULL);
INSERT INTO v_calls VALUES ('c6', 'east-1', '000000C6', TIMESTAMP '2026-10-03 11:30:00', NULL,                             NULL, 'a2', 'agent',      NULL, 'h1', 'd', 'engine1', 'uhg');
INSERT INTO v_calls VALUES ('c7', 'east-1', '000000C7', TIMESTAMP '2026-09-20 10:00:00', TIMESTAMP '2026-09-20 10:01:00', 60,  'a1', 'conference', NULL, 'h0', 'd', 'engine0', NULL);
INSERT INTO v_calls VALUES ('c8', 'east-1', '000000C7', TIMESTAMP '2026-09-20 10:00:03', TIMESTAMP '2026-09-20 10:01:00', 57,  'a4', 'listener',   NULL, 'h0', 'd', 'engine0', NULL);

INSERT INTO v_events VALUES ('e1',  'callStarted',   TIMESTAMP '2026-10-03 08:00:00', 'u1',  'c1', 'a1', 'conference', NULL, 'east-1', '000000C1', NULL);
INSERT INTO v_events VALUES ('e2',  'callStarted',   TIMESTAMP '2026-10-02 09:00:00', 'u2',  'c3', 'a2', 'agent',      'uhg', 'east-1', '000000C3', 'radiology');
INSERT INTO v_events VALUES ('e3',  'callAnswered',  TIMESTAMP '2026-10-02 09:00:01', 'u3',  'c3', 'a2', 'agent',      'uhg', 'east-1', '000000C3', 'radiology');
INSERT INTO v_events VALUES ('e4',  'callConnected', TIMESTAMP '2026-10-02 09:00:02', 'u4',  'c3', 'a2', 'agent',      'uhg', 'east-1', '000000C3', 'radiology');
INSERT INTO v_events VALUES ('e5',  'callCompleted', TIMESTAMP '2026-10-02 09:00:30', 'u5',  'c3', 'a2', 'agent',      'uhg', 'east-1', '000000C3', 'radiology');
INSERT INTO v_events VALUES ('e6',  'callStarted',   TIMESTAMP '2026-10-03 11:30:00', 'u6',  'c6', 'a2', 'agent',      'uhg', 'east-1', '000000C6', 'sales');
INSERT INTO v_events VALUES ('e7',  'callAnswered',  TIMESTAMP '2026-10-03 11:30:01', 'u7',  'c6', 'a2', 'agent',      'uhg', 'east-1', '000000C6', 'radiology');
INSERT INTO v_events VALUES ('e8',  'callStarted',   TIMESTAMP '2026-10-03 07:00:00', 'u8',  'c5', 'a9', 'gateway',    NULL, 'sample',    '000000C5', NULL);
INSERT INTO v_events VALUES ('e9',  'callAnswered',  TIMESTAMP '2026-10-03 07:00:01', 'u9',  'c5', 'a9', 'gateway',    NULL, 'sample',    '000000C5', NULL);
INSERT INTO v_events VALUES ('e10', 'start',         TIMESTAMP '2026-10-03 06:00:00', 'u10', NULL, 'a1', 'conference', NULL, NULL,        NULL, NULL);
INSERT INTO v_events VALUES ('e11', 'stop',          TIMESTAMP '2026-10-03 05:59:00', 'u11', NULL, 'a1', 'conference', NULL, NULL,        NULL, NULL);
INSERT INTO v_events VALUES ('e12', 'callStarted',   TIMESTAMP '2026-09-20 10:00:00', 'u12', 'c7', 'a1', 'conference', NULL, 'east-1', '000000C7', NULL);
INSERT INTO v_events VALUES ('e13', 'callAnswered',  TIMESTAMP '2026-09-20 10:00:01', 'u13', 'c7', 'a2', 'agent',      NULL, 'east-1', '000000C7', NULL);
INSERT INTO v_events VALUES ('e14', 'callStarted',   TIMESTAMP '2026-09-20 10:00:03', 'u14', 'c8', 'a4', 'listener',   NULL, 'east-1', '000000C7', NULL);
-- A proxy on c8: routed it and left, so it sends no lifecycle (not a gap).
INSERT INTO v_events VALUES ('e22', 'callRouted',    TIMESTAMP '2026-09-20 10:00:02', 'u22', 'c8', 'a6', 'irouter',    NULL, 'east-1', '000000C7', NULL);
INSERT INTO v_events VALUES ('e20', 'callStarted',   TIMESTAMP '2026-10-02 10:00:00', 'u20', 'c9', 'a2', 'agent',      NULL, 'east-1', '000000C9', NULL);
INSERT INTO v_events VALUES ('e21', 'callCompleted', TIMESTAMP '2026-10-02 10:01:00', 'u21', 'c9', 'a2', 'agent',      NULL, 'east-1', '000000C9', NULL);
-- c3 was offered to transfer first, which refused it: started and declined,
-- never answered, and owing no answer (not a gap).
INSERT INTO v_events VALUES ('e23', 'callStarted',   TIMESTAMP '2026-10-02 08:59:58', 'u23', 'c3', 'a7', 'transfer',   NULL, 'east-1', '000000C3', NULL);
INSERT INTO v_events VALUES ('e24', 'callDeclined',  TIMESTAMP '2026-10-02 08:59:59', 'u24', 'c3', 'a7', 'transfer',   NULL, 'east-1', '000000C3', NULL);
-- Ends judged by what came before them, on the sample cluster so no other
-- report counts them. transfer refused c30 after a 408 nobody answered (r20:
-- the container's own), so it failed, and c31 after a callee's 486 (r21), so
-- declined. agent saw c32 answered, then the caller abandon it: the answer
-- never reached them, so dropped. c33 expired with its legs out of step. c34
-- the caller simply gave up on, after a 487 the cancel itself caused.
INSERT INTO v_events VALUES ('e30', 'callDeclined',  TIMESTAMP '2026-10-03 09:00:30', 'u30', 'c30', 'a7', 'transfer', NULL, 'sample', '000000D0', NULL);
INSERT INTO v_events VALUES ('e31', 'callDeclined',  TIMESTAMP '2026-10-03 09:10:30', 'u31', 'c31', 'a7', 'transfer', NULL, 'sample', '000000D1', NULL);
INSERT INTO v_events VALUES ('e32', 'callAnswered',  TIMESTAMP '2026-10-03 09:20:00', 'u32', 'c32', 'a2', 'agent',    NULL, 'sample', '000000D2', NULL);
INSERT INTO v_events VALUES ('e33', 'callAbandoned', TIMESTAMP '2026-10-03 09:20:40', 'u33', 'c32', 'a2', 'agent',    NULL, 'sample', '000000D2', NULL);
INSERT INTO v_events VALUES ('e34', 'callDropped',   TIMESTAMP '2026-10-03 09:30:00', 'u34', 'c33', 'a2', 'agent',    NULL, 'sample', '000000D3', NULL);
INSERT INTO v_events VALUES ('e35', 'callAbandoned', TIMESTAMP '2026-10-03 09:40:00', 'u35', 'c34', 'a2', 'agent',    NULL, 'sample', '000000D4', NULL);
-- Per-application session facts: agent opened and closed c3; meetings opened
-- c4 on Sep 29 and never closed it (a leak, older than a day); agent opened c6
-- at 11:30 today and it is still up (not a leak yet).
INSERT INTO v_events VALUES ('e19', 'serverSampled',  TIMESTAMP '2026-10-03 10:00:00', 'u19', NULL, 'a5', 'blade-dashboard', NULL, NULL, NULL, NULL);

INSERT INTO v_call_risk VALUES ('r1', 'c1', TIMESTAMP '2026-10-03 08:00:10', 'callRiskAssessed', NULL, 'conference', 'east-1', '000000C1', 0.60, 'WATCH',   'ACOUSTIC', 1, 0.9, 0.5, 0.5, 0.5, 2.0, 0.1, 0.2, 0.0, NULL);
INSERT INTO v_call_risk VALUES ('r2', 'c1', TIMESTAMP '2026-10-03 08:00:40', 'callRiskFlagged',  NULL, 'conference', 'east-1', '000000C1', 0.95, 'SUSPECT', 'CONTENT',  2, 0.9, 0.8, 0.9, 0.6, 2.5, 1.4, 3.0, 0.6, NULL);
INSERT INTO v_call_risk VALUES ('r3', 'c3', TIMESTAMP '2026-10-02 09:00:10', 'callRiskAssessed', 'uhg', 'agent',      'east-1', '000000C3', 0.10, 'CLEAR',   'ACOUSTIC', 0, 0.5, 0.6, 0.5, 0.5, -1.0, 0.5, 0.2, 0.0, 'radiology');
INSERT INTO v_call_risk VALUES ('r4', 'c5', TIMESTAMP '2026-10-03 07:00:10', 'callRiskFlagged',  NULL, 'gateway',    'sample',    '000000C5', 0.99, 'SUSPECT', 'CONTENT',  3, 0.9, 0.9, 0.9, 0.9, 3.0, 3.0, 3.0, 3.0, NULL);

INSERT INTO v_call_risk_summary VALUES ('c1', NULL, 'east-1', TIMESTAMP '2026-10-03 08:00:10', TIMESTAMP '2026-10-03 08:00:40', 2, 0.95, 3, 'SUSPECT');
INSERT INTO v_call_risk_summary VALUES ('c2', NULL, 'east-1', TIMESTAMP '2026-10-03 08:00:35', TIMESTAMP '2026-10-03 08:00:35', 1, 0.70, 2, 'WATCH');
INSERT INTO v_call_risk_summary VALUES ('c3', 'uhg', 'east-1', TIMESTAMP '2026-10-02 09:00:10', TIMESTAMP '2026-10-02 09:00:10', 1, 0.10, 1, 'CLEAR');
INSERT INTO v_call_risk_summary VALUES ('c5', NULL, 'sample',    TIMESTAMP '2026-10-03 07:00:10', TIMESTAMP '2026-10-03 07:00:10', 1, 0.99, 3, 'SUSPECT');

INSERT INTO v_conversation VALUES ('s1', 'c1', TIMESTAMP '2026-10-03 08:00:05', 'callerSaid',  NULL, 'conference', 'east-1', '000000C1', 'what is the weather', 'weather', 'Chicago', 'yes', NULL, NULL, NULL);
INSERT INTO v_conversation VALUES ('s2', 'c1', TIMESTAMP '2026-10-03 08:00:20', 'callerSaid',  NULL, 'conference', 'east-1', '000000C1', 'and tomorrow',        'weather', 'tomorrow', 'yes', NULL, NULL, NULL);
INSERT INTO v_conversation VALUES ('s3', 'c3', TIMESTAMP '2026-10-02 09:00:05', 'callerSaid',  'uhg', 'agent',      'east-1', '000000C3', 'book tuesday',        'booking', 'tuesday', 'no',  NULL, NULL, 'radiology');
INSERT INTO v_conversation VALUES ('s4', 'c1', TIMESTAMP '2026-10-03 08:00:00', 'callStarted', NULL, 'conference', 'east-1', '000000C1', NULL, NULL, NULL, NULL, '+15550100', '+15550199', NULL);

CREATE TABLE v_config_changes (
   event_id VARCHAR(20) PRIMARY KEY, changed_at TIMESTAMP, change_type VARCHAR(64), config_file VARCHAR(256),
   configured_application VARCHAR(64), actor VARCHAR(128), change_kind VARCHAR(16), config_scope VARCHAR(16),
   failed VARCHAR(2000), node VARCHAR(64), domain VARCHAR(64));

-- Configuration changes: irouter.json saved and pushed just before meetings
-- restarted on east-1, the push failing on engine1; one change on west-1.
INSERT INTO v_config_changes VALUES ('g1', TIMESTAMP '2026-10-03 09:58:00', 'configSaved', 'irouter.json', NULL, 'operator1', 'saved', NULL, NULL, 'AdminServer', 'east-1');
INSERT INTO v_config_changes VALUES ('g2', TIMESTAMP '2026-10-03 09:59:00', 'configPublished', 'irouter.json', 'irouter', NULL, NULL, 'domain', '["engine1"]', 'AdminServer', 'east-1');
INSERT INTO v_config_changes VALUES ('g3', TIMESTAMP '2026-10-03 11:00:00', 'configPublished', 'gateway.json', 'gateway', NULL, NULL, 'domain', NULL, 'AdminServer', 'west-1');

CREATE TABLE v_ops_events (
   event_id VARCHAR(20) PRIMARY KEY, occurred_at TIMESTAMP, event_type VARCHAR(64), node VARCHAR(64),
   application VARCHAR(64), subject VARCHAR(256), detail VARCHAR(512), remote_kind VARCHAR(16),
   registrar VARCHAR(128), status_code BIGINT, low_waiting BIGINT, high_waiting BIGINT, waiting_now BIGINT,
   open_page VARCHAR(256), domain VARCHAR(64));

-- Operations on east-1: carrier-a down for twenty minutes this morning, the
-- flowroute trunk failing since 10:00 on engine1, two minutes the sales queue
-- held callers, and a problem the player raised two weeks ago and never
-- cleared. One endpoint down on west-1.
INSERT INTO v_ops_events VALUES ('o1', TIMESTAMP '2026-10-03 09:00:00', 'endpointDown', 'engine0', 'proxy-balancer', 'carrier-a', 'OPTIONS 408', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'east-1');
INSERT INTO v_ops_events VALUES ('o2', TIMESTAMP '2026-10-03 09:20:00', 'endpointUp', 'engine0', 'proxy-balancer', 'carrier-a', 'OPTIONS 200', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'east-1');
INSERT INTO v_ops_events VALUES ('o3', TIMESTAMP '2026-10-03 10:00:00', 'trunkFailed', 'engine1', 'gateway', 'flowroute', 'Forbidden', NULL, 'sip.flowroute.com', 403, NULL, NULL, NULL, NULL, 'east-1');
INSERT INTO v_ops_events VALUES ('o4', TIMESTAMP '2026-10-03 11:00:00', 'queueDepth', 'engine0', 'queue', 'sales', NULL, NULL, NULL, NULL, 0, 3, 2, NULL, 'east-1');
INSERT INTO v_ops_events VALUES ('o5', TIMESTAMP '2026-10-03 11:01:00', 'queueDepth', 'engine0', 'queue', 'sales', NULL, NULL, NULL, NULL, 0, 1, 0, NULL, 'east-1');
INSERT INTO v_ops_events VALUES ('o6', TIMESTAMP '2026-09-20 08:00:00', 'problemRaised', 'engine0', 'player', 'prompts', 'Prompt files missing', NULL, NULL, NULL, NULL, NULL, NULL, '/blade/configurator/', 'east-1');
INSERT INTO v_ops_events VALUES ('o7', TIMESTAMP '2026-10-03 11:30:00', 'endpointDown', 'engine0', 'proxy-balancer', 'carrier-b', 'OPTIONS 503', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'west-1');
