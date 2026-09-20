package org.vorpal.blade.services.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The threshold arithmetic, and the capacity walk through the domain
/// configuration against fake MBeans shaped like WebLogic's
/// (RuntimeService → DomainConfiguration → SelfTuning → WorkManagers →
/// Capacity → Count), on a private MBeanServer.
class QueuePressureTest {

	public interface RuntimeService {
		ObjectName getDomainConfiguration();
	}

	public interface Domain {
		ObjectName getSelfTuning();
	}

	public interface SelfTuning {
		ObjectName[] getWorkManagers();
	}

	public interface WorkManager {
		String getName();

		ObjectName getCapacity();
	}

	public interface Capacity {
		int getCount();
	}

	private MBeanServer mbs;
	private ObjectName selfTuning;

	@BeforeEach
	void setup() throws Exception {
		mbs = MBeanServerFactory.newMBeanServer();
		ObjectName runtimeService = new ObjectName(
				"com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean");
		ObjectName domain = new ObjectName("com.bea:Name=test_domain,Type=Domain");
		selfTuning = new ObjectName("com.bea:Name=test_domain,Type=SelfTuning");
		mbs.registerMBean(new StandardMBean((RuntimeService) () -> domain, RuntimeService.class), runtimeService);
		mbs.registerMBean(new StandardMBean((Domain) () -> selfTuning, Domain.class), domain);
	}

	private ObjectName workManager(String name, Integer capacity) throws Exception {
		ObjectName wm = new ObjectName("com.bea:Name=" + name + ",Type=WorkManager");
		ObjectName cap = null;
		if (capacity != null) {
			cap = new ObjectName("com.bea:Name=" + name + ".capacity,Type=Capacity");
			int count = capacity;
			mbs.registerMBean(new StandardMBean((Capacity) () -> count, Capacity.class), cap);
		}
		ObjectName capRef = cap;
		mbs.registerMBean(new StandardMBean(new WorkManager() {
			public String getName() {
				return name;
			}

			public ObjectName getCapacity() {
				return capRef;
			}
		}, WorkManager.class), wm);
		return wm;
	}

	private void selfTuning(ObjectName... workManagers) throws Exception {
		mbs.registerMBean(new StandardMBean((SelfTuning) () -> workManagers, SelfTuning.class), selfTuning);
	}

	@Test
	@DisplayName("80% of 400 is 320: 319 passes, 320 trips")
	void thresholdBoundary() {
		assertFalse(QueuePressure.over(319, 400, 80));
		assertTrue(QueuePressure.over(320, 400, 80));
		assertTrue(QueuePressure.over(400, 400, 80));
	}

	@Test
	@DisplayName("A 5,000,000 capacity does not overflow the arithmetic")
	void largeCapacity() {
		assertFalse(QueuePressure.over(3_999_999, 5_000_000, 80));
		assertTrue(QueuePressure.over(4_000_000, 5_000_000, 80));
	}

	@Test
	@DisplayName("Unreadable depth, unknown capacity or a zero percent never trips")
	void failsOpen() {
		assertFalse(QueuePressure.over(-1, 400, 80));
		assertFalse(QueuePressure.over(1000, 0, 80));
		assertFalse(QueuePressure.over(1000, 400, 0));
		assertFalse(QueuePressure.isPressured(0));
	}

	@Test
	@DisplayName("Outside WebLogic there are no queues to read, so no pressure")
	void noContainer() {
		assertFalse(QueuePressure.isPressured(QueuePressure.DEFAULT_PERCENT));
	}

	@Test
	@DisplayName("Capacity is read from the work manager's capacity constraint")
	void configuredCapacity() throws Exception {
		selfTuning(workManager("wlss.timer", 150000), workManager("wlss.transport", 5000000));
		assertEquals(5000000, QueuePressure.readCapacity(mbs, "wlss.transport", 400));
		assertEquals(150000, QueuePressure.readCapacity(mbs, "wlss.timer", 256));
	}

	@Test
	@DisplayName("No capacity constraint: the container's default applies")
	void defaultCapacity() throws Exception {
		selfTuning(workManager("wlss.transport", null));
		assertEquals(400, QueuePressure.readCapacity(mbs, "wlss.transport", 400));
	}

	@Test
	@DisplayName("No such work manager, or no configuration at all: 0, never a guess")
	void unreadable() throws Exception {
		assertEquals(0, QueuePressure.readCapacity(mbs, "wlss.transport", 400));
		selfTuning(workManager("wlss.connect", null));
		assertEquals(0, QueuePressure.readCapacity(mbs, "wlss.transport", 400));
	}

}
