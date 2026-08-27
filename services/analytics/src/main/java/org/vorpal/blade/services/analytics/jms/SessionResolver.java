package org.vorpal.blade.services.analytics.jms;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;

import org.vorpal.blade.services.analytics.model.Session;

/// Resolves a call's `sessions` row from its correlator, creating it if it is
/// not there yet.
///
/// **This is what makes arrival order stop mattering.** The consumer used to
/// depend on seeing a session start before anything that referenced it: an
/// in-JVM map held the mapping, a bounded parking lot caught session keys that
/// arrived early, and an event that arrived early was persisted with a null
/// `session_id` — permanently orphaned, silently. The parking lot's own comment
/// recorded the day it was observed happening in production.
///
/// That dependency was never sound. The subscriber is targeted at the cluster,
/// so messages already spread across members: the in-JVM caches were already
/// split, which is exactly why a database fallback had to be bolted on.
///
/// **The correlator is now the key, not a lookup into one.** The row's id is
/// [Session#idFor] of `(cluster_name, vorpal_id, created)`, so resolving is a
/// primary-key `find` rather than a query, and creating needs no flush to
/// discover what id the row got. Two cluster members handling the same call
/// compute the same number without consulting each other.
///
/// **Why `created` belongs in the key.** A Vorpal-ID is 32 bits and is only
/// checked for uniqueness against *currently live* sessions, so ids are reused
/// over time. The birth instant is what makes the pair an identity rather than
/// a correlator — and it is already on every message, because `Session.created`
/// is derived from the X-Vorpal-ID `ts` parameter.
public final class SessionResolver {

	private SessionResolver() {
	}

	/// Find the session row for a correlator, or create a stub.
	///
	/// A stub is a real `sessions` row with the correlator and application set
	/// and `destroyed` left null. When the real `session.started` arrives it
	/// computes the same key and updates the same row rather than colliding
	/// with it.
	///
	/// @param em            an open entity manager inside the caller's transaction
	/// @param clusterName   the domain/cluster stamp
	/// @param vorpalId      the call correlator
	/// @param created       the call's birth instant, or null when unknown
	/// @param applicationId the reporting application instance
	/// @return the session's database primary key
	public static Long resolveOrCreate(EntityManager em, String clusterName, long vorpalId, Date created,
			long applicationId) {

		Date birth = birthInstant(em, clusterName, vorpalId, created);
		long id = Session.idFor(clusterName, vorpalId, birth);

		Session existing = em.find(Session.class, id);
		if (existing != null) {
			return id;
		}

		Session stub = new Session();
		stub.setId(id);
		stub.setClusterName(clusterName);
		stub.setVorpalId(vorpalId);
		stub.setApplicationId(applicationId);
		stub.setCreated(birth);
		return insert(em, stub, id);
	}

	/// Close a session, creating it already-closed if the stop arrives first.
	///
	/// First stop wins: a second one finds the row already closed and does
	/// nothing, which keeps `session_open_uk` meaningful.
	public static Long close(EntityManager em, String clusterName, long vorpalId, Date created, long applicationId,
			Timestamp destroyed) {

		Date birth = birthInstant(em, clusterName, vorpalId, created);
		long id = Session.idFor(clusterName, vorpalId, birth);

		Session existing = em.find(Session.class, id);
		if (existing == null) {
			// Stop before start. Create the row already closed rather than
			// discard the only record that this call happened.
			Session row = new Session();
			row.setId(id);
			row.setClusterName(clusterName);
			row.setVorpalId(vorpalId);
			row.setApplicationId(applicationId);
			row.setCreated(birth);
			row.setDestroyed(destroyed);
			return insert(em, row, id);
		}
		if (existing.getDestroyed() == null) {
			existing.setDestroyed(destroyed);
			em.merge(existing);
		}
		return id;
	}

	/// The birth instant to key on.
	///
	/// Normally it is on the wire and this is a no-op. When it is absent the
	/// key cannot be computed, so fall back to the correlator's one open
	/// session and adopt *its* birth instant — that is the row the message
	/// means. With no open session either, mint an instant and let this call
	/// have its own identity; a row under a slightly-wrong timestamp beats
	/// discarding the message.
	private static Date birthInstant(EntityManager em, String clusterName, long vorpalId, Date created) {
		if (created != null) {
			return created;
		}
		List<Session> open = em.createNamedQuery("Session.findOpen", Session.class)
				.setParameter("clusterName", clusterName)
				.setParameter("vorpalId", vorpalId)
				.setMaxResults(1)
				.getResultList();
		return open.isEmpty() ? new Date() : open.get(0).getCreated();
	}

	/// Insert a row whose key is already known.
	///
	/// A duplicate-key failure here means another cluster member inserted the
	/// same call between the `find` and the insert. That is not a conflict to
	/// resolve — both members computed the same key from the same facts and
	/// would have written the same row — so the id is simply returned.
	private static Long insert(EntityManager em, Session row, long id) {
		try {
			em.persist(row);
			return id;
		} catch (RuntimeException raced) {
			em.clear();
			if (em.find(Session.class, id) != null) {
				return id;
			}
			throw raced;
		}
	}
}
