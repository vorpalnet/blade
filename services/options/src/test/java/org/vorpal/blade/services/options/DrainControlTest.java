package org.vorpal.blade.services.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The administrative drain flag and its JMX surface.
///
/// The MBean round-trip runs against the real platform MBeanServer — the same
/// registration path production uses (StandardMBean with the explicit
/// DrainMXBean interface). Outside WebLogic there is no `com.bea` Server MBean,
/// so cluster discovery returns null and the ObjectName carries no Cluster key
/// — which is itself asserted (the standalone naming branch).
class DrainControlTest {

	private DrainControl control;

	@AfterEach
	void cleanup() {
		if (control != null) {
			control.unregister();
			control = null;
		}
	}

	@Test
	@DisplayName("drain and resume flip the flag and the since-timestamp")
	void stateTransitions() {
		DrainControl c = new DrainControl();

		assertFalse(c.isDrained(), "a new control starts undrained");
		assertEquals(0L, c.getDrainedSinceMillis());

		c.setDrained(true);
		assertTrue(c.isDrained());
		assertTrue(c.getDrainedSinceMillis() > 0, "drain must stamp its start time");

		c.setDrained(false);
		assertFalse(c.isDrained());
		assertEquals(0L, c.getDrainedSinceMillis(), "resume must clear the timestamp");
	}

	@Test
	@DisplayName("the flag is reachable and settable over real JMX")
	void jmxRoundTrip() throws Exception {
		control = new DrainControl();
		control.register("options");

		ObjectName on = control.getObjectName();
		assertNotNull(on, "registration must store the canonical ObjectName");
		assertEquals("vorpal.blade", on.getDomain());
		assertEquals("options", on.getKeyProperty("Name"));
		assertEquals("Drain", on.getKeyProperty("Type"));
		assertNull(on.getKeyProperty("Cluster"),
				"outside WebLogic the standalone name carries no Cluster key");

		// Flip through the MBean server, exactly as WLST would.
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		assertEquals(Boolean.FALSE, mbs.getAttribute(on, "Drained"));

		mbs.setAttribute(on, new Attribute("Drained", true));
		assertTrue(control.isDrained(), "a JMX write must reach the flag the callflow reads");
		assertEquals(Boolean.TRUE, mbs.getAttribute(on, "Drained"));
		assertTrue((Long) mbs.getAttribute(on, "DrainedSinceMillis") > 0);

		mbs.setAttribute(on, new Attribute("Drained", false));
		assertFalse(control.isDrained());
		assertEquals(0L, mbs.getAttribute(on, "DrainedSinceMillis"));
	}

	@Test
	@DisplayName("re-registration replaces a stale instance from a prior deployment")
	void reRegistrationReplacesStale() throws Exception {
		control = new DrainControl();
		control.register("options");
		ObjectName on = control.getObjectName();

		// A redeploy registers a NEW control under the same name; the stale
		// one must be replaced, not rejected with InstanceAlreadyExists.
		DrainControl redeployed = new DrainControl();
		try {
			redeployed.register("options");
			assertEquals(on, redeployed.getObjectName());

			MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
			mbs.setAttribute(on, new Attribute("Drained", true));
			assertTrue(redeployed.isDrained(), "the registered instance must be the new one");
			assertFalse(control.isDrained(), "the stale instance must be disconnected");
		} finally {
			redeployed.unregister();
		}
	}

	@Test
	@DisplayName("unregister is idempotent and safe before register")
	void unregisterIsIdempotent() throws Exception {
		DrainControl never = new DrainControl();
		never.unregister(); // never registered — must not throw

		control = new DrainControl();
		control.register("options");
		control.unregister();
		assertNull(control.getObjectName(), "unregister must clear the stored name");
		control.unregister(); // second call — must not throw
	}

}
