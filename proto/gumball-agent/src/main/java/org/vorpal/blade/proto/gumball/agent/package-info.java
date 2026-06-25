/// Project Gumball — Demo 1, the AI agent gateway (incubator/proto).
///
/// Answers an inbound SIP call as a UAS, anchors the caller's media on a Kurento RtpEndpoint via
/// the {@link org.vorpal.blade.proto.gumball.media.KurentoClient}, taps the audio to an int8
/// inference bridge (ASR -> LLM -> TTS), and branches on the conversation outcome.
///
/// ## Key Components
///
/// - [AgentSipServlet] - routes the initial INVITE to the agent callflow; owns the SettingsManager
/// - [AgentCallflow]   - the conversation lifecycle: answer, anchor media, wire the AI, branch on outcome
/// - [AgentSettings]   - configuration (Kurento URL, system prompt, language, transfer target)
/// - [AgentSettingsSample] - default configuration
///
/// ## Architecture note
///
/// BLADE owns SIP signaling; the media + inference run on the Kurento/Ampere plane. The callflow
/// stores only string ids (pipeline/endpoint/session) so the failover-serialized state stays
/// reconstructable — see [org.vorpal.blade.proto.gumball.media.KurentoClient] and the project
/// charter under gumball/.
///
/// @see AgentSipServlet
/// @see AgentCallflow
/// @see org.vorpal.blade.proto.gumball.media.KurentoClient
package org.vorpal.blade.proto.gumball.agent;
