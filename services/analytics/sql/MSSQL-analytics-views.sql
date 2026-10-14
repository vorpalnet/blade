-- BLADE Analytics — the reporting surface, Microsoft SQL Server.
--
-- The Oracle file (`Oracle-analytics-views.sql`) carries the full reasoning for
-- why these views exist and why every identifier is text. The short version,
-- because it is the part that bites silently:
--
--   The keys are 63-bit. A BI tool reads a BIGINT column into a double, which
--   carries 53 bits of mantissa, so above 2^53 the low digits are lost: call
--   1414214647537770644 arrives as ...770800. It still looks like a valid
--   19-digit id, so a join between two datasets on `call_id` matches rounded
--   values and nothing anywhere reports an error.
--
-- So identifiers are CAST to VARCHAR here, and `vorpal_id` is rendered as the 8
-- hex digits an operator reads off a log line or a SIP header rather than the
-- decimal nobody can search with.
--
-- Point a BI tool at these, never at the tables: the views are a contract, the
-- tables are an implementation, and the last schema change killed every dataset
-- built directly on the tables.
--
-- ── SET options are part of the script, not the session ────────────────────
--
-- SQL Server refuses to create a view that touches a computed column or a JSON
-- function unless QUOTED_IDENTIFIER is ON, and sqlcmd defaults it OFF. Leaving
-- these to the caller fails with Msg 1934 at deploy time, or — worse on a
-- client that defaults them ON — succeeds here and diverges from the schema
-- script. Both files therefore state them.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- Run as the schema owner, after the table script.

CREATE OR ALTER VIEW v_calls AS
SELECT
   CAST(s.id AS VARCHAR(20))                  AS call_id,
   s.cluster_name                             AS cluster_name,
   -- CONVERT style 2 renders the binary without a 0x prefix; the value is
   -- widened to 8 bytes first so a correlator above INT range cannot overflow,
   -- and the rightmost 8 characters are the 32-bit id the wire carries.
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8)
                                              AS vorpal_id,
   s.created                                  AS started_at,
   s.destroyed                                AS ended_at,
   -- NULL while the call is still up, which is what a "live calls" tile filters
   -- on. DATEDIFF returns whole units, so SECOND is the grain a BI tool
   -- averages.
   CASE WHEN s.destroyed IS NOT NULL
        THEN DATEDIFF(SECOND, s.created, s.destroyed) END
                                              AS duration_seconds,
   CAST(a.id AS VARCHAR(20))                  AS application_id,
   a.name                                     AS application,
   a.version                                  AS application_version,
   a.host                                     AS host,
   a.domain                                   AS domain,
   a.server                                   AS server,
   COALESCE(s.tenant, a.tenant) AS tenant
FROM sessions s
JOIN applications a ON a.id = s.application_id;
GO

-- One row per recorded fact, with the attributes every event carries.
--
-- `payload` is exposed as well as flattened, so an analyst chasing something
-- the typed views do not cover can still read the document without waiting for
-- a new view to be deployed.
CREATE OR ALTER VIEW v_events AS
SELECT
   CAST(e.id AS VARCHAR(20))                  AS event_id,
   e.type                                     AS event_type,
   e.created                                  AS occurred_at,
   e.event_uid                                AS event_uid,
   CAST(e.session_id AS VARCHAR(20))          AS call_id,
   CAST(e.application_id AS VARCHAR(20))      AS application_id,
   a.name                                     AS application,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   s.cluster_name                             AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8)
                                              AS vorpal_id,
   e.payload                                  AS payload
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id;
GO

-- The fused call-risk verdict, typed.
--
-- Note the quoted JSON paths. The attribute names contain dots —
-- `signal.acoustic` is one flat key, not a nested object — so
-- `$.signal.acoustic` would read it as a path into a structure that does not
-- exist and return nothing. `$."signal.acoustic"` is correct and the difference
-- is silent.
--
-- TRY_CAST rather than CAST throughout: values are numbers, or numeric strings
-- in rows written before the flat payload, and one malformed publisher must
-- not fail the whole query for every other call. A bad value becomes NULL and is visible as a gap.
CREATE OR ALTER VIEW v_call_risk AS
SELECT
   CAST(e.id AS VARCHAR(20))                  AS event_id,
   CAST(e.session_id AS VARCHAR(20))          AS call_id,
   e.created                                  AS assessed_at,
   e.type                                     AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name                                     AS application,
   s.cluster_name                             AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8)
                                              AS vorpal_id,
   TRY_CAST(JSON_VALUE(e.payload, '$."riskScore"')     AS DECIMAL(6,4)) AS risk_score,
   JSON_VALUE(e.payload, '$."riskBand"')                                AS risk_band,
   JSON_VALUE(e.payload, '$."triggerSignal"')                           AS trigger_signal,
   TRY_CAST(JSON_VALUE(e.payload, '$."suspectStreak"') AS INT)          AS suspect_streak,
   TRY_CAST(JSON_VALUE(e.payload, '$."signal.acoustic"')   AS DECIMAL(6,4)) AS signal_acoustic,
   TRY_CAST(JSON_VALUE(e.payload, '$."signal.signaling"')  AS DECIMAL(6,4)) AS signal_signaling,
   TRY_CAST(JSON_VALUE(e.payload, '$."signal.provenance"') AS DECIMAL(6,4)) AS signal_provenance,
   TRY_CAST(JSON_VALUE(e.payload, '$."signal.behavior"')   AS DECIMAL(6,4)) AS signal_behavior,
   -- The contributions are the defensibility argument made queryable: how many
   -- log-odds each independent signal added. A score alone invites "says who?".
   TRY_CAST(JSON_VALUE(e.payload, '$."contribution.acoustic"')   AS DECIMAL(8,4)) AS contribution_acoustic,
   TRY_CAST(JSON_VALUE(e.payload, '$."contribution.signaling"')  AS DECIMAL(8,4)) AS contribution_signaling,
   TRY_CAST(JSON_VALUE(e.payload, '$."contribution.provenance"') AS DECIMAL(8,4)) AS contribution_provenance,
   TRY_CAST(JSON_VALUE(e.payload, '$."contribution.behavior"')   AS DECIMAL(8,4)) AS contribution_behavior
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callRiskAssessed', 'callRiskFlagged');
GO

-- Call-level roll-up of risk: the worst assessment each call ever reached.
--
-- The per-event view above is the detail; this is what a "calls at risk" list is
-- built on, one row per call rather than one per scored window.
CREATE OR ALTER VIEW v_call_risk_summary AS
SELECT
   r.call_id           AS call_id,
   r.tenant            AS tenant,
   r.cluster_name      AS cluster_name,
   MIN(r.assessed_at)  AS first_assessed_at,
   MAX(r.assessed_at)  AS last_assessed_at,
   COUNT(*)            AS assessments,
   MAX(r.risk_score)   AS peak_risk_score,
   MAX(CASE r.risk_band WHEN 'SUSPECT' THEN 3 WHEN 'WATCH' THEN 2 WHEN 'CLEAR' THEN 1 END)
                       AS peak_band_rank,
   -- The rank above sorts correctly; this is the label to display beside it.
   CASE MAX(CASE r.risk_band WHEN 'SUSPECT' THEN 3 WHEN 'WATCH' THEN 2 WHEN 'CLEAR' THEN 1 END)
      WHEN 3 THEN 'SUSPECT' WHEN 2 THEN 'WATCH' WHEN 1 THEN 'CLEAR' END
                       AS peak_risk_band
FROM v_call_risk r
GROUP BY r.call_id, r.tenant, r.cluster_name;
GO

-- What the caller said and what the system made of it, typed.
--
-- The conversational counterpart to v_call_risk: one row per classified
-- utterance. `addressed` is the system's judgement about whether an utterance
-- was meant for an agent and which one.
CREATE OR ALTER VIEW v_conversation AS
SELECT
   CAST(e.id AS VARCHAR(20))                  AS event_id,
   CAST(e.session_id AS VARCHAR(20))          AS call_id,
   e.created                                  AS occurred_at,
   e.type                                     AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name                                     AS application,
   s.cluster_name                             AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8)
                                              AS vorpal_id,
   JSON_VALUE(e.payload, '$."text"')          AS said,
   JSON_VALUE(e.payload, '$."intent"')        AS intent,
   JSON_VALUE(e.payload, '$."entity"')        AS entity,
   JSON_VALUE(e.payload, '$."addressed"')     AS addressed,
   JSON_VALUE(e.payload, '$."caller"')        AS caller,
   JSON_VALUE(e.payload, '$."destination"')   AS destination
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callerSaid', 'callStarted');
GO

-- Session correlators, for looking a call up by something a human has: a
-- Cisco-GUCID, a caller number, whatever selector the domain configured.
CREATE OR ALTER VIEW v_call_keys AS
SELECT
   CAST(k.session_id AS VARCHAR(20))          AS call_id,
   k.name                                     AS key_name,
   k.value                                    AS key_value,
   COALESCE(s.tenant, a.tenant) AS tenant
FROM session_keys k
JOIN sessions s     ON s.id = k.session_id
JOIN applications a ON a.id = s.application_id;
GO

-- Queueing, one row per fact: a caller joining a queue, being offered to the
-- destination (an offer that failed retryably counts up `attempt`), or leaving
-- without being connected (`reason` caller or refused). `depth` is the queue's
-- length with the caller in it.
CREATE OR ALTER VIEW v_queue AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   CAST(e.session_id AS VARCHAR(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8) AS vorpal_id,
   JSON_VALUE(e.payload, '$."queue"') AS queue,
   TRY_CAST(JSON_VALUE(e.payload, '$."depth"') AS INT) AS depth,
   TRY_CAST(JSON_VALUE(e.payload, '$."waitedMs"') AS DECIMAL(18,3)) / 1000 AS waited_seconds,
   TRY_CAST(JSON_VALUE(e.payload, '$."attempt"') AS INT) AS attempt,
   JSON_VALUE(e.payload, '$."reason"') AS reason
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('queueEntered', 'queueReleased', 'queueAbandoned');
GO

-- Where each call was sent. `callRouted` is a proxy's choice (iRouter's
-- destination, or the balancer's endpoint and tier after `failovers` tiers
-- failed); `callResponded` and `callDeclined` are answers the router or the
-- balancer gave itself, with the `status` it sent; `branchFailed` is a server
-- the call was sent to answering that leg with a failure (`status`, `reason`,
-- the `responder` address, and `retry_after` from a 503's Retry-After).
CREATE OR ALTER VIEW v_call_routing AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   CAST(e.session_id AS VARCHAR(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8) AS vorpal_id,
   JSON_VALUE(e.payload, '$."destination"') AS destination,
   JSON_VALUE(e.payload, '$."endpoint"') AS endpoint,
   JSON_VALUE(e.payload, '$."tier"') AS tier,
   TRY_CAST(JSON_VALUE(e.payload, '$."failovers"') AS INT) AS failovers,
   TRY_CAST(JSON_VALUE(e.payload, '$."status"') AS INT) AS status,
   JSON_VALUE(e.payload, '$."reason"') AS reason,
   JSON_VALUE(e.payload, '$."responder"') AS responder,
   TRY_CAST(JSON_VALUE(e.payload, '$."retryAfter"') AS INT) AS retry_after
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callRouted', 'callResponded', 'callDeclined', 'branchFailed');
GO

-- Parked calls: one row when a call is put on hold, one when it leaves, with
-- how long it was held.
CREATE OR ALTER VIEW v_call_hold AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   CAST(e.session_id AS VARCHAR(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8) AS vorpal_id,
   TRY_CAST(JSON_VALUE(e.payload, '$."heldMs"') AS DECIMAL(18,3)) / 1000 AS held_seconds
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callHeld', 'callHoldEnded');
GO

-- Calls an application placed on someone's behalf (third-party call
-- control): who was called and how they answered.
CREATE OR ALTER VIEW v_call_origination AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   CAST(e.session_id AS VARCHAR(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8) AS vorpal_id,
   JSON_VALUE(e.payload, '$."party"') AS party,
   TRY_CAST(JSON_VALUE(e.payload, '$."status"') AS INT) AS status,
   JSON_VALUE(e.payload, '$."answered"') AS answered
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callOriginated');
GO

-- Queue wait per call: what a call-center report asks first. One row per
-- call per queue: when the caller joined, the longest wait recorded, how many
-- offers it took, and how it ended. `outcome` is caller (hung up), refused
-- (the destination refused for good), released (offered and not abandoned),
-- or waiting (still queued, or the node went away before it said).
CREATE OR ALTER VIEW v_queue_wait AS
SELECT
   q.call_id AS call_id,
   q.tenant AS tenant,
   q.cluster_name AS cluster_name,
   q.queue AS queue,
   MIN(CASE WHEN q.event_type = 'queueEntered' THEN q.occurred_at END) AS entered_at,
   MAX(q.waited_seconds) AS wait_seconds,
   MAX(q.attempt) AS offers,
   COALESCE(MAX(CASE WHEN q.event_type = 'queueAbandoned' THEN q.reason END),
            CASE WHEN MAX(q.attempt) IS NOT NULL THEN 'released' ELSE 'waiting' END) AS outcome
FROM v_queue q
GROUP BY q.call_id, q.tenant, q.cluster_name, q.queue;
GO

-- What each application did, per node, minute by minute: events published,
-- publishes the bus refused (each one a dropped event), INVITEs received and
-- SIP errors. The dashboard reads every application's metrics MBean and
-- publishes the difference since its last look; the MBean itself resets on
-- every deploy, so these rows are the only history of it. `restarted` says
-- the application restarted within the sample and its counts run from then.
CREATE OR ALTER VIEW v_app_samples AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   JSON_VALUE(e.payload, '$."cluster"') AS weblogic_cluster,
   TRY_CAST(JSON_VALUE(e.payload, '$."seconds"') AS BIGINT) AS seconds,
   TRY_CAST(JSON_VALUE(e.payload, '$."published"') AS BIGINT) AS published,
   TRY_CAST(JSON_VALUE(e.payload, '$."failures"') AS BIGINT) AS failures,
   TRY_CAST(JSON_VALUE(e.payload, '$."invites"') AS BIGINT) AS invites,
   TRY_CAST(JSON_VALUE(e.payload, '$."sipErrors"') AS BIGINT) AS sip_errors,
   JSON_VALUE(e.payload, '$."restarted"') AS restarted,
   TRY_CAST(JSON_VALUE(e.payload, '$."activeAppSessions"') AS BIGINT) AS active_app_sessions,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type = 'applicationSampled';
GO

-- How each server stood, minute by minute: SIP messages its container
-- received and rejected, application sessions created, and at each sample its
-- live sessions, heap and threads. `rejected` is the only record of a message
-- the container refused under overload: it never reached an application.
-- Published by the dashboard; readings a server lacks (the AdminServer runs
-- no SIP container) are absent, not zero.
CREATE OR ALTER VIEW v_server_samples AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."node"') AS node,
   JSON_VALUE(e.payload, '$."cluster"') AS weblogic_cluster,
   JSON_VALUE(e.payload, '$."state"') AS state,
   JSON_VALUE(e.payload, '$."restarted"') AS restarted,
   TRY_CAST(JSON_VALUE(e.payload, '$."seconds"') AS BIGINT) AS seconds,
   TRY_CAST(JSON_VALUE(e.payload, '$."received"') AS BIGINT) AS received,
   TRY_CAST(JSON_VALUE(e.payload, '$."rejected"') AS BIGINT) AS rejected,
   TRY_CAST(JSON_VALUE(e.payload, '$."appSessionsCreated"') AS BIGINT) AS app_sessions_created,
   TRY_CAST(JSON_VALUE(e.payload, '$."routed"') AS BIGINT) AS routed,
   TRY_CAST(JSON_VALUE(e.payload, '$."routingFallbacks"') AS BIGINT) AS routing_fallbacks,
   TRY_CAST(JSON_VALUE(e.payload, '$."undeployedBypasses"') AS BIGINT) AS undeployed_bypasses,
   TRY_CAST(JSON_VALUE(e.payload, '$."routingCycles"') AS BIGINT) AS routing_cycles,
   TRY_CAST(JSON_VALUE(e.payload, '$."activeAppSessions"') AS BIGINT) AS active_app_sessions,
   TRY_CAST(JSON_VALUE(e.payload, '$."activeSipSessions"') AS BIGINT) AS active_sip_sessions,
   TRY_CAST(JSON_VALUE(e.payload, '$."heapUsedPct"') AS BIGINT) AS heap_used_pct,
   TRY_CAST(JSON_VALUE(e.payload, '$."cpuPct"') AS BIGINT) AS cpu_pct,
   TRY_CAST(JSON_VALUE(e.payload, '$."threadsStuck"') AS BIGINT) AS threads_stuck,
   TRY_CAST(JSON_VALUE(e.payload, '$."threadsHogging"') AS BIGINT) AS threads_hogging,
   TRY_CAST(JSON_VALUE(e.payload, '$."threadsPending"') AS BIGINT) AS threads_pending,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type = 'serverSampled';
GO

-- Every counter an application declares, one row per counter that moved in
-- a sample: the history of what an application counts about itself (a speech
-- service that did not answer, a media server that refused), kept past the
-- redeploy that resets the counter. A labelled counter is `name.value`.
CREATE OR ALTER VIEW v_app_counters AS
SELECT
   CONCAT(e.id, ':', c.counter_name) AS row_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   c.counter_name AS counter,
   c.delta AS delta,
   (SELECT a.domain FROM applications a WHERE a.id = e.application_id) AS domain
FROM events e
CROSS APPLY OPENJSON(e.payload, '$.counters') WITH (
   counter_name NVARCHAR(200) '$.name',
   delta BIGINT '$.delta') c
WHERE e.type = 'applicationSampled';
GO

-- Every timing an application records, one row per timing per sample: how
-- many recordings and their summed milliseconds, so a mean over any window is
-- SUM(sum_millis) / SUM(recordings).
CREATE OR ALTER VIEW v_app_timings AS
SELECT
   CONCAT(e.id, ':', t.timing_name) AS row_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   t.timing_name AS timing,
   t.recordings AS recordings,
   t.sum_millis AS sum_millis,
   (SELECT a.domain FROM applications a WHERE a.id = e.application_id) AS domain
FROM events e
CROSS APPLY OPENJSON(e.payload, '$.timings') WITH (
   timing_name NVARCHAR(200) '$.name',
   recordings BIGINT '$.count',
   sum_millis BIGINT '$.sumMillis') t
WHERE e.type = 'applicationSampled';
GO

-- An agent's verdict on a call, as filed from the Agent Console: the outcome
-- (legitimate, scam, ...), the treatment, whether the number was blocked, and
-- who filed it. Beside v_call_risk_summary it says whether the risk score was
-- right. The caller's number stays in the payload, not in the view.
CREATE OR ALTER VIEW v_call_disposition AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   CAST(e.session_id AS VARCHAR(20)) AS call_id,
   e.created AS dispositioned_at,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   RIGHT(CONVERT(VARCHAR(32), CONVERT(VARBINARY(8), s.vorpal_id), 2), 8) AS vorpal_id,
   JSON_VALUE(e.payload, '$."outcome"') AS outcome,
   JSON_VALUE(e.payload, '$."treatment"') AS treatment,
   JSON_VALUE(e.payload, '$."blocked"') AS blocked,
   JSON_VALUE(e.payload, '$."agent"') AS agent,
   JSON_VALUE(e.payload, '$."identity"') AS caller_identity,
   JSON_VALUE(e.payload, '$."action"') AS action,
   JSON_VALUE(e.payload, '$."reasons"') AS reasons,
   TRY_CAST(JSON_VALUE(e.payload, '$."face"') AS INT) AS face
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type = 'agentDisposition';
GO

-- Configuration changes: every file the Configurator pushed to the servers
-- running its application, and every save from an editor. `failed` lists the
-- servers that did not take a push, as a JSON array; null when every one did.
-- The dashboard lists these under Operations, Changes, beside restarts, to
-- answer "what changed just before the failures rose".
CREATE OR ALTER VIEW v_config_changes AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   e.created AS changed_at,
   e.type AS change_type,
   JSON_VALUE(e.payload, '$."file"') AS config_file,
   JSON_VALUE(e.payload, '$."application"') AS configured_application,
   JSON_VALUE(e.payload, '$."actor"') AS actor,
   JSON_VALUE(e.payload, '$."change"') AS change_kind,
   JSON_VALUE(e.payload, '$."scope"') AS config_scope,
   JSON_QUERY(e.payload, '$."failed"') AS failed,
   JSON_VALUE(e.payload, '$."node"') AS node,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type IN ('configPublished', 'configSaved');
GO

-- Operations events: what went down and came back, problems applications
-- raised and cleared, and each minute a queue held callers. One row per event,
-- published on the change. The dashboard reads these to show a past hour's
-- Operations; `subject` is what the event is about (the endpoint, remote
-- service, gateway, queue, or problem key) and `detail` what was observed.
CREATE OR ALTER VIEW v_ops_events AS
SELECT
   CAST(e.id AS VARCHAR(20)) AS event_id,
   e.created AS occurred_at,
   e.type AS event_type,
   JSON_VALUE(e.payload, '$."node"') AS node,
   COALESCE(JSON_VALUE(e.payload, '$."application"'), a.name) AS application,
   COALESCE(JSON_VALUE(e.payload, '$."endpoint"'), JSON_VALUE(e.payload, '$."remote"'), JSON_VALUE(e.payload, '$."gateway"'), JSON_VALUE(e.payload, '$."queue"'), JSON_VALUE(e.payload, '$."key"')) AS subject,
   COALESCE(JSON_VALUE(e.payload, '$."note"'), JSON_VALUE(e.payload, '$."reason"'), JSON_VALUE(e.payload, '$."summary"')) AS detail,
   JSON_VALUE(e.payload, '$."kind"') AS remote_kind,
   JSON_VALUE(e.payload, '$."registrar"') AS registrar,
   TRY_CAST(JSON_VALUE(e.payload, '$."status"') AS BIGINT) AS status_code,
   TRY_CAST(JSON_VALUE(e.payload, '$."low"') AS BIGINT) AS low_waiting,
   TRY_CAST(JSON_VALUE(e.payload, '$."high"') AS BIGINT) AS high_waiting,
   TRY_CAST(JSON_VALUE(e.payload, '$."depth"') AS BIGINT) AS waiting_now,
   JSON_VALUE(e.payload, '$."open"') AS open_page,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type IN ('queueDepth', 'endpointDown', 'endpointUp', 'remoteDown', 'remoteUp', 'problemRaised',
   'problemCleared', 'trunkRegistered', 'trunkFailed', 'trunkUnregistered');
GO
