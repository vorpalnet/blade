package org.vorpal.blade.framework.v3.events;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import javax.jms.Destination;

/// Discovers the physical members behind a distributed JMS destination.
///
/// ## Why this exists
///
/// A durable subscription cannot be created on the *logical* name of a
/// partitioned distributed topic — the broker refuses it outright:
///
/// ```
/// [JMSClientExceptions:055030] This topic does not support durable subscriptions.
/// ```
///
/// A message-driven bean does not hit this because the container subscribes to
/// each physical member on the bean's behalf; that is what its
/// `distributedDestinationConnection=EveryMember` setting means. Owning the
/// subscription in application code means doing the same thing explicitly, and
/// doing it requires knowing what the members are — which is what this returns.
///
/// ## Why reflection
///
/// The API lives in `weblogic.jms.extensions`, which is not carried by any
/// artifact this repository installs: `weblogic-server` resolves to a
/// manifest-only pointer jar, and the OCCAS jars `bootstrap.sh` installs
/// (`wlss`, `wlssapi`) do not contain it. Compiling against it directly would
/// add a build prerequisite to this repository and to every repository that
/// consumes the framework.
///
/// That trade is not worth making for one optional capability, because of where
/// this code ends up: the framework jar ships **inside every BLADE WAR**, so a
/// hard reference to a class the container may not expose is a deployment that
/// fails to start rather than a feature that is missing. Reflection keeps the
/// dependency at runtime, where it can be absent without consequence —
/// [#register] simply returns null and the caller stays on the single-consumer
/// path.
///
/// If the JMS extensions are ever installed as a first-class artifact, replacing
/// the bodies here with direct calls is mechanical and nothing else changes.
final class DistributedMembers {

	private static final String HELPER = "weblogic.jms.extensions.JMSDestinationAvailabilityHelper";
	private static final String LISTENER = "weblogic.jms.extensions.DestinationAvailabilityListener";
	private static final String DETAIL = "weblogic.jms.extensions.DestinationDetail";
	private static final String HANDLE = "weblogic.jms.extensions.RegistrationHandle";

	private DistributedMembers() {
	}

	/// Told about members as the cluster makes them available and takes them
	/// away. Members come and go with server restarts and migration, so this is
	/// a subscription to a changing set, not a one-time query.
	interface Listener {

		/// @param memberName a stable name for this member, unique within the
		///                   distributed destination
		/// @param member     the member's own destination, which a durable
		///                   subscriber can be created on
		void onAvailable(String memberName, Destination member);

		/// The member is gone; whatever was consuming it should stop.
		void onUnavailable(String memberName);
	}

	/// Start watching a distributed destination's members.
	///
	/// @param jndiName the distributed destination's JNDI name
	/// @param listener told about each member
	/// @return an opaque handle to pass to [#unregister], or **null** when the
	///         extensions are unavailable or the destination is not
	///         distributed — in which case the caller must fall back to the
	///         logical destination
	static Object register(String jndiName, Listener listener) {
		try {
			ClassLoader loader = DistributedMembers.class.getClassLoader();
			Class<?> helperClass = Class.forName(HELPER, true, loader);
			Class<?> listenerClass = Class.forName(LISTENER, true, loader);
			Class<?> detailClass = Class.forName(DETAIL, true, loader);

			Object helper = helperClass.getMethod("getInstance").invoke(null);
			Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { listenerClass },
					new Adapter(listener, detailClass));

			Method register = helperClass.getMethod("register", java.util.Hashtable.class, String.class,
					listenerClass);
			// A null environment means "this server": right on an engine, whose
			// cluster hosts the members. On the AdminServer the topic is not in
			// this server's tree at all, and the watcher fails without a word, so
			// it looks where the subscriber's own lookups do ([EventBus#environment]).
			return register.invoke(helper, EventBus.environment(), jndiName, proxy);
		} catch (Throwable notAvailable) {
			return null;
		}
	}

	/// Stop watching. Safe to call with null.
	///
	/// Called through the public `RegistrationHandle` interface, never the handle's own class: the
	/// container returns a non-public class, and a reflective call on its method fails with an
	/// access error even though the method is public. That failure was swallowed, so no watcher was
	/// ever removed. The container kept each one, with its listener, which is a class of the
	/// application, so every undeployed copy of an application that subscribes stayed loaded:
	/// about 125 MB per redeploy of one application until the server ran out of memory.
	static void unregister(Object handle) {
		if (handle == null) {
			return;
		}
		try {
			Class<?> handleClass = Class.forName(HANDLE, true, handle.getClass().getClassLoader());
			handleClass.getMethod("unregister").invoke(handle);
		} catch (Throwable failed) {
			// A watcher that will not go must not stop a subscriber shutting down; it does keep the
			// application loaded, though, so say so.
			java.util.logging.Logger.getLogger(DistributedMembers.class.getName()).warning(
					"could not stop watching a distributed destination's members: " + failed);
		}
	}

	/// Bridges the container's listener interface to [Listener].
	private static final class Adapter implements InvocationHandler {

		private final Listener target;
		private final Class<?> detailClass;

		private Adapter(Listener target, Class<?> detailClass) {
			this.target = target;
			this.detailClass = detailClass;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
			case "onDestinationsAvailable":
				for (Object detail : details(args)) {
					String name = name(detail);
					Destination destination = destination(detail);
					if (name != null && destination != null) {
						target.onAvailable(name, destination);
					}
				}
				return null;
			case "onDestinationsUnavailable":
				for (Object detail : details(args)) {
					String name = name(detail);
					if (name != null) {
						target.onUnavailable(name);
					}
				}
				return null;
			case "onFailure":
				// Nothing useful to do here: members simply stay as they are,
				// and the container retries. Reporting it would need a logger
				// this class deliberately does not hold.
				return null;
			case "toString":
				return "DistributedMembers.Listener";
			case "hashCode":
				return Integer.valueOf(System.identityHashCode(proxy));
			case "equals":
				return Boolean.valueOf(proxy == args[0]);
			default:
				return null;
			}
		}

		@SuppressWarnings("unchecked")
		private static List<Object> details(Object[] args) {
			if (args == null || args.length < 2 || !(args[1] instanceof List)) {
				return java.util.Collections.emptyList();
			}
			return (List<Object>) args[1];
		}

		private String name(Object detail) {
			try {
				return (String) detailClass.getMethod("getMemberConfigName").invoke(detail);
			} catch (Throwable t) {
				return null;
			}
		}

		private Destination destination(Object detail) {
			try {
				return (Destination) detailClass.getMethod("getDestination").invoke(detail);
			} catch (Throwable t) {
				return null;
			}
		}
	}
}
