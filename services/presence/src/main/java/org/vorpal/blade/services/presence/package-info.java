/// Agent presence and skill routing over SIP: PUBLISH and SUBSCRIBE/NOTIFY for an agent's Ready or
/// Not Ready state and the skills they publish, and INVITEs to a skill's address routed to the
/// longest-idle agent Ready for it.
///
/// - [PresenceServlet]: dispatch, and the session keys that put each agent's state in one place
/// - [PublishCallflow], [SubscribeCallflow]: the event package `presence`
/// - [Agent], [Agents]: one agent's state, where it is kept, and NOTIFY
/// - [Skills]: each skill's members and waiting calls, the match between them, and its locking
/// - [SkillInvite]: a call for a skill, from 182 Queued to the agent's answer
/// - [Pidf]: the presence documents read and written
package org.vorpal.blade.services.presence;
