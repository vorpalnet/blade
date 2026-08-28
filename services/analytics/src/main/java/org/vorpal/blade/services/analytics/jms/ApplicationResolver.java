package org.vorpal.blade.services.analytics.jms;

import java.util.Date;

import javax.persistence.EntityManager;

import org.vorpal.blade.services.analytics.model.Application;

/// Resolves an `applications` row from what an application instance *is*,
/// creating it if it is not there yet.
///
/// **Why this replaced a producer-minted id.** The producer used to generate
/// `application_id` itself — `ThreadLocalRandom.nextLong()`, with a stated
/// ~1e-11 collision risk noted in the schema — and put it on the wire. That is a
/// surrogate primary key invented by the one participant in the system that has
/// no database, and it is the same mistake the session correlator already
/// stopped making. `(name, domain, server, created)` says exactly what an
/// instance is: one app, on one server, with one configuration, where a restart
/// is a new instance. It is already on every call-scoped event, so there is
/// nothing extra to carry.
///
/// **`created` is millisecond-precise, and that is load-bearing.** The wire
/// carries ISO-8601 instants with milliseconds. A `DATETIME` column without
/// fractional seconds truncates on write, and the lookup below then compares an
/// un-truncated `Date` against the truncated column and never matches — so every
/// event would insert a fresh application row. The schema declares
/// `DATETIME(3)`; see the note in `MySQL-database-schema.sql`.
///
/// Mirrors [SessionResolver], including the re-read on a lost insert race.
public final class ApplicationResolver {

	private ApplicationResolver() {
	}

	/// Find the row for an application instance, or create it.
	///
	/// @param em      an open entity manager inside the caller's transaction
	/// @param name    the deployed application name
	/// @param domain  the WebLogic domain
	/// @param server  the server this instance runs on
	/// @param started when this instance started — the rest of its identity
	/// @param host    hostname, or null when the caller does not know it
	/// @param tenant  tenant code, or null when the caller does not know it
	/// @param version application version, or null when the caller does not know it
	/// @return the application's database primary key
	public static Long resolveOrCreate(EntityManager em, String name, String domain, String server, Date started,
			String host, String tenant, String version) {

		Date created = (started != null) ? started : new Date();
		long id = Application.idFor(name, domain, server, created);

		Application existing = em.find(Application.class, id);
		if (existing != null) {
			backfill(em, existing, host, tenant, version);
			return Long.valueOf(id);
		}

		Application row = new Application();
		row.setId(id);
		row.setName(name);
		row.setDomain(domain);
		row.setServer(server);
		row.setCreated(created);
		row.setHost(host);
		row.setTenant(tenant);
		row.setVersion(version);
		return insert(em, row, id);
	}

	/// Fill in instance facts that were not known when the row was created.
	///
	/// **Creation order is not publication order.** `host`, `tenant` and
	/// `version` ride only on `application.started`; a call-scoped event carries
	/// the instance's *identity* but none of its description. So whichever event
	/// reaches the sink first wins the insert, and when that is a call event the
	/// row is created with those three columns null. Before this, they stayed
	/// null forever — the later `application.started` found the row present and
	/// returned early — which is why live `applications` rows had a name and a
	/// server but no host at all.
	///
	/// Only null columns are written, so a call event arriving after the
	/// application event (the common order) cannot blank out what is already
	/// known. Identity columns are never touched: a differing value there is a
	/// different instance with a different key, not an update to this one.
	private static void backfill(EntityManager em, Application row, String host, String tenant, String version) {
		boolean changed = false;
		if (row.getHost() == null && host != null) {
			row.setHost(host);
			changed = true;
		}
		if (row.getTenant() == null && tenant != null) {
			row.setTenant(tenant);
			changed = true;
		}
		if (row.getVersion() == null && version != null) {
			row.setVersion(version);
			changed = true;
		}
		if (changed) {
			em.merge(row);
		}
	}

	/// Record an instance's stop.
	///
	/// Creates the row if the stop somehow arrives without a start, for the same
	/// reason [SessionResolver#close] does: the only record that this instance
	/// existed is worth more than the tidiness of refusing it.
	public static Long close(EntityManager em, String name, String domain, String server, Date started, Date stopped) {
		Date created = (started != null) ? started : new Date();
		long id = Application.idFor(name, domain, server, created);

		Application existing = em.find(Application.class, id);
		if (existing == null) {
			// A stop whose start this node never saw. `started` is on the wire
			// for exactly this reason, so the key still resolves to the row the
			// instance would have had.
			Application row = new Application();
			row.setId(id);
			row.setName(name);
			row.setDomain(domain);
			row.setServer(server);
			row.setCreated(created);
			row.setDestroyed(stopped);
			return insert(em, row, id);
		}
		if (existing.getDestroyed() == null) {
			existing.setDestroyed(stopped);
			em.merge(existing);
		}
		return Long.valueOf(id);
	}

	/// Insert a row whose key is already known. A duplicate-key failure means
	/// another cluster member wrote the identical row first — every member of a
	/// cluster publishes its own `application.started` — so the id is returned
	/// rather than the message failed.
	private static Long insert(EntityManager em, Application row, long id) {
		try {
			em.persist(row);
			// Ordering, not key retrieval — see the same flush in
			// [SessionResolver#insert] for why it is load-bearing.
			em.flush();
			return Long.valueOf(id);
		} catch (RuntimeException raced) {
			em.clear();
			if (em.find(Application.class, id) != null) {
				return Long.valueOf(id);
			}
			throw raced;
		}
	}
}
