package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayStoreTest {

	@Test
	void theQueueComesBackWithItsFramesAndReference(@TempDir Path domain) throws Exception {
		String dir = System.getProperty("user.dir");
		System.setProperty("user.dir", domain.toString());
		System.setProperty("weblogic.Name", "engineT");
		try {
			AgentConsoleRegistry.recordPop("AB12CD34", "reviewer1", "{\"t\":\"pop\"}");
			AgentConsoleRegistry.recordFrame("AB12CD34", "{\"t\":\"update\",\"n\":1}");
			AgentConsoleRegistry.rememberCall("AB12CD34",
					new AgentConsoleRegistry.CallRef(0xAB12CD34L, new Date(1_790_000_000_000L), "3055550123"));
			ReplayStore.save();

			assertTrue(Files.isRegularFile(ReplayStore.file()), "saved under the server's data directory");
			assertTrue(ReplayStore.file().toString().contains("servers/engineT/data/blade-agent"));

			assertTrue(ReplayStore.load() >= 1);
			List<AgentConsoleRegistry.Kept> kept = AgentConsoleRegistry.kept();
			AgentConsoleRegistry.Kept call = kept.stream().filter(k -> k.vorpalId.equals("AB12CD34")).findFirst().get();
			assertEquals("reviewer1", call.agent);
			assertEquals(2, call.frames.size(), "the pop and the frame after it");
			assertNotNull(AgentConsoleRegistry.callRef("AB12CD34"));
			assertEquals("3055550123", AgentConsoleRegistry.callRef("AB12CD34").ani);
		} finally {
			System.setProperty("user.dir", dir);
			System.clearProperty("weblogic.Name");
		}
	}
}
