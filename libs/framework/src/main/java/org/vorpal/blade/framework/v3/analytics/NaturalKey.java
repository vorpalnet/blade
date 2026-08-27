package org.vorpal.blade.framework.v3.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/// Turns the facts that identify a row into the row's primary key.
///
/// **In the framework, not beside the entities, because two modules have to
/// agree with it.** The analytics service writes these keys and the analytics
/// console's sample-data generator writes rows over raw JDBC alongside it. A
/// second implementation of a hash that decides where a row lives is not a
/// duplication that drifts slowly — it is two tools writing the same call to
/// two different rows the first time either one changes.
///
/// Every table in this schema has a natural key already on the wire — an
/// application is `(name, domain, server, created)`, a session is
/// `(cluster_name, vorpal_id, created)`, an event is its CloudEvent id. This
/// class hashes that tuple to the 64-bit number the row is stored under, so
/// **the producer computes the key and the database generates nothing.**
///
/// ## Why not let the database assign it
///
/// It was tried, and it does not survive contact with both supported
/// databases. `GenerationType.IDENTITY` works on MySQL and is unimplementable
/// on Oracle — no EclipseLink Oracle platform reports native identity support,
/// so the provider silently substitutes a single default sequence shared by
/// every table, which then overflows the narrow lookup columns. Beyond the
/// provider bug, a generated key has to be read back before it can be used as
/// a foreign key, which forces a flush in the middle of a transaction and
/// leaves the writer holding an id whose row a later rollback may erase.
///
/// A computed key has none of that. It is known before the insert, identical
/// on every node without asking anyone, and stable across restarts — so a
/// redelivered event collides with itself on the primary key and is a no-op
/// instead of a duplicate row. This is the schema's original design (the first
/// `application` table computed its id "by a Java hash algorithm"), restored
/// and extended to every table.
///
/// ## An identity timestamp is not a time you read
///
/// Two of these keys take a timestamp, and it is worth being precise about
/// which one. A session's key includes the call's BIRTH INSTANT, from the
/// X-Vorpal-ID's `ts` parameter, because the Vorpal-ID itself is 32 bits and is
/// reused over time — the instant is what turns a correlator into an identity.
/// An application instance's key includes its start time for the same reason.
///
/// That is not the same thing as when something happened. An event carries its
/// own `occurredAt`, and a `callStarted` occurs strictly after the session it
/// belongs to was born; the schema keeps them in different columns
/// (`sessions.created` versus `events.created`) precisely so one cannot be
/// mistaken for the other. The identity timestamps are uniqueness material that
/// happens to look like a time. Nobody reports on them.
///
/// The rule that follows is the important one: **compute a key from the wire,
/// never from a stored value.** Nothing guarantees a timestamp round-trips
/// bit-for-bit through a column, a driver and a time zone, and a key recomputed
/// from a row can therefore fail to find the row it came from. Where a writer
/// needs the identity of something already stored, it reads that row's `id`
/// — which needs no derivation at all.
///
/// ## This is a wire contract
///
/// Two nodes writing the same call must compute the same key, and a node
/// running last year's release must agree with one running this year's.
/// **The algorithm below therefore cannot be changed** — not the digest, not
/// the encoding, not the framing — without every stored key becoming
/// unreachable. `NaturalKeyTest` pins known inputs to known outputs so an
/// accidental change fails the build rather than the database.
///
/// ## Collisions
///
/// 64 bits over the row counts this schema holds makes a collision
/// vanishingly unlikely, but "unlikely" is not "impossible" and silently
/// merging two calls into one row would be the worst possible outcome. Each
/// table therefore keeps a UNIQUE constraint on the natural-key columns
/// themselves, so a genuine collision surfaces as a constraint violation —
/// loudly, on the insert that caused it — rather than as two calls quietly
/// becoming one.
public final class NaturalKey {

	private NaturalKey() {
	}

	/// The key for a tuple of identifying values.
	///
	/// Parts are length-framed before hashing, so `("ab", "c")` and
	/// `("a", "bc")` are different keys. A null part is distinct from an empty
	/// one. [Date] and [Number] parts are converted canonically here rather
	/// than by the caller, because a caller that formats a timestamp its own
	/// way is a caller that computes a different key for the same row.
	///
	/// @param parts the natural-key values, in a fixed order the caller must
	///              never rearrange
	/// @return the row's primary key
	public static long idFor(Object... parts) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every Java implementation. If it is
			// genuinely absent the process cannot write a correct key, and
			// writing an incorrect one silently is worse than not starting.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		if (parts != null) {
			for (Object part : parts) {
				update(digest, canonical(part));
			}
		}
		byte[] hash = digest.digest();
		long id = 0L;
		for (int i = 0; i < 8; i++) {
			id = (id << 8) | (hash[i] & 0xFFL);
		}
		// Clear the sign bit rather than allowing negative keys. Both schemas
		// store this as a signed 64-bit column and a negative id is legal, but
		// keys show up in logs, URLs and support tickets, and a leading minus
		// invites someone to "fix" it. 63 bits is ample.
		return id & Long.MAX_VALUE;
	}

	/// The canonical text of one part, or null.
	///
	/// A [Date] becomes its epoch-millisecond count: the schema stores
	/// millisecond precision deliberately (a session's birth instant is part of
	/// its identity), and any text format would risk a locale or timezone
	/// changing the key for a row that has not changed.
	private static String canonical(Object part) {
		if (part == null) {
			return null;
		}
		if (part instanceof Date) {
			return Long.toString(((Date) part).getTime());
		}
		if (part instanceof Number) {
			return Long.toString(((Number) part).longValue());
		}
		return part.toString();
	}

	/// Frame one part into the digest: its length, then its bytes. Null is
	/// framed as a length of -1, which no real part can produce.
	private static void update(MessageDigest digest, String part) {
		byte[] bytes = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
		int length = part == null ? -1 : bytes.length;
		digest.update((byte) (length >>> 24));
		digest.update((byte) (length >>> 16));
		digest.update((byte) (length >>> 8));
		digest.update((byte) length);
		digest.update(bytes);
	}
}
