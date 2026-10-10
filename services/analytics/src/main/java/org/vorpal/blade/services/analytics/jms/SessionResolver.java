package org.vorpal.blade.services.analytics.jms;

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
		return resolveOrCreate(em, clusterName, vorpalId, created, applicationId, null);
	}

	/// As [#resolveOrCreate(EntityManager, String, long, Date, long)], filing
	/// the call under `tenant` if its row has none yet: the first tenant the
	/// call is heard with is the one it opened under.
	///
	/// @param tenant the call's tenant from the event, or null
	public static Long resolveOrCreate(EntityManager em, String clusterName, long vorpalId, Date created,
			long applicationId, String tenant) {

		if (created == null) {
			// No birth instant on the wire, so no key can be computed. Adopt
			// the correlator's open session BY ITS ID — see #openSessionId.
			Long open = openSessionId(em, clusterName, vorpalId);
			if (open != null) {
				adoptTenant(em, em.find(Session.class, open), tenant);
				return open;
			}
			created = new Date();
		}
		Date birth = created;
		long id = Session.idFor(clusterName, vorpalId, birth);

		Session existing = em.find(Session.class, id);
		if (existing != null) {
			adoptTenant(em, existing, tenant);
			return id;
		}

		Session stub = new Session();
		stub.setId(id);
		stub.setClusterName(clusterName);
		stub.setVorpalId(vorpalId);
		stub.setApplicationId(applicationId);
		stub.setCreated(birth);
		stub.setTenant(tenant);
		return insert(em, stub, id);
	}

	/// Close a session, creating it already-closed if the stop arrives first.
	///
	/// **The latest stop wins.** A session is the call through the whole
	/// system, and every application on it sends its own stop as it lets go, so
	/// the last of them is when the call ended. Latest by `destroyed`, not by
	/// arrival: stops from different nodes reach the bus in any order, and an
	/// earlier one arriving late must not pull the end back.
	public static Long close(EntityManager em, String clusterName, long vorpalId, Date created, long applicationId,
			Date destroyed) {

		if (created == null) {
			Long open = openSessionId(em, clusterName, vorpalId);
			if (open != null) {
				extend(em, em.find(Session.class, open), destroyed);
				return open;
			}
			created = new Date();
		}
		Date birth = created;
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
		extend(em, existing, destroyed);
		return id;
	}

	/// File a session under `tenant` if it has none. Never moves a call from
	/// one tenant to another: the first one heard stands.
	private static void adoptTenant(EntityManager em, Session row, String tenant) {
		if (row != null && tenant != null && row.getTenant() == null) {
			row.setTenant(tenant);
			em.merge(row);
		}
	}

	/// Move a session's end to `destroyed` if that is later than the end it has.
	private static void extend(EntityManager em, Session row, Date destroyed) {
		if (row != null && (row.getDestroyed() == null || destroyed.after(row.getDestroyed()))) {
			row.setDestroyed(destroyed);
			em.merge(row);
		}
	}

	/// The id of the correlator's one open session, or null when there is none.
	///
	/// **Returns the id, deliberately, and not the row's birth instant.** This
	/// used to hand back `created` for the caller to hash, which meant deriving
	/// a key from a timestamp that had been through the database and back.
	///
	/// That is the one rule this schema's keys depend on. A key is computed
	/// from the WIRE — see [org.vorpal.blade.framework.v3.analytics.NaturalKey]
	/// — because nothing guarantees a stored timestamp round-trips
	/// bit-for-bit through a column, a driver and a time zone. When it does
	/// not, the recomputed id misses the very row it was read from, the insert
	/// that follows collides on the natural-key constraint instead, and the
	/// message fails for a reason that looks nothing like the cause.
	///
	/// The row's own id needs no derivation. It is already stored.
	private static Long openSessionId(EntityManager em, String clusterName, long vorpalId) {
		List<Session> open = em.createNamedQuery("Session.findOpen", Session.class)
				.setParameter("clusterName", clusterName)
				.setParameter("vorpalId", vorpalId)
				.setMaxResults(1)
				.getResultList();
		return open.isEmpty() ? null : Long.valueOf(open.get(0).getId());
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
			// Flush so this row exists before anything referencing it is
			// written. **This is about ordering, not about reading the key
			// back** — the key was known before the insert. These entities
			// declare no JPA relationships (see persistence.xml: every foreign
			// key is a plain column the writer populates), so the provider has
			// no dependency graph to order inserts by and is free to write an
			// event before the session it points at. Dropping this flush cost
			// a live run to ORA-02291 on EVENT_FK2.
			em.flush();
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
