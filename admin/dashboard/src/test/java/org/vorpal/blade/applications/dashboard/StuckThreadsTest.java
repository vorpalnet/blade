package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/// A thread dump's stuck threads, and what each is doing.
class StuckThreadsTest {

	static final String DUMP = String.join("\n",
			"\"[ACTIVE] ExecuteThread: '0' for queue: 'weblogic.kernel.Default (self-tuning)'\" #35 daemon prio=5 elapsed=4118.50s nid=1 in Object.wait()",
			"   java.lang.Thread.State: WAITING (on object monitor)",
			"\tat java.lang.Object.wait0(java.base@21/Native Method)",
			"\tat weblogic.work.ExecuteThread.run(ExecuteThread.java:398)",
			"",
			"\"[STUCK] ExecuteThread: '12' for queue: 'weblogic.kernel.Default (self-tuning)'\" #48 daemon prio=1 elapsed=661.20s nid=2 runnable",
			"   java.lang.Thread.State: RUNNABLE",
			"\tat sun.nio.ch.Net.poll(java.base@21/Native Method)",
			"\tat java.net.SocketInputStream.read(java.base@21/SocketInputStream.java:168)",
			"\tat org.vorpal.blade.framework.v3.configuration.connectors.RestConnector.call(RestConnector.java:212)",
			"\tat org.vorpal.blade.services.irouter.IRouterInvite.process(IRouterInvite.java:90)",
			"\tat org.vorpal.blade.framework.AsyncSipServlet.doRequest(AsyncSipServlet.java:1180)",
			"\tat org.vorpal.blade.framework.AsyncSipServlet.service(AsyncSipServlet.java:900)",
			"\tat weblogic.work.ExecuteThread.execute(ExecuteThread.java:420)",
			"");

	@Test
	void aStuckThreadSaysWhoseCodeWaits() {
		List<StuckThreads.Stuck> stuck = StuckThreads.parse(DUMP);
		assertEquals(1, stuck.size(), "the active thread is not stuck");
		StuckThreads.Stuck s = stuck.get(0);
		assertEquals("[STUCK] ExecuteThread: '12' for queue: 'weblogic.kernel.Default (self-tuning)'", s.name);
		assertEquals(661, s.seconds);
		assertEquals("RUNNABLE", s.state);
		assertEquals(Arrays.asList("org.vorpal.blade.framework.v3.configuration.connectors.RestConnector.call(RestConnector.java:212)",
				"org.vorpal.blade.services.irouter.IRouterInvite.process(IRouterInvite.java:90)",
				"org.vorpal.blade.framework.AsyncSipServlet.doRequest(AsyncSipServlet.java:1180)"), s.frames,
				"the JDK's socket read and the container are skipped; the first three of BLADE's");
		assertEquals(true, s.summary("engine0").startsWith("[STUCK] ExecuteThread: '12'"));
		assertEquals(true, s.summary("engine0").contains(" on engine0 stuck 11 min, RUNNABLE: org.vorpal"));
	}
}
