package org.vorpal.blade.services.webrtc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/// Pings this node's browser sockets every [#PERIOD_SECONDS] seconds ([BrowserRegistry#pingAll]),
/// inside the 60-second idle timeout of the proxies that commonly sit in front of the gateway.
@WebListener
public class SocketPing implements ServletContextListener {

	static final long PERIOD_SECONDS = 25;

	private ScheduledExecutorService timer;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "webrtc-socket-ping");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleAtFixedRate(BrowserRegistry::pingAll, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (timer != null) {
			timer.shutdownNow();
		}
	}
}
