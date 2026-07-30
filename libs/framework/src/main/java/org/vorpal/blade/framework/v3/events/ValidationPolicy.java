package org.vorpal.blade.framework.v3.events;

/// What the ingress does when a published payload fails its event type's schema.
///
/// Three settings rather than a boolean, and the middle one is the point.
/// Switching validation on against live traffic is exactly the change nobody
/// dares make, because a mistake in the catalog starts rejecting real events.
/// [#WARN] makes the change safe: turn it on, watch the logs for a day, see
/// whether the schema or the producer is wrong, *then* move to [#REJECT].
/// Without it, the honest operator choice is to leave validation off forever.
public enum ValidationPolicy {

	/// Do not validate. Payloads pass through untouched — the behavior before
	/// the catalog existed.
	OFF,

	/// Validate and log a warning naming the failing field, but publish the
	/// event anyway. The setting to run in while a schema is being proven.
	WARN,

	/// Validate and refuse: the ingress returns 400 naming the failing field and
	/// the event is not published.
	REJECT
}
