package org.vorpal.gryphon;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/// Stands in for Gryphon's media driver in the agent's tests: Vorpal code on
/// the domain class path that opens a connection on no application's behalf,
/// with the thread's context classloader switched away from the
/// application's, as the Kurento client does around its WebSocket connect.
public final class FakeDriver {

	private FakeDriver() {
	}

	public static void connect(String host, int port) {
		Thread t = Thread.currentThread();
		ClassLoader app = t.getContextClassLoader();
		t.setContextClassLoader(ClassLoader.getPlatformClassLoader());
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), 2000);
		} catch (IOException expected) {
			// a refusal is recorded too
		} finally {
			t.setContextClassLoader(app);
		}
	}
}
