package org.vorpal.blade.services.options;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The boot-gate latch, driven through the real platform MBeanServer with a
/// fake `com.bea:Name=<server>,Type=ServerRuntime` registered under the same
/// name WebLogic uses (`weblogic.Name` system property picks the server).
class ServerReadyTest {

	private static final String SERVER = "ready-test-server";
	private static final String RUNTIME = "com.bea:Name=" + SERVER + ",Type=ServerRuntime";

	/// The one attribute ServerReady reads.
	public interface FakeServerRuntimeMXBean {
		String getState();
	}

	public static class FakeServerRuntime implements FakeServerRuntimeMXBean {
		volatile String state = "STANDBY";

		@Override
		public String getState() {
			return state;
		}
	}

	private final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
	private String savedName;
	private ObjectName registered;

	@BeforeEach
	void setup() {
		savedName = System.getProperty("weblogic.Name");
		System.setProperty("weblogic.Name", SERVER);
		ServerReady.resetForTesting();
	}

	@AfterEach
	void cleanup() throws Exception {
		if (savedName != null) {
			System.setProperty("weblogic.Name", savedName);
		} else {
			System.clearProperty("weblogic.Name");
		}
		if (registered != null && mbs.isRegistered(registered)) {
			mbs.unregisterMBean(registered);
		}
		registered = null;
		ServerReady.resetForTesting();
	}

	private FakeServerRuntime register() throws Exception {
		FakeServerRuntime runtime = new FakeServerRuntime();
		StandardMBean mxbean = new StandardMBean(runtime, FakeServerRuntimeMXBean.class, true);
		registered = mbs.registerMBean(mxbean, new ObjectName(RUNTIME)).getObjectName();
		return runtime;
	}

	@Test
	@DisplayName("not ready before RUNNING, ready at RUNNING")
	void readyFollowsServerState() throws Exception {
		FakeServerRuntime runtime = register();

		assertFalse(ServerReady.isReady(), "STANDBY is mid-boot — the gate holds");

		runtime.state = "RESUMING";
		assertFalse(ServerReady.isReady(), "still deploying — the gate holds");

		runtime.state = "RUNNING";
		assertTrue(ServerReady.isReady(), "RUNNING = deploy phase over — the gate opens");
	}

	@Test
	@DisplayName("the latch holds: a later suspend does not re-close the gate")
	void latchSurvivesLaterStates() throws Exception {
		FakeServerRuntime runtime = register();
		runtime.state = "RUNNING";
		assertTrue(ServerReady.isReady());

		// Boot gate, not a state mirror: drain is the tool for a live node.
		runtime.state = "SUSPENDING";
		assertTrue(ServerReady.isReady(), "the gate must not re-close after boot");
	}

	@Test
	@DisplayName("an unreadable state reads as not ready (early boot)")
	void unreadableStateHoldsTheGate() {
		// No ServerRuntime MBean registered at all — the earliest boot moments.
		assertFalse(ServerReady.isReady(),
				"no runtime MBean yet means the deploy phase has not even begun");
	}
}
