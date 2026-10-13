package org.vorpal.blade.framework.v3.net.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import net.bytebuddy.agent.ByteBuddyAgent;

/// The agent in a real JVM: the JDK's own socket and HTTP classes rewritten,
/// real connections to a local server and to a closed port, and what each
/// application is credited with.
class BladeAgentTest {

	private static HttpServer server;
	private static int port;
	private static int closed;
	private static Method register, unregister, json;

	private ClassLoader app;
	private ClassLoader saved;

	@BeforeAll
	static void install() throws Exception {
		BladeAgent.install(ByteBuddyAgent.install());
		assertTrue(BladeAgent.isInstalled());
		// The boot-path copy JDK code calls, not one on the test class path.
		Class<?> events = Class.forName(BladeAgent.BOOT_PACKAGE + "NetEvents", false, null);
		register = events.getMethod("register", ClassLoader.class, String.class);
		unregister = events.getMethod("unregister", ClassLoader.class);
		json = events.getMethod("json", ClassLoader.class);

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", x -> {
			x.sendResponseHeaders(200, 2);
			x.getResponseBody().write("ok".getBytes());
			x.close();
		});
		server.createContext("/busy", x -> {
			x.sendResponseHeaders(503, -1);
			x.close();
		});
		server.createContext("/missing", x -> {
			x.sendResponseHeaders(404, -1);
			x.close();
		});
		server.start();
		port = server.getAddress().getPort();
		try (ServerSocket s = new ServerSocket(0)) {
			closed = s.getLocalPort(); // free once closed: nothing listens there
		}
	}

	@AfterAll
	static void stop() {
		server.stop(0);
	}

	@BeforeEach
	void becomeAnApplication() throws Exception {
		app = new URLClassLoader(new URL[0], getClass().getClassLoader());
		register.invoke(null, app, "test-app");
		saved = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(app);
	}

	@AfterEach
	void stopBeingOne() throws Exception {
		Thread.currentThread().setContextClassLoader(saved);
		unregister.invoke(null, app);
	}

	private String recorded() throws Exception {
		return (String) json.invoke(null, app);
	}

	/// The entry for `kind remote`, or null.
	private static String entry(String all, String kind, String remote) {
		Matcher m = Pattern.compile("\\{\"kind\":\"" + kind + "\",\"remote\":\"" + Pattern.quote(remote) + "\"[^}]*\\}")
				.matcher(all);
		return m.find() ? m.group() : null;
	}

	private static long number(String entry, String field) {
		Matcher m = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(entry);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	@Test
	void aRefusedConnectIsAFailedTcpRemote() throws Exception {
		assertThrows(IOException.class, () -> {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("127.0.0.1", closed), 2000);
			}
		});
		String e = entry(recorded(), "tcp", "127.0.0.1:" + closed);
		assertTrue(e != null, recorded());
		assertEquals(1, number(e, "failures"));
		assertTrue(e.contains("ConnectException"), e);
	}

	@Test
	void anHttpUrlConnectionAnswered503IsAFailure() throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/busy").openConnection();
		assertEquals(503, c.getResponseCode());
		assertThrows(IOException.class, c::getInputStream, "asked again: reported once");
		String e = entry(recorded(), "http", "127.0.0.1:" + port);
		assertTrue(e != null, recorded());
		assertEquals(1, number(e, "calls"));
		assertEquals(1, number(e, "failures"));
		assertTrue(e.contains("HTTP 503"), e);
	}

	@Test
	void aClientErrorMeansTheServerIsUp() throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/missing").openConnection();
		assertEquals(404, c.getResponseCode());
		String e = entry(recorded(), "http", "127.0.0.1:" + port);
		assertEquals(0, number(e, "failures"), "a 404 is the server answering; the network map judges the codes");
	}

	@Test
	void anHttpUrlConnectionThatCannotConnectIsReportedOnceByTheSocket() throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + closed + "/x").openConnection();
		c.setConnectTimeout(2000);
		assertThrows(IOException.class, c::getInputStream);
		String all = recorded();
		assertTrue(entry(all, "tcp", "127.0.0.1:" + closed) != null, all);
		assertNull(entry(all, "http", "127.0.0.1:" + closed), "not twice: " + all);
	}

	@Test
	void theJdkHttpClientIsReportedWhenTheResponseArrives() throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, ok.statusCode());
		client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/busy")).build(),
				HttpResponse.BodyHandlers.discarding());
		assertThrows(IOException.class, () -> client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/x")).build(),
				HttpResponse.BodyHandlers.discarding()));
		Thread.sleep(200); // completion runs on the client's thread
		String all = recorded();
		String up = entry(all, "http", "127.0.0.1:" + port);
		assertEquals(2, number(up, "calls"), all);
		assertEquals(1, number(up, "failures"), "the 503");
		String down = entry(all, "http", "127.0.0.1:" + closed);
		assertEquals(1, number(down, "failures"), all);
		assertTrue(down.contains("ConnectException"), down);
		assertNull(entry(all, "tcp", "127.0.0.1:" + closed), "the client reports its own connects");
	}

	@Test
	void aThreadThatBelongsToNoApplicationIsNotRecorded() throws Exception {
		Thread.currentThread().setContextClassLoader(saved);
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
		}
		Thread.currentThread().setContextClassLoader(app);
		assertNull(entry(recorded(), "tcp", "127.0.0.1:" + port));
	}

	@Test
	void theContainersOwnConnectionsAreNotRecorded() throws Exception {
		weblogic.jdbc.FakePool.connect("127.0.0.1", port);
		assertNull(entry(recorded(), "tcp", "127.0.0.1:" + port), recorded());
	}

	@Test
	void anApplicationCallMadeWhileTheContainerDispatchesIsTheApplications() throws Exception {
		com.bea.wcp.sip.FakeContainer.dispatch(() -> {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("127.0.0.1", closed), 2000);
			} catch (IOException expected) {
				// refused
			}
		});
		assertTrue(entry(recorded(), "tcp", "127.0.0.1:" + closed) != null,
				"the container is beneath every request; the code nearest the socket is the application's");
	}

	@Test
	void aConnectThatWorkedCountsAsACall() throws Exception {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
			try (InputStream in = s.getInputStream()) {
				// connected; nothing to read
			}
		}
		String e = entry(recorded(), "tcp", "127.0.0.1:" + port);
		assertEquals(1, number(e, "calls"));
		assertEquals(0, number(e, "failures"));
	}
}
