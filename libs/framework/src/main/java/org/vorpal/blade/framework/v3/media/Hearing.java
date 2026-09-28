package org.vorpal.blade.framework.v3.media;

import java.net.URI;
import java.util.Collection;
import java.util.function.Function;

import javax.media.mscontrol.MediaEventListener;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;
import org.vorpal.blade.framework.v3.media.manifest.ContextBias;
import org.vorpal.blade.framework.v3.media.manifest.Redactor;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// Hear what a media group is joined to: transcribe every party, score the voices asked for, and
/// hand each utterance and score to the application, to the event bus and to the installed
/// [CallAnalyzer]s, as the application chooses.
///
/// One helper for every application that anchors media, so a two-party call and a meeting hear the
/// same way and cost the same: one transcription tap per party, whatever reads the words. The
/// application keeps what is its own, which is who a party is ([#start]'s `party` function), where a
/// stored transcript goes (its [Ear]) and whether the call is analyzed at all.
///
/// ## What each utterance goes through
///
/// The party is named, the [ContextBias] corrects it, and the [Redactor] marks what must not be
/// shown. Then, for a live utterance ([TranscriberEvent.Type#LIVE_UTTERANCE]), the redacted text is
/// published as [BladeEventTypes#CALL_UTTERANCE] when `publish` is on; for either kind the application's [Ear] is told,
/// and so is every analyzer when `analyze` is on. The bus gets the redacted rendition because the
/// bus is read by more than the screen it was meant for, and the analytics sink keeps it. Ears and
/// analyzers get the verbatim words: they are in process and store nothing unless they choose to.
///
/// ## Threads
///
/// Everything here runs on the media driver's thread, which must not block. An [Ear] hands slow
/// work, object storage or SIP, to a thread or lock of its own. A failure in an ear or an analyzer is
/// logged and never reaches the call.
public final class Hearing {

	/// The application's own use of what is heard.
	public interface Ear {
		/// A party said something. `live` is the first decode; otherwise it is the decode for the
		/// record, the one to store.
		default void heard(String party, Utterance utterance, boolean live) {
		}

		/// One window of a party's voice was scored.
		default void voice(String party, VoiceAssessmentEvent event) {
		}

		/// A party started speaking, a fraction of a second in, before any of their words are
		/// decoded: the moment to show who is talking. Only when the driver can tell.
		default void speaking(String party) {
		}
	}

	private Hearing() {
	}

	/// Transcribe every party on the source `group` is joined to.
	///
	/// @param app     the session events are correlated with, and the one analyzers are told about
	/// @param party   a leg's URI to the name the application uses for that party
	/// @param bias    corrections toward the names this call is likely to contain; may be empty
	/// @param redact  what must be masked before the words leave the node; may be none
	/// @param publish publish each live utterance on the bus
	/// @param analyze tell the installed [CallAnalyzer]s
	/// @param ear     the application's own use; may be null
	/// @return false when the driver would not transcribe
	public static boolean start(SipApplicationSession app, MediaGroup group, Function<String, String> party,
			ContextBias bias, Redactor redact, boolean publish, boolean analyze, Ear ear) {
		final ContextBias corrections = (bias == null) ? ContextBias.of(null) : bias;
		final Redactor redactor = (redact == null) ? Redactor.none() : redact;
		MediaEventListener<TranscriberEvent> hearing = event -> {
			Utterance utterance = event.getUtterance();
			String who = party.apply(utterance.getParty());
			if (TranscriberEvent.Type.SPEECH_STARTED.equals(event.getEventType())) {
				// No words: nothing to correct, redact, publish or analyze.
				if (ear != null) {
					try {
						ear.speaking(who);
					} catch (Throwable t) {
						warn("an ear failed on a speech start: " + t);
					}
				}
				return;
			}
			utterance.setParty(who);
			corrections.apply(utterance);
			redactor.apply(utterance);
			boolean live = TranscriberEvent.Type.LIVE_UTTERANCE.equals(event.getEventType());
			if (live && publish) {
				publishUtterance(app, utterance);
			}
			if (ear != null) {
				try {
					ear.heard(who, utterance, live);
				} catch (Throwable t) {
					warn("an ear failed on an utterance: " + t);
				}
			}
			if (analyze) {
				for (CallAnalyzer analyzer : CallAnalyzer.installed()) {
					try {
						analyzer.heard(app, who, utterance, live);
					} catch (Throwable t) {
						warn("an analyzer failed on an utterance: " + t);
					}
				}
			}
		};
		return MediaCallflow.transcribe(group, hearing, ear != null);
	}

	/// Score the voices on `legs`, by their connection URIs, publishing and analyzing as [#start]
	/// does. Nothing is scored when `legs` is empty.
	///
	/// @return false when nothing is being scored
	public static boolean scoreVoices(SipApplicationSession app, MediaGroup group, Collection<URI> legs,
			Function<String, String> party, boolean publish, boolean analyze, Ear ear) {
		if (legs == null || legs.isEmpty()) {
			return false;
		}
		return MediaCallflow.assessVoice(group, legs, event -> {
			String who = party.apply(event.getParty());
			if (publish) {
				publishVoice(app, who, event);
			}
			if (ear != null) {
				try {
					ear.voice(who, event);
				} catch (Throwable t) {
					warn("an ear failed on a voice score: " + t);
				}
			}
			if (analyze) {
				for (CallAnalyzer analyzer : CallAnalyzer.installed()) {
					try {
						analyzer.voiceAssessed(app, who, event.getScore(), event.getModel());
					} catch (Throwable t) {
						warn("an analyzer failed on a voice score: " + t);
					}
				}
			}
		});
	}

	/// Stop hearing `group`: the transcription and the delivery of voice scores.
	public static void stop(MediaGroup group) {
		MediaCallflow.stopTranscribing(group);
		MediaCallflow.stopAssessingVoice(group);
	}

	/// Publish one utterance as [BladeEventTypes#CALL_UTTERANCE]: `text` (redacted when it was),
	/// `party`, `startMs`, `endMs`.
	public static void publishUtterance(SipApplicationSession app, Utterance utterance) {
		String text = (utterance.getRedacted() != null) ? utterance.getRedacted() : utterance.getText();
		if (text == null || text.trim().isEmpty()) {
			return;
		}
		Events.publish(app, BladeEventTypes.CALL_UTTERANCE, data -> data
				.put("text", text.trim())
				.put("party", utterance.getParty())
				.put("startMs", utterance.getStartMillis())
				.put("endMs", utterance.getEndMillis()));
	}

	/// Publish one voice score as [BladeEventTypes#CALL_VOICE_ASSESSED]: `score`, `party`, `model`,
	/// `offsetMs`.
	public static void publishVoice(SipApplicationSession app, String party, VoiceAssessmentEvent scored) {
		String model = scored.getModel();
		Events.publish(app, BladeEventTypes.CALL_VOICE_ASSESSED, data -> data
				.put("score", Math.round(scored.getScore() * 1000.0) / 1000.0)
				.put("party", party)
				.put("model", (model == null || model.isEmpty()) ? null : model)
				.put("offsetMs", scored.getOffsetMillis()));
	}

	private static void warn(String message) {
		if (Callflow.getSipLogger() != null) {
			Callflow.getSipLogger().warning("Hearing: " + message);
		}
	}
}
