package org.vorpal.blade.framework.v3.deploy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.servlet.ServletContext;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationKey;

/// Hands the container a servlet's `@SipApplicationKey` method when its annotation
/// scan did not find it.
///
/// OCCAS learns an application's key method only by scanning the deployment for
/// annotations, and that scan loads every class on the application's class path,
/// shared libraries included, into the application's own loader. On a server
/// started with `-Dwlss.parse.annotations=false` the scan is skipped, the classes
/// stay unloaded, and `sip.xml` declares the servlets and listeners instead. The
/// SIP deployment descriptor has no element for the application key, though, so
/// without this every request to an application such as the proxy-registrar would
/// get a fresh application session.
///
/// The container keeps the key method in its per-application deployment
/// descriptor and reads it again on every initial request; nothing copies it at
/// deploy time. This reaches that descriptor through the SIP factory and sets the
/// method there, by reflection, so nothing here compiles against the container.
/// The annotation itself is still read: it stays on the method at run time whether
/// or not anything scanned for it.
///
/// [AsyncSipServlet][org.vorpal.blade.framework.AsyncSipServlet] calls
/// [#install] from `init`, which the container runs for a load-on-startup servlet
/// before it tells the application router the application exists, so the key is
/// in place before the first request can reach it. When the scan did run, the
/// container has already set its own method and this leaves it alone.
public final class ApplicationKey {

	/// What [#install] did.
	public enum Outcome {
		/// The servlet declares no `@SipApplicationKey` method.
		NONE,
		/// The container already had a key method, from its own scan.
		SCANNED,
		/// The scan was off; this installed the servlet's method.
		INSTALLED,
		/// The container's descriptor was not where this expects it.
		UNREACHABLE
	}

	private static final String FIELD = "sipApplicationKeyMethod";

	private ApplicationKey() {
	}

	/// The servlet class's `@SipApplicationKey` method for SIP requests, or null.
	///
	/// @throws IllegalStateException when the method breaks the rules the
	///                               container's own scan enforces at deploy time,
	///                               or when there are two
	public static Method find(Class<?> servletClass) {
		Method found = null;
		for (Method m : servletClass.getMethods()) {
			if (!m.isAnnotationPresent(SipApplicationKey.class)) {
				continue;
			}
			Class<?>[] params = m.getParameterTypes();
			if (params.length != 1 || !SipServletRequest.class.isAssignableFrom(params[0])) {
				continue; // an HTTP key method, which BLADE does not use
			}
			if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class) {
				throw new IllegalStateException(
						"@SipApplicationKey " + m + " must be public static String method(SipServletRequest)");
			}
			if (found != null) {
				throw new IllegalStateException("two @SipApplicationKey methods: " + found + " and " + m);
			}
			found = m;
		}
		return found;
	}

	/// Sets the servlet class's key method on the container, unless it has one.
	public static Outcome install(ServletContext context, Class<?> servletClass) {
		Method key = find(servletClass);
		if (key == null) {
			return Outcome.NONE;
		}
		try {
			Object factory = context.getAttribute("javax.servlet.sip.SipFactory");
			Object impl = call(factory, "getImpl");
			Object app = call(impl, "getCanaryContext");
			Object descriptor = call(app, "getSipDeploymentDescriptor");
			Field field = descriptor.getClass().getDeclaredField(FIELD);
			field.setAccessible(true);
			if (field.get(descriptor) != null) {
				return Outcome.SCANNED;
			}
			field.set(descriptor, key);
			return Outcome.INSTALLED;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return Outcome.UNREACHABLE;
		}
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

}
