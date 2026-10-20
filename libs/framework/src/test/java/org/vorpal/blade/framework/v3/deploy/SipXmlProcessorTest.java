package org.vorpal.blade.framework.v3.deploy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.servlet.sip.annotation.SipServlet;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SipXmlProcessorTest {

	@TempDir
	Path dir;

	private static JavaFileObject source(String className, String code) {
		return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"),
				JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return code;
			}
		};
	}

	/// Compiles one class with the processor; returns the sip.xml, or null.
	private String compile(String className, String code, StringWriter diagnostics) throws Exception {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		String classpath = new File(SipServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.getPath();
		Path webinf = dir.resolve("WEB-INF");
		JavaCompiler.CompilationTask task = javac.getTask(diagnostics, null, null,
				List.of("-classpath", classpath, "-d", dir.resolve("classes").toString(), "-proc:only",
						"-A" + SipXmlProcessor.OPTION + "=" + webinf),
				null, List.of(source(className, code)));
		task.setProcessors(List.of(new SipXmlProcessor()));
		boolean ok = task.call();
		Path file = webinf.resolve("sip.xml");
		return ok && Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
	}

	@Test
	void unnamedApplicationLeavesTheNameToTheContainer() throws Exception {
		String xml = compile("p.App", "package p;\n" //
				+ "@javax.servlet.sip.annotation.SipApplication(distributable = true)\n"
				+ "@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)\n"
				+ "@javax.servlet.sip.annotation.SipListener\n" //
				+ "public class App {}\n", new StringWriter());
		assertTrue(xml.contains("<distributable />"), xml);
		assertTrue(xml.contains("<javaee:listener-class>p.App</javaee:listener-class>"), xml);
		assertTrue(xml.contains("<javaee:servlet-name>App</javaee:servlet-name>"), xml);
		assertTrue(xml.contains("<javaee:load-on-startup>1</javaee:load-on-startup>"), xml);
		assertTrue(xml.contains("<main-servlet>App</main-servlet>"), xml);
		assertFalse(xml.contains("app-name"), xml);
		assertFalse(xml.contains("display-name"), xml);
	}

	@Test
	void namedApplicationGetsADisplayNameToo() throws Exception {
		String xml = compile("p.App", "package p;\n" //
				+ "@javax.servlet.sip.annotation.SipApplication(name = \"block\", sessionTimeout = 10)\n"
				+ "@javax.servlet.sip.annotation.SipServlet\n" //
				+ "public class App {}\n", new StringWriter());
		assertTrue(xml.contains("<app-name>block</app-name>"), xml);
		assertTrue(xml.contains("<javaee:display-name>block</javaee:display-name>"), xml);
		assertTrue(xml.contains("<javaee:session-timeout>10</javaee:session-timeout>"), xml);
		assertFalse(xml.contains("distributable"), xml);
		assertFalse(xml.contains("load-on-startup"), xml);
	}

	@Test
	void anAttributeSipXmlCannotCarryFailsTheCompile() throws Exception {
		StringWriter diagnostics = new StringWriter();
		String xml = compile("p.App", "package p;\n" //
				+ "@javax.servlet.sip.annotation.SipApplication(concurrencyMode = javax.servlet.sip.ConcurrencyMode.APPLICATIONSESSION)\n"
				+ "@javax.servlet.sip.annotation.SipServlet\n" //
				+ "public class App {}\n", diagnostics);
		assertTrue(xml == null, xml);
		assertTrue(diagnostics.toString().contains("concurrencyMode"), diagnostics.toString());
	}

	@Test
	void noSipServletNoFile() throws Exception {
		String xml = compile("p.Plain", "package p;\npublic class Plain {}\n", new StringWriter());
		assertTrue(xml == null, xml);
	}

}
