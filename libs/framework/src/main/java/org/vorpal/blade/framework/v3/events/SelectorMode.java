package org.vorpal.blade.framework.v3.events;

/// How a subscription decides which events it handles: at the broker, or in its
/// own code.
///
/// The two fail in opposite directions, which is the whole reason both exist.
public enum SelectorMode {

	/// Build a JMS message selector from the subscription's declared types. The
	/// broker filters, and the app never wakes for an event it would ignore.
	///
	/// **Fails closed.** A type nobody added to the subscription is never
	/// enqueued — which looks exactly like "no events yet", the silent no-op the
	/// catalog exists to abolish. Right for an actor, whose type list is a
	/// deliberate, reviewed statement of what it handles.
	DERIVED,

	/// Take everything on the destination and decide in code.
	///
	/// **Fails open.** A type declared today reaches this subscriber today, with
	/// no regeneration and no redeploy. The cost is real and worth stating: the
	/// subscription's store holds every event, including the ones the code will
	/// drop, and that draws on the destination's quota. Right for a sink, whose
	/// job is to miss nothing.
	NONE
}
