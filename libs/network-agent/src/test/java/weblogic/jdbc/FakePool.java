package weblogic.jdbc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/// Stands in for a container connection pool in the agent's tests: a connect
/// made with this on the stack is the container's, not the application's.
public final class FakePool {

	private FakePool() {
	}

	public static void connect(String host, int port) throws IOException {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), 2000);
		}
	}
}
