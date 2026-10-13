package org.vorpal.blade.framework.v3.net.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/// The BLADE agent: shows the dashboard the network calls an application's
/// own code makes, whatever library it makes them with.
///
/// Loaded with the server, `-javaagent:/path/to/blade-agent.jar`. It adds a
/// report to JDK network methods ([Advices]): every TCP connect through
/// `java.net.Socket`, an NIO `SocketChannel` or an NIO.2
/// `AsynchronousSocketChannel` (on Unix), which covers any library's
/// "connection refused" and "timed out", and every HTTP exchange through the
/// JDK's two clients, `HttpURLConnection` and `java.net.http.HttpClient`, which
/// gives the status. What it finds goes to [org.vorpal.blade.framework.v3.net.agent.boot.NetEvents],
/// per application, and the framework's `Remotes` MBean serves it beside what
/// the framework records itself.
///
/// **Nothing else changes.** The added code reports and returns; a failure
/// inside it is swallowed. The container's own connections (SIP, JMS, JDBC
/// pools) are not recorded.
public final class BladeAgent {

	/// The classes JDK code calls, which must be on the boot class path.
	static final String[] BOOT_CLASSES = { "NetEvents", "NetEvents$App", "NetEvents$Rec", "NetEvents$Pending",
			"NetEvents$Done" };

	static final String BOOT_PACKAGE = "org.vorpal.blade.framework.v3.net.agent.boot.";

	/// The JDK classes rewritten ([Advices]).
	static final String[] TARGETS = { "java.net.Socket", "sun.nio.ch.SocketChannelImpl",
			"sun.nio.ch.UnixAsynchronousSocketChannelImpl", "sun.net.www.protocol.http.HttpURLConnection",
			"jdk.internal.net.http.HttpClientImpl" };

	private static volatile boolean installed;

	private BladeAgent() {
	}

	public static void premain(String args, Instrumentation inst) {
		install(inst);
	}

	public static void agentmain(String args, Instrumentation inst) {
		install(inst);
	}

	/// Puts [NetEvents] on the boot class path, lets the modules whose classes
	/// are rewritten read it, and rewrites them. Once per JVM. Never throws: a
	/// server must start whether or not its agent could.
	public static synchronized void install(Instrumentation inst) {
		if (installed) {
			return;
		}
		try {
			inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar()));
			Module boot = Class.forName(BOOT_PACKAGE + "NetEvents", false, null).getModule();
			Set<Module> reads = Collections.singleton(boot);
			for (Module m : new Module[] { Object.class.getModule(), moduleOf("java.net.http.HttpClient") }) {
				if (m != null) {
					inst.redefineModule(m, reads, Collections.emptyMap(), Collections.emptyMap(),
							Collections.emptySet(), Collections.emptyMap());
				}
			}

			new AgentBuilder.Default()
					.disableClassFormatChanges()
					.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
					.with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
					.with(AgentBuilder.TypeStrategy.Default.REDEFINE)
					.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
					.ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("org.vorpal.blade.framework.v3.net.agent.")))
					.type(named("java.net.Socket"))
					.transform((b, type, loader, module, domain) -> b.visit(Advice.to(Advices.SocketConnect.class)
							.on(named("connect").and(takesArguments(2)))))
					.type(named("sun.nio.ch.SocketChannelImpl"))
					.transform((b, type, loader, module, domain) -> b
							.visit(Advice.to(Advices.ChannelConnect.class).on(named("connect").and(takesArguments(1))))
							.visit(Advice.to(Advices.ChannelFinish.class).on(named("finishConnect").and(takesArguments(0)))))
					.type(named("sun.nio.ch.UnixAsynchronousSocketChannelImpl"))
					.transform((b, type, loader, module, domain) -> b
							.visit(Advice.to(Advices.AsyncConnect.class).on(named("implConnect").and(takesArguments(3))))
							.visit(Advice.to(Advices.AsyncFinish.class).on(named("finishConnect").and(takesArguments(1))))
							.visit(Advice.to(Advices.AsyncClose.class).on(named("implClose").and(takesArguments(0)))))
					.type(named("sun.net.www.protocol.http.HttpURLConnection"))
					.transform((b, type, loader, module, domain) -> b.visit(Advice.to(Advices.HttpUrlConnectionInput.class)
							.on(named("getInputStream").and(takesArguments(0)).and(not(isConstructor())))))
					.type(named("jdk.internal.net.http.HttpClientImpl"))
					.transform((b, type, loader, module, domain) -> b.visit(Advice.to(Advices.HttpClientSend.class)
							.on(named("sendAsync").and(takesArguments(4)))))
					.installOn(inst);
			// Retransform each target by name as well. Loaded with the server
			// (-javaagent) they are rewritten as they load; attached to a JVM
			// already running, one may have been loaded before the transformer
			// arrived and missed by the sweep of loaded classes.
			for (String target : TARGETS) {
				try {
					Class<?> c = Class.forName(target, false, ClassLoader.getPlatformClassLoader());
					if (inst.isModifiableClass(c)) {
						inst.retransformClasses(c);
					}
				} catch (ClassNotFoundException | LinkageError absent) {
					// not in this JDK
				}
			}
			installed = true;
			System.out.println("BLADE agent: recording the connections and HTTP calls applications make");
		} catch (Throwable t) {
			System.err.println("BLADE agent not installed: " + t);
		}
	}

	/// The module of a JDK class, whichever built-in loader defines it
	/// (`java.net.http` is the platform loader's, not the boot loader's).
	private static Module moduleOf(String className) {
		try {
			return Class.forName(className, false, ClassLoader.getPlatformClassLoader()).getModule();
		} catch (ClassNotFoundException absent) {
			return null;
		}
	}

	/// A temporary JAR of the [#BOOT_CLASSES], read from this JAR's own
	/// resources, for the boot class path.
	private static File bootJar() throws IOException {
		File jar = File.createTempFile("blade-agent-boot", ".jar");
		jar.deleteOnExit();
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
			for (String name : BOOT_CLASSES) {
				String path = (BOOT_PACKAGE + name).replace('.', '/') + ".class";
				try (InputStream in = BladeAgent.class.getClassLoader().getResourceAsStream(path)) {
					if (in == null) {
						throw new IOException("missing " + path);
					}
					out.putNextEntry(new JarEntry(path));
					out.write(in.readAllBytes());
					out.closeEntry();
				}
			}
		}
		return jar;
	}

	static boolean isInstalled() {
		return installed;
	}
}
