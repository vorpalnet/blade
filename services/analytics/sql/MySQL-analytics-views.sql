-- BLADE Analytics — the reporting surface, MySQL.
--
-- The Oracle file (`Oracle-analytics-views.sql`) carries the full reasoning for
-- why these views exist and why every identifier is text. The short version,
-- because it is the part that bites silently:
--
--   The keys are 63-bit. A BI tool reads a NUMBER/BIGINT column into a double,
--   which carries 53 bits of mantissa, so above 2^53 the low digits are lost:
--   call 1414214647537770644 arrives as ...770800. It still looks like a valid
--   19-digit id, so a join between two datasets on `call_id` matches rounded
--   values and nothing anywhere reports an error.
--
-- So identifiers are CAST to CHAR here, and `vorpal_id` is rendered as the 8
-- hex digits an operator reads off a log line or a SIP header rather than the
-- decimal nobody can search with.
--
-- Point a BI tool at these, never at the tables: the views are a contract, the
-- tables are an implementation, and the last schema change killed every dataset
-- built directly on the tables.
--
-- Run as the schema owner, after the table script.

CREATE OR REPLACE VIEW v_calls AS
SELECT
   CAST(s.id AS CHAR(20))                     AS call_id,
   s.cluster_name                             AS cluster_name,
   LPAD(HEX(s.vorpal_id), 8, '0')             AS vorpal_id,
   s.created                                  AS started_at,
   s.destroyed                                AS ended_at,
   -- NULL while the call is still up, which is what a "live calls" tile filters
   -- on. MySQL subtracts datetimes with TIMESTAMPDIFF, which returns whole
   -- units, so SECOND is the grain a BI tool averages.
   CASE WHEN s.destroyed IS NOT NULL
        THEN TIMESTAMPDIFF(SECOND, s.created, s.destroyed) END
                                              AS duration_seconds,
   CAST(a.id AS CHAR(20))                     AS application_id,
   a.name                                     AS application,
   a.version                                  AS application_version,
   a.host                                     AS host,
   a.domain                                   AS domain,
   a.server                                   AS server,
   a.tenant                                   AS tenant
FROM sessions s
JOIN applications a ON a.id = s.application_id;

-- One row per recorded fact, with the attributes every event carries.
--
-- `payload` is exposed as well as flattened, so an analyst chasing something
-- the typed views do not cover can still read the document without waiting for
-- a new view to be deployed.
CREATE OR REPLACE VIEW v_events AS
SELECT
   CAST(e.id AS CHAR(20))                     AS event_id,
   e.type                                     AS event_type,
   e.created                                  AS occurred_at,
   e.event_uid                                AS event_uid,
   CAST(e.session_id AS CHAR(20))             AS call_id,
   CAST(e.application_id AS CHAR(20))         AS application_id,
   a.name                                     AS application,
   a.tenant                                   AS tenant,
   s.cluster_name                             AS cluster_name,
   LPAD(HEX(s.vorpal_id), 8, '0')             AS vorpal_id,
   e.payload                                  AS payload
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id;

-- The fused call-risk verdict, typed.
--
-- Note the quoted JSON paths. The attribute names contain dots —
-- `signal.acoustic` is one flat key, not a nested object — so
-- `$.signal.acoustic` would read it as a path into a structure that does not
-- exist and return nothing. `$."signal.acoustic"` is correct and the difference
-- is silent.
--
-- Values are stored as strings, faithful to the wire. JSON_UNQUOTE strips the
-- quotes JSON_EXTRACT leaves behind; the CAST is what makes them measures
-- rather than labels in a BI tool.
CREATE OR REPLACE VIEW v_call_risk AS
SELECT
   CAST(e.id AS CHAR(20))                     AS event_id,
   CAST(e.session_id AS CHAR(20))             AS call_id,
   e.created                                  AS assessed_at,
   e.type                                     AS event_type,
   a.tenant                                   AS tenant,
   a.name                                     AS application,
   s.cluster_name                             AS cluster_name,
   LPAD(HEX(s.vorpal_id), 8, '0')             AS vorpal_id,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."riskScore"')) AS DECIMAL(6,4))     AS risk_score,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."riskBand"'))                            AS risk_band,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."triggerSignal"'))                       AS trigger_signal,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."suspectStreak"')) AS SIGNED)       AS suspect_streak,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."signal.acoustic"')) AS DECIMAL(6,4))   AS signal_acoustic,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."signal.signaling"')) AS DECIMAL(6,4))  AS signal_signaling,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."signal.provenance"')) AS DECIMAL(6,4)) AS signal_provenance,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."signal.behavior"')) AS DECIMAL(6,4))   AS signal_behavior,
   -- The contributions are the defensibility argument made queryable: how many
   -- log-odds each independent signal added. A score alone invites "says who?".
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."contribution.acoustic"')) AS DECIMAL(8,4))   AS contribution_acoustic,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."contribution.signaling"')) AS DECIMAL(8,4))  AS contribution_signaling,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."contribution.provenance"')) AS DECIMAL(8,4)) AS contribution_provenance,
   CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."contribution.behavior"')) AS DECIMAL(8,4))   AS contribution_behavior
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callRiskAssessed', 'callRiskFlagged');

-- Call-level roll-up of risk: the worst assessment each call ever reached.
--
-- The per-event view above is the detail; this is what a "calls at risk" list is
-- built on, one row per call rather than one per scored window.
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
-- The conversational counterpart to v_call_risk: one row per classified
-- utterance. `addressed` is the system's judgement about whether an utterance
-- was meant for an agent and which one.
CREATE OR REPLACE VIEW v_conversation AS
SELECT
   CAST(e.id AS CHAR(20))                     AS event_id,
   CAST(e.session_id AS CHAR(20))             AS call_id,
   e.created                                  AS occurred_at,
   e.type                                     AS event_type,
   a.tenant                                   AS tenant,
   a.name                                     AS application,
   s.cluster_name                             AS cluster_name,
   LPAD(HEX(s.vorpal_id), 8, '0')             AS vorpal_id,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."text"'))        AS said,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."intent"'))      AS intent,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."entity"'))      AS entity,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."addressed"'))   AS addressed,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."caller"'))      AS caller,
   JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$."destination"')) AS destination
FROM events e
JOIN applications a ON a.id = e.application_id
LEFT JOIN sessions s ON s.id = e.session_id
WHERE e.type IN ('callerSaid', 'callStarted');

-- Session correlators, for looking a call up by something a human has: a
-- Cisco-GUCID, a caller number, whatever selector the domain configured.
CREATE OR REPLACE VIEW v_call_keys AS
SELECT
   CAST(k.session_id AS CHAR(20))             AS call_id,
   k.name                                     AS key_name,
   k.value                                    AS key_value,
   a.tenant                                   AS tenant
FROM session_keys k
JOIN sessions s     ON s.id = k.session_id
JOIN applications a ON a.id = s.application_id;
