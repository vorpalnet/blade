package org.vorpal.blade.applications.console.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/// ServerDrain's JMX walks against the real platform MBeanServer, with fake
/// drain and SipServerRuntime MBeans registered under the same key layout the
/// DomainRuntime federation produces (`Location=<server>` on federated names,
/// none on the AdminServer-local one). No compile-time dependency on the
/// options app or the container — everything is ObjectNames and attribute
/// names, which is exactly the contract ServerDrain itself relies on.
class ServerDrainTest {

	/// Attribute surface of the options app's DrainMXBean, duplicated here as a
	/// test fake (the real class lives in the options WAR — the whole point is
	/// that the tuning app never links against it).
	public interface FakeDrainMXBean {
		boolean isDrained();
		void setDrained(boolean drained);
		long getDrainedSinceMillis();
	}

	public static class FakeDrain implements FakeDrainMXBean {
		volatile boolean drained;
		volatile long since;

		@Override
		public boolean isDrained() { return drained; }

		@Override
		public void setDrained(boolean drained) {
			this.drained = drained;
			this.since = drained ? System.currentTimeMillis() : 0L;
		}

		@Override
		public long getDrainedSinceMillis() { return since; }
	}

	/// Attribute surface of OCCAS's SipServerRuntime, names from Oracle's own
	/// BeanInfo (wlss-mbeaninfo.jar).
	public interface FakeSipRuntimeMXBean {
		long getActiveServerAppSessionCount();
		long getActiveServerSipSessionCount();
		long getPeriodCountSipThroughput();
	}

	public static class FakeSipRuntime implements FakeSipRuntimeMXBean {
		final long apps, sips, throughput;

		FakeSipRuntime(long apps, long sips, long throughput) {
			this.apps = apps;
			this.sips = sips;
			this.throughput = throughput;
		}

		@Override
		public long getActiveServerAppSessionCount() { return apps; }

		@Override
		public long getActiveServerSipSessionCount() { return sips; }

		@Override
		public long getPeriodCountSipThroughput() { return throughput; }
	}

	private final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
	private final List<ObjectName> registered = new ArrayList<>();

	private void register(Object impl, Class<?> iface, String name) throws Exception {
		ObjectName on = new ObjectName(name);
		@SuppressWarnings({ "unchecked", "rawtypes" })
		StandardMBean mxbean = new StandardMBean(impl, (Class) iface, true);
		registered.add(mbs.registerMBean(mxbean, on).getObjectName());
	}

	@AfterEach
	void cleanup() throws Exception {
		for (ObjectName on : registered) {
			if (mbs.isRegistered(on)) {
				mbs.unregisterMBean(on);
			}
		}
		registered.clear();
	}

	private static JsonNode rowFor(ArrayNode rows, String server) {
		for (JsonNode n : rows) {
			if (server.equals(n.path("server").asText())) {
				return n;
			}
		}
		return null;
	}

	@Test
	@DisplayName("collect merges drain state with each engine's SIP activity")
	void collectMergesDrainAndActivity() throws Exception {
		FakeDrain e1 = new FakeDrain();
		FakeDrain e2 = new FakeDrain();
		e2.setDrained(true);
		register(e1, FakeDrainMXBean.class,
				"vorpal.blade:Name=options,Type=Drain,Cluster=engines,Location=engine1");
		register(e2, FakeDrainMXBean.class,
				"vorpal.blade:Name=options,Type=Drain,Cluster=engines,Location=engine2");
		register(new FakeSipRuntime(12, 30, 250), FakeSipRuntimeMXBean.class,
				"com.bea:Name=engine1,Type=SipServerRuntime,Location=engine1");
		register(new FakeSipRuntime(4, 9, 0), FakeSipRuntimeMXBean.class,
				"com.bea:Name=engine2,Type=SipServerRuntime,Location=engine2");

		ArrayNode rows = ServerDrain.collect(mbs, "AdminServer");

		JsonNode r1 = rowFor(rows, "engine1");
		assertFalse(r1.path("drained").asBoolean(true), "engine1 is in service");
		assertEquals(30, r1.path("activeSipSessions").asLong());
		assertEquals(250, r1.path("sipThroughput").asLong());

		JsonNode r2 = rowFor(rows, "engine2");
		assertTrue(r2.path("drained").asBoolean(false), "engine2 is drained");
		assertTrue(r2.path("drainedSinceMillis").asLong() > 0);
		assertEquals(0, r2.path("sipThroughput").asLong(),
				"engine2 is quiet — the safe-to-restart signal");
	}

	@Test
	@DisplayName("a drain MBean without SIP runtime data still gets a row")
	void drainWithoutActivityStillListed() throws Exception {
		register(new FakeDrain(), FakeDrainMXBean.class,
				"vorpal.blade:Name=options,Type=Drain,Cluster=engines,Location=engine1");

		ArrayNode rows = ServerDrain.collect(mbs, "AdminServer");

		JsonNode r = rowFor(rows, "engine1");
		assertFalse(r.path("drained").asBoolean(true));
		assertFalse(r.has("activeSipSessions"), "no runtime data — counts absent, UI shows n/a");
	}

	@Test
	@DisplayName("findDrainBean targets one engine by Location; local names map to the AdminServer")
	void findDrainBeanTargetsByLocation() throws Exception {
		FakeDrain e1 = new FakeDrain();
		register(e1, FakeDrainMXBean.class,
				"vorpal.blade:Name=options,Type=Drain,Cluster=engines,Location=engine1");
		register(new FakeDrain(), FakeDrainMXBean.class,
				"vorpal.blade:Name=options,Type=Drain"); // AdminServer-local: no Location

		ObjectName on = ServerDrain.findDrainBean(mbs, "engine1", "AdminServer");
		assertEquals("engine1", on.getKeyProperty("Location"));

		// Setting through the found name reaches the right instance — the same
		// setAttribute the drain/resume endpoints perform.
		mbs.setAttribute(on, new Attribute("Drained", true));
		assertTrue(e1.isDrained(), "the write must land on engine1's instance");

		ObjectName admin = ServerDrain.findDrainBean(mbs, "AdminServer", "AdminServer");
		assertNull(admin.getKeyProperty("Location"), "the local bean is the AdminServer's");

		assertNull(ServerDrain.findDrainBean(mbs, "engine9", "AdminServer"),
				"an unknown server has no drain bean");
	}
}
