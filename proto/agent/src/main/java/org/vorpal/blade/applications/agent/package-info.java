/// The Agent Console: a live screen-pop and one-click fraud report for the human
/// agent, deployed on the engine tier.
///
/// ## What it is
///
/// One converged app in one WAR, two faces:
///
///  - **SIP** ([AgentServlet]) proxies the inbound call to the agent. As it
///    passes, it reads the INVITE into a [CallPop], enriches it with the
///    caller's catalog [CallerHistory], and pushes it to the consoles.
///  - **WebSocket** ([AgentConsoleEndpoint]) is the console's one channel, both
///    directions: a signed-in agent's browser holds a single socket, receives
///    pops on it, and sends its one action, a report, back up the same socket.
///    There is no REST API beside it.
///
/// The pop reaches the screen while the phone is still ringing. That timing is
/// the product: an agent who sees "reported for scam twice before, STIR
/// attestation C, screened as watch" in time can let the call go to voicemail
/// instead of answering it.
///
/// ## Where the data comes from
///
/// Nothing here screens a call or decides risk. Those happen upstream at the
/// edge (proxy-block / iRouter) and, for the fused score, in the closed risk
/// engine. This app reads what they already stamped on the INVITE (`X-Call-Screen`,
/// `X-Call-Rate`, the `Identity` PASSporT) and what the call catalog already
/// recorded (`BLADE_CONVERSATION`, `BLADE_LABEL`), and shows it. The one thing
/// it writes is the agent's report.
///
/// ## What a report does
///
/// One click fans out three ways ([ReportService]), each best-effort and
/// independent:
///
///  1. a `spam_numbers` row the edge reads next time, with a treatment chosen
///     from the category (harassment goes to a review voicemail, robocall to a
///     tarpit, scam is declined) and an expiry, since numbers rotate;
///  2. a `BLADE_LABEL` row on the conversation, which is both the catalog's
///     label store and the ground truth the fused risk score calibrates against;
///  3. a CloudEvent on the bus for audit and any other subscriber.
///
/// The report never sends a `607` or otherwise tells the caller they were
/// blocked: a spammer who learns they are blocked tries harder. The treatments
/// divert quietly.
///
/// ## Trust and safety
///
/// The pop is a read of headers a prior hop stamped. It shows them as observed,
/// decoded but not re-verified, and never acts on them: the acting already
/// happened at the edge. Identity is OpenID Connect (`OidcLoginFilter`, the same
/// model as the recordings service): the agents are corporate users with no
/// WebLogic account. The fleet-wide same-origin filter refuses a cross-site
/// WebSocket upgrade, and an unauthenticated upgrade never gets past the login
/// filter, so the socket opens only for a signed-in identity. Reporting is gated
/// against that identity's group claims ([AgentConsoleEndpoint],
/// [AgentSettings#getReportGroups]), since a container role check cannot reach a
/// single WebSocket frame; and a report is attributed to the socket's
/// authenticated principal, never a value the browser sent, so it cannot be
/// pinned on someone else. Every
/// catalog touch is best-effort and parameterized, so a database that is down,
/// slow, or fed a hostile caller-id string can make the pop thinner but can
/// never fail the call or reach the SQL.
///
/// ## Deliberately deferred
///
/// v1 broadcasts every pop to every open console: for a single agent that is
/// their screen, for a supervisor it is the floor view. Routing a pop to the one
/// agent an ACD assigned the call to, and diverting to voicemail as a live
/// in-path action rather than a next-call block, are later steps (see the
/// agent-app questions log).
package org.vorpal.blade.applications.agent;
