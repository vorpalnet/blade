package org.vorpal.blade.framework.v3.deploy;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

/// Writes an application's `sip.xml` at compile time from its `@SipApplication`,
/// `@SipServlet` and `@SipListener` annotations.
///
/// OCCAS finds those annotations by scanning the deployment, and the scan loads
/// every class on the application's class path into the application's own
/// loader: a SIP application loads about five times the classes a web
/// application on the same shared library does. A server started with
/// `-Dwlss.parse.annotations=false` skips the scan and reads `sip.xml` instead,
/// so every SIP application needs one. This writes it, so the annotations stay
/// the only place an application says what it is. With the scan on, the
/// container merges the two by servlet name, so the same WAR deploys either way.
///
/// `@SipApplicationKey` has no `sip.xml` element; [ApplicationKey] covers it at
/// run time.
///
/// The parent POM's compiler runs this for every module (`<proc>full</proc>`)
/// and passes `-Ablade.webinf`, the WAR plugin's staging `WEB-INF`; the file
/// goes straight there and the WAR plugin packages it with the rest. A module
/// without `@SipServlet` gets nothing. An annotation
/// attribute that `sip.xml` cannot carry fails the compile rather than being
/// dropped, since the application would behave differently with the scan off.
///
/// Leaves `<app-name>` out unless `@SipApplication` names the application. Given
/// an `<app-name>`, the container ignores an unnamed `@SipApplication`, sets no
/// display name, and `getServletContextName()` returns null; without one it uses
/// the deployment name for both, exactly as it does from the annotations alone.
@SupportedOptions(SipXmlProcessor.OPTION)
// "*", not the three SIP annotations: javac warns about -Ablade.webinf in every
// module where no processor ran, and this one claims nothing, so others still see all.
@SupportedAnnotationTypes("*")
public final class SipXmlProcessor extends AbstractProcessor {

	/// The processor option naming the `WEB-INF` directory to write into.
	public static final String OPTION = "blade.webinf";

	private Element application;
	private final List<TypeElement> servlets = new ArrayList<>();
	private final List<TypeElement> listeners = new ArrayList<>();

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
		for (Element e : round.getElementsAnnotatedWith(SipApplication.class)) {
			if (application != null) {
				error(e, "a second @SipApplication; the first is on " + application);
			}
			application = e;
		}
		for (Element e : round.getElementsAnnotatedWith(SipServlet.class)) {
			servlets.add((TypeElement) e);
		}
		for (Element e : round.getElementsAnnotatedWith(SipListener.class)) {
			listeners.add((TypeElement) e);
		}
		if (round.processingOver() && !servlets.isEmpty()) {
			write();
		}
		return false;
	}

	private void write() {
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<!-- Generated at build time from the application's SIP annotations. Do not edit. -->\n");
		xml.append("<sip-app xmlns=\"http://xmlns.jcp.org/xml/ns/sipservlet\"")
				.append(" xmlns:javaee=\"http://java.sun.com/xml/ns/javaee\" version=\"2.0\">\n");

		String appName = "";
		String mainServlet = "";
		if (application != null) {
			SipApplication app = application.getAnnotation(SipApplication.class);
			unsupported(application, app, "name", "displayName", "description", "distributable", "proxyTimeout",
					"sessionTimeout", "mainServlet");
			appName = app.name();
			mainServlet = app.mainServlet();
			String displayName = app.displayName().isEmpty() ? appName : app.displayName();
			if (!displayName.isEmpty()) {
				element(xml, "javaee:display-name", displayName);
			}
			if (!app.description().isEmpty()) {
				element(xml, "javaee:description", app.description());
			}
			if (!appName.isEmpty()) {
				element(xml, "app-name", appName);
			}
			if (app.distributable()) {
				xml.append("\t<distributable />\n");
			}
			if (!isDefault(app, "proxyTimeout")) {
				xml.append("\t<proxy-config>\n");
				xml.append("\t");
				element(xml, "proxy-timeout", Integer.toString(app.proxyTimeout()));
				xml.append("\t</proxy-config>\n");
			}
			if (!isDefault(app, "sessionTimeout")) {
				xml.append("\t<session-config>\n");
				xml.append("\t");
				element(xml, "javaee:session-timeout", Integer.toString(app.sessionTimeout()));
				xml.append("\t</session-config>\n");
			}
		}

		for (TypeElement listener : listeners) {
			SipListener ann = listener.getAnnotation(SipListener.class);
			unsupported(listener, ann, "description");
			if (!ann.applicationName().isEmpty() && !ann.applicationName().equals(appName)) {
				error(listener, "@SipListener names application " + ann.applicationName() + ", not " + appName);
			}
			xml.append("\t<listener>\n\t");
			element(xml, "javaee:listener-class", listener.getQualifiedName().toString());
			xml.append("\t</listener>\n");
		}

		List<String> names = new ArrayList<>();
		for (TypeElement servlet : servlets) {
			SipServlet ann = servlet.getAnnotation(SipServlet.class);
			unsupported(servlet, ann, "name", "description", "loadOnStartup");
			if (!ann.applicationName().isEmpty() && !ann.applicationName().equals(appName)) {
				error(servlet, "@SipServlet names application " + ann.applicationName() + ", not " + appName);
			}
			String name = ann.name().isEmpty() ? servlet.getSimpleName().toString() : ann.name();
			names.add(name);
			xml.append("\t<servlet>\n\t");
			element(xml, "javaee:servlet-name", name);
			xml.append("\t");
			element(xml, "javaee:servlet-class", servlet.getQualifiedName().toString());
			if (ann.loadOnStartup() >= 0) {
				xml.append("\t");
				element(xml, "javaee:load-on-startup", Integer.toString(ann.loadOnStartup()));
			}
			xml.append("\t</servlet>\n");
		}

		if (mainServlet.isEmpty() && names.size() == 1) {
			mainServlet = names.get(0);
		}
		if (!mainServlet.isEmpty()) {
			xml.append("\t<servlet-selection>\n\t");
			element(xml, "main-servlet", mainServlet);
			xml.append("\t</servlet-selection>\n");
		}
		xml.append("</sip-app>\n");

		String webinf = processingEnv.getOptions().get(OPTION);
		if (webinf == null) {
			processingEnv.getMessager().printMessage(Kind.WARNING,
					"no -A" + OPTION + "=<WEB-INF directory>, so no sip.xml was written");
			return;
		}
		Path file = Paths.get(webinf, "sip.xml");
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, xml.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			processingEnv.getMessager().printMessage(Kind.ERROR, "cannot write " + file + ": " + e);
		}
	}

	/// Fails the compile for any attribute set away from its default that is not
	/// in `handled`, since `sip.xml` would not carry it.
	private void unsupported(Element where, Annotation ann, String... handled) {
		List<String> ok = List.of(handled);
		for (Method m : ann.annotationType().getDeclaredMethods()) {
			if (!ok.contains(m.getName()) && !m.getName().equals("applicationName") && !isDefault(ann, m.getName())) {
				error(where, "@" + ann.annotationType().getSimpleName() + "(" + m.getName()
						+ ") has no sip.xml equivalent; this application needs the container's annotation scan");
			}
		}
	}

	private static boolean isDefault(Annotation ann, String attribute) {
		try {
			Method m = ann.annotationType().getMethod(attribute);
			return Objects.equals(m.invoke(ann), m.getDefaultValue());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void element(StringBuilder xml, String name, String value) {
		xml.append('\t').append('<').append(name).append('>').append(escape(value)).append("</").append(name)
				.append(">\n");
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void error(Element where, String message) {
		processingEnv.getMessager().printMessage(Kind.ERROR, message, where);
	}

}
