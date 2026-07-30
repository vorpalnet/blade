package org.vorpal.blade.services.analytics.jms;

import java.util.Date;
import java.util.List;

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
	/// @param host    hostname, recorded on creation only
	/// @param tenant  tenant code, recorded on creation only
	/// @return the application's database primary key
	public static Long resolveOrCreate(EntityManager em, String name, String domain, String server, Date started,
			String host, String tenant) {

		Application existing = find(em, name, domain, server, started);
		if (existing != null) {
			return Long.valueOf(existing.getId());
		}

		Application row = new Application();
		row.setName(name);
		row.setDomain(domain);
		row.setServer(server);
		row.setCreated((started != null) ? started : new Date());
		row.setHost(host);
		row.setTenant(tenant);

		try {
			em.persist(row);
			em.flush();
			return Long.valueOf(row.getId());
		} catch (RuntimeException raced) {
			// Another cluster member inserted the same instance between the
			// lookup and the insert — every member of a cluster publishes its own
			// application.started, and two of them can land together.
			// `application_natural_uk` is doing its job; re-read rather than fail
			// the message.
			em.clear();
			Application found = find(em, name, domain, server, started);
			if (found != null) {
				return Long.valueOf(found.getId());
			}
			throw raced;
		}
	}

	/// Record an instance's stop.
	///
	/// Creates the row if the stop somehow arrives without a start, for the same
	/// reason [SessionResolver#close] does: the only record that this instance
	/// existed is worth more than the tidiness of refusing it.
	public static Long close(EntityManager em, String name, String domain, String server, Date started, Date stopped) {
		Application existing = find(em, name, domain, server, started);
		if (existing == null) {
			Application row = new Application();
			row.setName(name);
			row.setDomain(domain);
			row.setServer(server);
			row.setCreated((started != null) ? started : new Date());
			row.setDestroyed(stopped);
			em.persist(row);
			em.flush();
			return Long.valueOf(row.getId());
		}
		if (existing.getDestroyed() == null) {
			existing.setDestroyed(stopped);
			em.merge(existing);
		}
		return Long.valueOf(existing.getId());
	}

	private static Application find(EntityManager em, String name, String domain, String server, Date started) {
		if (name == null || started == null) {
			return null;
		}
		List<Application> found = em.createNamedQuery(Application.NATURAL_KEY, Application.class)
				.setParameter("name", name)
				.setParameter("domain", domain)
				.setParameter("server", server)
				.setParameter("created", started)
				.setMaxResults(1)
				.getResultList();
		return found.isEmpty() ? null : found.get(0);
	}
}
