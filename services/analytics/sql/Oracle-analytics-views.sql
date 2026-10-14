-- BLADE Analytics — the reporting surface, for Oracle Analytics Cloud.
--
-- **Point a BI tool at these, never at the tables.** Two reasons, and the
-- second is why this file exists at all.
--
-- 1. A BI tool cannot do anything with `events.payload`. It is a JSON
--    document, and OAC wants typed columns it can aggregate and filter. So
--    something has to flatten it; better once here than in every workbook.
--
-- 2. These views are a CONTRACT, and the tables underneath are an
--    implementation. When the analytics schema changed — seven tables to
--    four, an attributes/attribute_names pair replaced by one JSON column —
--    every dataset built directly on the tables died with it. A view layer
--    absorbs that: the storage can change again and the workbooks survive,
--    because the columns they were built on still resolve.
--
-- Adding an attribute to a dashboard is therefore a one-line change here, and
-- touches no table, no index and no application.
--
-- ── Multi-tenancy ──────────────────────────────────────────────────────────
--
-- Every view exposes `tenant`: the call's own (X-Vorpal-ID `tn`, stored on the
-- event or session row) or else the publishing deployment's `blade.tenant`,
-- through `application_id`. A view over events also exposes `department`, the
-- part of the tenant that handled that part of the call. One database behind
-- one OAC instance can then serve many customers with each seeing only their
-- own calls, enforced either by OAC's own row-level security, by a VPD policy
-- on these views, or by the dashboard's grants. NULL means a single-tenant
-- deployment and matches everything.
--
-- ── Why every identifier here is text ─────────────────────────────────────
--
-- The keys are 63-bit. A BI tool reads a NUMBER column into a double, which
-- carries 53 bits of mantissa, so above 2^53 the low digits are quietly lost:
-- call 1414214647537770644 arrives in Oracle Analytics as ...770800. The value
-- still looks like a 19-digit id, which is what makes it dangerous — a join
-- between two datasets on `call_id` matches rounded values, and nothing
-- reports an error.
--
-- So identifiers are CAST to VARCHAR2 at this boundary. They are opaque
-- correlators, never arithmetic; a BI tool offering to SUM a call id was never
-- useful, and text also stops it trying.
--
-- `vorpal_id` gets the same treatment for a different reason: it is a
-- correlator a human arrives with, copied from a log line or a SIP header,
-- where it is written as 8 hex digits. Decimal is the one form nobody can
-- search with.
--
-- Run as the schema owner, after the table script.

CREATE OR REPLACE VIEW v_calls AS
SELECT
   CAST(s.id AS VARCHAR2(20))  AS call_id,
   s.cluster_name      AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   s.created           AS started_at,
   s.destroyed         AS ended_at,
   -- NULL while the call is still up, which is what a "live calls" tile
   -- filters on. Oracle subtracts timestamps into an INTERVAL; the extract
   -- turns it into the seconds a BI tool can average.
   CASE WHEN s.destroyed IS NOT NULL THEN
      EXTRACT(DAY    FROM (s.destroyed - s.created)) * 86400
    + EXTRACT(HOUR   FROM (s.destroyed - s.created)) * 3600
    + EXTRACT(MINUTE FROM (s.destroyed - s.created)) * 60
    + EXTRACT(SECOND FROM (s.destroyed - s.created))
   END                 AS duration_seconds,
   CAST(a.id AS VARCHAR2(20))  AS application_id,
   a.name              AS application,
   a.version           AS application_version,
   a.host              AS host,
   a.domain            AS domain,
   a.server            AS server,
   COALESCE(s.tenant, a.tenant) AS tenant
FROM sessions s
JOIN applications a ON a.id = s.application_id;

-- One row per recorded fact, with the attributes every event carries.
--
-- `payload` is exposed as well as flattened: an analyst chasing something the
-- columns do not cover can still read the document, without needing a new view
-- to be deployed first.
CREATE OR REPLACE VIEW v_events AS
SELECT
   CAST(e.id AS VARCHAR2(20))             AS event_id,
   e.type              AS event_type,
   e.created           AS occurred_at,
   e.event_uid         AS event_uid,
   CAST(e.session_id AS VARCHAR2(20))     AS call_id,
   CAST(e.application_id AS VARCHAR2(20)) AS application_id,
   a.name              AS application,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   s.cluster_name      AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   e.payload           AS payload
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id;

-- The fused call-risk verdict, typed.
--
-- This is the view a risk dashboard is built on, and the one that makes the
-- demo question answerable without a join or a cast: "which calls scored above
-- 0.8, and what did each signal contribute".
--
-- Note the quoted JSON paths. The attribute names contain dots —
-- `signal.acoustic` is one flat key, not a nested object — so `$.signal.acoustic`
-- would read it as a path into a structure that does not exist and return
-- nothing. `$."signal.acoustic"` is correct and the difference is silent.
--
-- Values are numbers, or numeric strings in rows written before the flat
-- payload; RETURNING NUMBER reads both, and is what makes them measures rather
-- than labels in OAC.
CREATE OR REPLACE VIEW v_call_risk AS
SELECT
   CAST(e.id AS VARCHAR2(20))         AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created           AS assessed_at,
   e.type              AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name              AS application,
   s.cluster_name      AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."riskScore"'               RETURNING NUMBER) AS risk_score,
   JSON_VALUE(e.payload, '$."riskBand"')                                 AS risk_band,
   JSON_VALUE(e.payload, '$."triggerSignal"')                            AS trigger_signal,
   JSON_VALUE(e.payload, '$."suspectStreak"'           RETURNING NUMBER) AS suspect_streak,
   JSON_VALUE(e.payload, '$."signal.acoustic"'         RETURNING NUMBER) AS signal_acoustic,
   JSON_VALUE(e.payload, '$."signal.signaling"'        RETURNING NUMBER) AS signal_signaling,
   JSON_VALUE(e.payload, '$."signal.provenance"'       RETURNING NUMBER) AS signal_provenance,
   JSON_VALUE(e.payload, '$."signal.behavior"'         RETURNING NUMBER) AS signal_behavior,
   -- The contributions are the defensibility argument made queryable: how many
   -- log-odds each independent signal added. A score alone invites "says who?".
   JSON_VALUE(e.payload, '$."contribution.acoustic"'   RETURNING NUMBER) AS contribution_acoustic,
   JSON_VALUE(e.payload, '$."contribution.signaling"'  RETURNING NUMBER) AS contribution_signaling,
   JSON_VALUE(e.payload, '$."contribution.provenance"' RETURNING NUMBER) AS contribution_provenance,
   JSON_VALUE(e.payload, '$."contribution.behavior"'   RETURNING NUMBER) AS contribution_behavior
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callRiskAssessed', 'callRiskFlagged');

-- Call-level roll-up of risk: the worst assessment each call ever reached.
--
-- The per-event view above is the detail; this is what a "calls at risk" list
-- is built on, one row per call rather than one per scored window.
CREATE OR REPLACE VIEW v_call_risk_summary AS
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

-- What the caller said and what the system made of it, typed.
--
-- The conversational counterpart to `v_call_risk`: one row per classified
-- utterance. `addressed` is the system's judgement about whether an utterance
-- was meant for an agent and which one — the nearest thing the current design
-- has to a gate decision, and named for what it is rather than for the column
-- an older dashboard used.
CREATE OR REPLACE VIEW v_conversation AS
SELECT
   CAST(e.id AS VARCHAR2(20))         AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created           AS occurred_at,
   e.type              AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name              AS application,
   s.cluster_name      AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."text"')        AS said,
   JSON_VALUE(e.payload, '$."intent"')      AS intent,
   JSON_VALUE(e.payload, '$."entity"')      AS entity,
   JSON_VALUE(e.payload, '$."addressed"')   AS addressed,
   JSON_VALUE(e.payload, '$."caller"')      AS caller,
   JSON_VALUE(e.payload, '$."destination"') AS destination
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callerSaid', 'callStarted');

-- Session correlators, for looking a call up by something a human has: a
-- Cisco-GUCID, a caller number, whatever selector the domain configured.
CREATE OR REPLACE VIEW v_call_keys AS
SELECT
   CAST(k.session_id AS VARCHAR2(20)) AS call_id,
   k.name              AS key_name,
   k.value             AS key_value,
   COALESCE(s.tenant, a.tenant) AS tenant
FROM session_keys k
JOIN sessions s     ON s.id = k.session_id
JOIN applications a ON a.id = s.application_id;

-- Queueing, one row per fact: a caller joining a queue, being offered to the
-- destination (an offer that failed retryably counts up `attempt`), or leaving
-- without being connected (`reason` caller or refused). `depth` is the queue's
-- length with the caller in it.
CREATE OR REPLACE VIEW v_queue AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."queue"') AS queue,
   JSON_VALUE(e.payload, '$."depth"' RETURNING NUMBER) AS depth,
   JSON_VALUE(e.payload, '$."waitedMs"' RETURNING NUMBER) / 1000 AS waited_seconds,
   JSON_VALUE(e.payload, '$."attempt"' RETURNING NUMBER) AS attempt,
   JSON_VALUE(e.payload, '$."reason"') AS reason
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('queueEntered', 'queueReleased', 'queueAbandoned');

-- Where each call was sent. `callRouted` is a proxy's choice (iRouter's
-- destination, or the balancer's endpoint and tier after `failovers` tiers
-- failed); `callResponded` and `callDeclined` are answers the router or the
-- balancer gave itself, with the `status` it sent; `branchFailed` is a server
-- the call was sent to answering that leg with a failure (`status`, `reason`,
-- the `responder` address, and `retry_after` from a 503's Retry-After).
CREATE OR REPLACE VIEW v_call_routing AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."destination"') AS destination,
   JSON_VALUE(e.payload, '$."endpoint"') AS endpoint,
   JSON_VALUE(e.payload, '$."tier"') AS tier,
   JSON_VALUE(e.payload, '$."failovers"' RETURNING NUMBER) AS failovers,
   JSON_VALUE(e.payload, '$."status"' RETURNING NUMBER) AS status,
   JSON_VALUE(e.payload, '$."reason"') AS reason,
   JSON_VALUE(e.payload, '$."responder"') AS responder,
   JSON_VALUE(e.payload, '$."retryAfter"' RETURNING NUMBER) AS retry_after
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callRouted', 'callResponded', 'callDeclined', 'branchFailed');

-- Parked calls: one row when a call is put on hold, one when it leaves, with
-- how long it was held.
CREATE OR REPLACE VIEW v_call_hold AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."heldMs"' RETURNING NUMBER) / 1000 AS held_seconds
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callHeld', 'callHoldEnded');

-- Calls an application placed on someone's behalf (third-party call
-- control): who was called and how they answered.
CREATE OR REPLACE VIEW v_call_origination AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created AS occurred_at,
   e.type AS event_type,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."party"') AS party,
   JSON_VALUE(e.payload, '$."status"' RETURNING NUMBER) AS status,
   JSON_VALUE(e.payload, '$."answered"') AS answered
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callOriginated');

-- Queue wait per call: what a call-center report asks first. One row per
-- call per queue: when the caller joined, the longest wait recorded, how many
-- offers it took, and how it ended. `outcome` is caller (hung up), refused
-- (the destination refused for good), released (offered and not abandoned),
-- or waiting (still queued, or the node went away before it said).
CREATE OR REPLACE VIEW v_queue_wait AS
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

-- What each application did, per node, minute by minute: events published,
-- publishes the bus refused (each one a dropped event), INVITEs received and
-- SIP errors. The dashboard reads every application's metrics MBean and
-- publishes the difference since its last look; the MBean itself resets on
-- every deploy, so these rows are the only history of it. `restarted` says
-- the application restarted within the sample and its counts run from then.
CREATE OR REPLACE VIEW v_app_samples AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   JSON_VALUE(e.payload, '$."cluster"') AS weblogic_cluster,
   JSON_VALUE(e.payload, '$."seconds"' RETURNING NUMBER) AS seconds,
   JSON_VALUE(e.payload, '$."published"' RETURNING NUMBER) AS published,
   JSON_VALUE(e.payload, '$."failures"' RETURNING NUMBER) AS failures,
   JSON_VALUE(e.payload, '$."invites"' RETURNING NUMBER) AS invites,
   JSON_VALUE(e.payload, '$."sipErrors"' RETURNING NUMBER) AS sip_errors,
   JSON_VALUE(e.payload, '$."restarted"') AS restarted,
   JSON_VALUE(e.payload, '$."activeAppSessions"' RETURNING NUMBER) AS active_app_sessions,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type = 'applicationSampled';

-- How each server stood, minute by minute: SIP messages its container
-- received and rejected, application sessions created, and at each sample its
-- live sessions, heap and threads. `rejected` is the only record of a message
-- the container refused under overload: it never reached an application.
-- Published by the dashboard; readings a server lacks (the AdminServer runs
-- no SIP container) are absent, not zero.
CREATE OR REPLACE VIEW v_server_samples AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."node"') AS node,
   JSON_VALUE(e.payload, '$."cluster"') AS weblogic_cluster,
   JSON_VALUE(e.payload, '$."state"') AS state,
   JSON_VALUE(e.payload, '$."restarted"') AS restarted,
   JSON_VALUE(e.payload, '$."seconds"' RETURNING NUMBER) AS seconds,
   JSON_VALUE(e.payload, '$."received"' RETURNING NUMBER) AS received,
   JSON_VALUE(e.payload, '$."rejected"' RETURNING NUMBER) AS rejected,
   JSON_VALUE(e.payload, '$."appSessionsCreated"' RETURNING NUMBER) AS app_sessions_created,
   JSON_VALUE(e.payload, '$."routed"' RETURNING NUMBER) AS routed,
   JSON_VALUE(e.payload, '$."routingFallbacks"' RETURNING NUMBER) AS routing_fallbacks,
   JSON_VALUE(e.payload, '$."undeployedBypasses"' RETURNING NUMBER) AS undeployed_bypasses,
   JSON_VALUE(e.payload, '$."routingCycles"' RETURNING NUMBER) AS routing_cycles,
   JSON_VALUE(e.payload, '$."activeAppSessions"' RETURNING NUMBER) AS active_app_sessions,
   JSON_VALUE(e.payload, '$."activeSipSessions"' RETURNING NUMBER) AS active_sip_sessions,
   JSON_VALUE(e.payload, '$."heapUsedPct"' RETURNING NUMBER) AS heap_used_pct,
   JSON_VALUE(e.payload, '$."cpuPct"' RETURNING NUMBER) AS cpu_pct,
   JSON_VALUE(e.payload, '$."threadsStuck"' RETURNING NUMBER) AS threads_stuck,
   JSON_VALUE(e.payload, '$."threadsHogging"' RETURNING NUMBER) AS threads_hogging,
   JSON_VALUE(e.payload, '$."threadsPending"' RETURNING NUMBER) AS threads_pending,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type = 'serverSampled';

-- Every counter an application declares, one row per counter that moved in
-- a sample: the history of what an application counts about itself (a speech
-- service that did not answer, a media server that refused), kept past the
-- redeploy that resets the counter. A labelled counter is `name.value`.
CREATE OR REPLACE VIEW v_app_counters AS
SELECT
   e.id || ':' || c.counter_name AS row_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   c.counter_name AS counter,
   c.delta AS delta,
   (SELECT a.domain FROM applications a WHERE a.id = e.application_id) AS domain
FROM events e,
   JSON_TABLE(e.payload, '$.counters[*]' COLUMNS (
      counter_name VARCHAR2(200) PATH '$.name',
      delta NUMBER PATH '$.delta')) c
WHERE e.type = 'applicationSampled';

-- Every timing an application records, one row per timing per sample: how
-- many recordings and their summed milliseconds, so a mean over any window is
-- SUM(sum_millis) / SUM(recordings).
CREATE OR REPLACE VIEW v_app_timings AS
SELECT
   e.id || ':' || t.timing_name AS row_id,
   e.created AS sampled_at,
   JSON_VALUE(e.payload, '$."app"') AS application,
   JSON_VALUE(e.payload, '$."node"') AS node,
   t.timing_name AS timing,
   t.recordings AS recordings,
   t.sum_millis AS sum_millis,
   (SELECT a.domain FROM applications a WHERE a.id = e.application_id) AS domain
FROM events e,
   JSON_TABLE(e.payload, '$.timings[*]' COLUMNS (
      timing_name VARCHAR2(200) PATH '$.name',
      recordings NUMBER PATH '$.count',
      sum_millis NUMBER PATH '$.sumMillis')) t
WHERE e.type = 'applicationSampled';

-- An agent's verdict on a call, as filed from the Agent Console: the outcome
-- (legitimate, scam, ...), the treatment, whether the number was blocked, and
-- who filed it. Beside v_call_risk_summary it says whether the risk score was
-- right. The caller's number stays in the payload, not in the view.
CREATE OR REPLACE VIEW v_call_disposition AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created AS dispositioned_at,
   COALESCE(e.tenant, a.tenant) AS tenant,
   e.department AS department,
   a.name AS application,
   s.cluster_name AS cluster_name,
   LPAD(TO_CHAR(s.vorpal_id, 'FMXXXXXXXXXXXXXXXX'), 8, '0') AS vorpal_id,
   JSON_VALUE(e.payload, '$."outcome"') AS outcome,
   JSON_VALUE(e.payload, '$."treatment"') AS treatment,
   JSON_VALUE(e.payload, '$."blocked"') AS blocked,
   JSON_VALUE(e.payload, '$."agent"') AS agent,
   JSON_VALUE(e.payload, '$."identity"') AS caller_identity,
   JSON_VALUE(e.payload, '$."action"') AS action,
   JSON_VALUE(e.payload, '$."reasons"') AS reasons,
   JSON_VALUE(e.payload, '$."face"' RETURNING NUMBER) AS face
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type = 'agentDisposition';

-- Configuration changes: every file the Configurator pushed to the servers
-- running its application, and every save from an editor. `failed` lists the
-- servers that did not take a push, as a JSON array; null when every one did.
-- The dashboard lists these under Operations, Changes, beside restarts, to
-- answer "what changed just before the failures rose".
CREATE OR REPLACE VIEW v_config_changes AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
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

-- Operations events: what went down and came back, problems applications
-- raised and cleared, and each minute a queue held callers. One row per event,
-- published on the change. The dashboard reads these to show a past hour's
-- Operations; `subject` is what the event is about (the endpoint, remote
-- service, gateway, queue, or problem key) and `detail` what was observed.
CREATE OR REPLACE VIEW v_ops_events AS
SELECT
   CAST(e.id AS VARCHAR2(20)) AS event_id,
   e.created AS occurred_at,
   e.type AS event_type,
   JSON_VALUE(e.payload, '$."node"') AS node,
   COALESCE(JSON_VALUE(e.payload, '$."application"'), a.name) AS application,
   COALESCE(JSON_VALUE(e.payload, '$."endpoint"'), JSON_VALUE(e.payload, '$."remote"'), JSON_VALUE(e.payload, '$."gateway"'), JSON_VALUE(e.payload, '$."queue"'), JSON_VALUE(e.payload, '$."key"')) AS subject,
   COALESCE(JSON_VALUE(e.payload, '$."note"'), JSON_VALUE(e.payload, '$."reason"'), JSON_VALUE(e.payload, '$."summary"')) AS detail,
   JSON_VALUE(e.payload, '$."kind"') AS remote_kind,
   JSON_VALUE(e.payload, '$."registrar"') AS registrar,
   JSON_VALUE(e.payload, '$."status"' RETURNING NUMBER) AS status_code,
   JSON_VALUE(e.payload, '$."low"' RETURNING NUMBER) AS low_waiting,
   JSON_VALUE(e.payload, '$."high"' RETURNING NUMBER) AS high_waiting,
   JSON_VALUE(e.payload, '$."depth"' RETURNING NUMBER) AS waiting_now,
   JSON_VALUE(e.payload, '$."open"') AS open_page,
   a.domain AS domain
FROM events e
JOIN applications a ON a.id = e.application_id
WHERE e.type IN ('queueDepth', 'endpointDown', 'endpointUp', 'remoteDown', 'remoteUp', 'problemRaised',
   'problemCleared', 'trunkRegistered', 'trunkFailed', 'trunkUnregistered');
