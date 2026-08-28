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
-- Every view exposes `tenant`, which reaches session and event rows through
-- `application_id`. One database behind one OAC instance can then serve many
-- customers with each seeing only their own calls, enforced either by OAC's
-- own row-level security or by a VPD policy on these views. NULL means a
-- single-tenant deployment and matches everything.
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
   a.tenant            AS tenant
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
   a.tenant            AS tenant,
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
-- Values are stored as strings, faithful to the wire; RETURNING NUMBER is what
-- makes them measures rather than labels in OAC.
CREATE OR REPLACE VIEW v_call_risk AS
SELECT
   CAST(e.id AS VARCHAR2(20))         AS event_id,
   CAST(e.session_id AS VARCHAR2(20)) AS call_id,
   e.created           AS assessed_at,
   e.type              AS event_type,
   a.tenant            AS tenant,
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
   a.tenant            AS tenant,
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
   a.tenant            AS tenant
FROM session_keys k
JOIN sessions s     ON s.id = k.session_id
JOIN applications a ON a.id = s.application_id;
