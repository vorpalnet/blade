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
/// Resolving through the database by natural key removes the dependency instead
/// of patching around it. It matters more now than it did — a durable
/// subscription redelivers on every rolling restart, so out-of-order arrival is
/// routine rather than a race.
///
/// **The natural key is `(cluster_name, vorpal_id, created)`, not
/// `(cluster_name, vorpal_id)`.** A Vorpal-ID is 32 bits and is only checked for
/// uniqueness against *currently live* sessions, so ids are reused over time.
/// The birth instant is what makes the pair an identity rather than a
/// correlator — and it is already on every message, because `Session.created` is
/// derived from the X-Vorpal-ID `ts` parameter.
public final class SessionResolver {

	private SessionResolver() {
	}

	/// Find the session row for a correlator, or create a stub.
	///
	/// A stub is a real `sessions` row with the correlator and application set
	/// and `destroyed` left null — strictly more information than the old
	/// behaviour, which dropped the message. When the real `session.started`
	/// arrives it updates the same row rather than colliding with it.
	///
	/// @param em            an open entity manager inside the caller's transaction
	/// @param clusterName   the domain/cluster stamp
	/// @param vorpalId      the call correlator
	/// @param created       the call's birth instant, or null when unknown
	/// @param applicationId the reporting application instance
	/// @return the session's database primary key
	public static Long resolveOrCreate(EntityManager em, String clusterName, long vorpalId, Date created,
			long applicationId) {

		Session existing = find(em, clusterName, vorpalId, created);
		if (existing != null) {
			return existing.getId();
		}

		Session stub = new Session();
		stub.setClusterName(clusterName);
		stub.setVorpalId(vorpalId);
		stub.setApplicationId(applicationId);
		stub.setCreated((created != null) ? created : new Date());
		try {
			em.persist(stub);
			em.flush();
			return stub.getId();
		} catch (RuntimeException raced) {
			// Another member inserted the same session between the lookup and
			// the insert. The unique constraint is doing its job; re-read
			// rather than fail the message.
			em.clear();
			Session found = find(em, clusterName, vorpalId, created);
			if (found != null) {
				return found.getId();
			}
			throw raced;
		}
	}

	/// Look up by the full natural key when the birth instant is known, and fall
	/// back to the open-session correlator when it is not.
	///
	/// The fallback is what a message that predates this change looks like: it
	/// carries the correlator but no reliable birth instant, and matching the
	/// one open session for that correlator is exactly what the old code did.
	private static Session find(EntityManager em, String clusterName, long vorpalId, Date created) {
		if (created != null) {
			List<Session> exact = em
					.createQuery("SELECT s FROM Session s WHERE s.clusterName = :clusterName "
							+ "AND s.vorpalId = :vorpalId AND s.created = :created", Session.class)
					.setParameter("clusterName", clusterName)
					.setParameter("vorpalId", vorpalId)
					.setParameter("created", created)
					.setMaxResults(1)
					.getResultList();
			if (!exact.isEmpty()) {
				return exact.get(0);
			}
		}
		List<Session> open = em.createNamedQuery("Session.findOpen", Session.class)
				.setParameter("clusterName", clusterName)
				.setParameter("vorpalId", vorpalId)
				.setMaxResults(1)
				.getResultList();
		return open.isEmpty() ? null : open.get(0);
	}

	/// Close a session, creating it already-closed if the stop arrives first.
	///
	/// First stop wins: a second one finds no open row and does nothing, which
	/// matches the old behaviour and keeps `session_open_uk` meaningful.
	public static Long close(EntityManager em, String clusterName, long vorpalId, Date created, long applicationId,
			Timestamp destroyed) {

		Session existing = find(em, clusterName, vorpalId, created);
		if (existing == null) {
			// Stop before start. Create the row already closed rather than
			// discard the only record that this call happened.
			Session row = new Session();
			row.setClusterName(clusterName);
			row.setVorpalId(vorpalId);
			row.setApplicationId(applicationId);
			row.setCreated((created != null) ? created : new Date());
			row.setDestroyed(destroyed);
			em.persist(row);
			em.flush();
			return row.getId();
		}
		if (existing.getDestroyed() == null) {
			existing.setDestroyed(destroyed);
			em.merge(existing);
		}
		return existing.getId();
	}
}
