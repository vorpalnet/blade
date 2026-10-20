package org.vorpal.blade.framework.v3.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.deploy.ApplicationKey.Outcome;

class ApplicationKeyTest {

	public static class Keyed {
		@SipApplicationKey
		public static String key(SipServletRequest request) {
			return "k";
		}

		@SipApplicationKey
		public static String httpKey(HttpServletRequest request) {
			return "h";
		}
	}

	public static class KeyedChild extends Keyed {
	}

	public static class Unkeyed {
		public static String key(SipServletRequest request) {
			return "k";
		}
	}

	public static class NotStatic {
		@SipApplicationKey
		public String key(SipServletRequest request) {
			return "k";
		}
	}

	public static class Twice {
		@SipApplicationKey
		public static String a(SipServletRequest request) {
			return "a";
		}

		@SipApplicationKey
		public static String b(SipServletRequest request) {
			return "b";
		}
	}

	// The shape the container exposes: factory -> impl -> app -> descriptor.
	public static class Descriptor {
		private Method sipApplicationKeyMethod;
	}

	public static class App {
		final Descriptor descriptor = new Descriptor();

		public Descriptor getSipDeploymentDescriptor() {
			return descriptor;
		}
	}

	public static class Impl {
		final App app = new App();

		public App getCanaryContext() {
			return app;
		}
	}

	public static class Factory {
		final Impl impl = new Impl();

		public Impl getImpl() {
			return impl;
		}
	}

	private static ServletContext context(Object factory) {
		return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
				new Class<?>[] { ServletContext.class }, (proxy, method, args) -> {
					if (method.getName().equals("getAttribute") && "javax.servlet.sip.SipFactory".equals(args[0])) {
						return factory;
					}
					return null;
				});
	}

	@Test
	void findsTheSipKeyAndSkipsTheHttpOne() throws Exception {
		assertEquals(Keyed.class.getMethod("key", SipServletRequest.class), ApplicationKey.find(Keyed.class));
	}

	@Test
	void findsAnInheritedKey() throws Exception {
		assertEquals(Keyed.class.getMethod("key", SipServletRequest.class), ApplicationKey.find(KeyedChild.class));
	}

	@Test
	void noAnnotationNoKey() {
		assertNull(ApplicationKey.find(Unkeyed.class));
	}

	@Test
	void rejectsWhatTheContainerRejects() {
		assertThrows(IllegalStateException.class, () -> ApplicationKey.find(NotStatic.class));
		assertThrows(IllegalStateException.class, () -> ApplicationKey.find(Twice.class));
	}

	@Test
	void installsWhenTheScanLeftItEmpty() throws Exception {
		Factory factory = new Factory();
		assertEquals(Outcome.INSTALLED, ApplicationKey.install(context(factory), Keyed.class));
		assertEquals(Keyed.class.getMethod("key", SipServletRequest.class),
				factory.impl.app.descriptor.sipApplicationKeyMethod);
	}

	@Test
	void leavesTheScannedMethodAlone() throws Exception {
		Factory factory = new Factory();
		Method scanned = Twice.class.getMethod("a", SipServletRequest.class);
		factory.impl.app.descriptor.sipApplicationKeyMethod = scanned;
		assertEquals(Outcome.SCANNED, ApplicationKey.install(context(factory), Keyed.class));
		assertSame(scanned, factory.impl.app.descriptor.sipApplicationKeyMethod);
	}

	@Test
	void unkeyedServletTouchesNothing() {
		assertEquals(Outcome.NONE, ApplicationKey.install(context(null), Unkeyed.class));
	}

	@Test
	void anotherContainerIsUnreachable() {
		assertEquals(Outcome.UNREACHABLE, ApplicationKey.install(context(new Object()), Keyed.class));
		assertEquals(Outcome.UNREACHABLE, ApplicationKey.install(context(null), Keyed.class));
	}

}
