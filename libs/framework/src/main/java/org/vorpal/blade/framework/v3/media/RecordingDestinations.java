package org.vorpal.blade.framework.v3.media;

import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Turns the logical name of a recording into the physical destination the media
/// server will write to.
///
/// ## Why an application never names a path
///
/// The URI an application hands a JSR-309 `Recorder` is a write primitive:
/// whatever is in it, the media server will try to write there. An application
/// that composes that string can therefore point a recording at any path the
/// media server can reach, including another call's.
///
/// So a BLADE application names a recording and nothing else, with
/// [MediaCallflow#recordingUri], and the destination is produced here. The
/// mapping belongs to the deployment rather than to the app, and the app cannot
/// widen it.
///
/// ## Why a seam and not a setting
///
/// The physical destination is not a constant. Where recordings go to object
/// storage it is a freshly minted, prefix-scoped, write-only capability,
/// different for every recording and revoked when the recording ends. A
/// configured base URL cannot express that, and a framework that knew how to
/// mint one would have to carry a cloud SDK into every application.
///
/// ## Discovery
///
/// Found through [ServiceLoader], the same mechanism that finds the JSR-309
/// driver: the deployment drops an implementation on the classpath and declares
/// it in `META-INF/services`. With none present, a `rec:` destination fails
/// rather than falling back somewhere convenient. Choosing a default location
/// for a recording nobody configured is how call audio ends up somewhere nobody
/// meant, and a recorder that silently wrote to the wrong place would be worse
/// than one that refused.
///
/// A deployment that records to ordinary files needs none of this: it passes a
/// `file:` URI and this is never consulted.
public interface RecordingDestinations {

	/// The scheme a logical recording destination carries.
	String SCHEME = "rec";

	/// @param recordingId the logical id, as produced by
	///                    [MediaCallflow#recordingUri], with the `rec:` scheme
	///                    already stripped
	/// @return the URI the media server should write to
	/// @throws Exception when no destination can be produced. The recording then
	///         fails loudly, which is the honest outcome: a recorder that cannot
	///         reach its store has not recorded anything.
	String resolve(String recordingId) throws Exception;

	/// Release the destination for a recording that has stopped.
	///
	/// Where the destination was a capability, this is what makes its life equal
	/// the length of the call rather than whatever expiry it was minted with.
	///
	/// Called on the teardown path, so it must not throw: a destination that
	/// outlives its recording is a small, bounded problem, and an exception
	/// thrown out of call teardown is not. The default does nothing, for
	/// implementations with nothing to release.
	default void release(String recordingId) {
	}

	/// The registered implementation, or null when the deployment records to
	/// plain files and needs none.
	///
	/// **Only success is cached.** A failure is retried on the next call, because
	/// a static that caches a failed lookup poisons the class for the life of the
	/// JVM: the first bad call breaks recording *and* the teardown path that
	/// releases destinations, and no amount of fixing the configuration recovers
	/// it without a restart. That is exactly what happened the first time this
	/// ran, and it turned a misconfiguration into an outage.
	static RecordingDestinations installed() {
		RecordingDestinations found = Holder.cached;
		if (found == null) {
			found = Holder.load();
			Holder.cached = found;
		}
		return found;
	}

	/// Deliberately not a settable static. The implementation is a property of
	/// what the deployment put on the classpath, and a setter would let one
	/// application redirect every other application's recordings.
	final class Holder {
		static volatile RecordingDestinations cached;

		private Holder() {
		}

		static RecordingDestinations load() {
			try {
				for (RecordingDestinations found : ServiceLoader.load(RecordingDestinations.class,
						RecordingDestinations.class.getClassLoader())) {
					return found;
				}
			} catch (Throwable t) {
				// ServiceLoader wraps anything a provider's constructor throws in a
				// ServiceConfigurationError. Report it and let the caller decide;
				// never let it escape as an Error nobody catches.
				Logger.getLogger(RecordingDestinations.class.getName())
						.log(Level.SEVERE, "a RecordingDestinations provider could not be loaded", t);
			}
			return null;
		}
	}
}
