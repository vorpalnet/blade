package org.vorpal.blade.applications.console.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/// Applying a JVM profile must not silently drop the platform baseline.
///
/// In MBean mode Node Manager builds the JVM line from `ServerStart.Arguments` alone, so that string
/// is the server's entire startup contract — `setDomainEnv.sh` never runs. The previous behaviour
/// overwrote it verbatim with the profile, which dropped `-Dwls.home` (server will not boot) and
/// `-Dwlss.callstate.manager.classname` (SIP call-state replication silently off). These assertions
/// use the real ashburn baseline and the real "Ashburn Engines" profile.
public class JvmSettingsMergeTest {

	/// The live ashburn ServerStart.Arguments, verbatim.
	private static final String BASELINE = "-Xms256m -Xmx512m -da "
			+ "-javaagent:/opt/oracle/occas/8.3/wlserver/server/lib/debugpatch-agent.jar "
			+ "-Dwls.home=/opt/oracle/occas/8.3/wlserver/server "
			+ "-Dweblogic.home=/opt/oracle/occas/8.3/wlserver/server "
			+ "-Dwlss.maddr.enable=true -Dwlss.replication=on "
			+ "-Dwlss.callstate.manager.classname=com.bea.wcp.sip.replicatedstore.server.CoherenceCallStateManager "
			+ "-Dweblogic.security.SSL.minimumProtocolVersion=TLSv1.2 "
			+ "-Dweblogic.servlet.ClasspathServlet.disableSecureMode=false";

	/// The "Ashburn Engines" profile, verbatim.
	private static final String PROFILE = "-Xms4g -Xmx4g -XX:+UseZGC -XX:+ZGenerational "
			+ "-XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+ExplicitGCInvokesConcurrent "
			+ "-Djava.security.egd=file:/dev/./urandom -Dwlss.maddr.enable=true "
			+ "-XX:+PerfDisableSharedMem -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=256m "
			+ "-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError "
			+ "-XX:HeapDumpPath=./servers/engine1/logs -Dwlss.replication=on";

	private final JvmSettings jvm = new JvmSettings();

	@Test
	public void theBaselineTheProfileNeverMentionsSurvives() {
		String merged = jvm.mergeArguments(BASELINE, PROFILE, new ArrayList<>());

		// Without wls.home / weblogic.home the server does not start at all.
		assertTrue(merged.contains("-Dwls.home=/opt/oracle/occas/8.3/wlserver/server"));
		assertTrue(merged.contains("-Dweblogic.home=/opt/oracle/occas/8.3/wlserver/server"));
		// Without this, wlss.replication=on is set but nothing implements replicated call state —
		// failover breaks quietly, which is the worst way for it to break.
		assertTrue(merged.contains(
				"-Dwlss.callstate.manager.classname=com.bea.wcp.sip.replicatedstore.server.CoherenceCallStateManager"));
		assertTrue(merged.contains("-javaagent:/opt/oracle/occas/8.3/wlserver/server/lib/debugpatch-agent.jar"));
		assertTrue(merged.contains("-Dweblogic.security.SSL.minimumProtocolVersion=TLSv1.2"));
		assertTrue(merged.contains("-Dweblogic.servlet.ClasspathServlet.disableSecureMode=false"));
		assertTrue(merged.contains("-da"));
	}

	@Test
	public void theProfileWinsOnEveryKnobItSets() {
		String merged = jvm.mergeArguments(BASELINE, PROFILE, new ArrayList<>());

		assertTrue(merged.contains("-Xmx4g"), merged);
		assertFalse(merged.contains("-Xmx512m"), "the old heap must be gone, not merely appended");
		assertTrue(merged.contains("-Xms4g"));
		assertFalse(merged.contains("-Xms256m"));
		assertTrue(merged.contains("-XX:+UseZGC"));
	}

	@Test
	public void namingACollectorRemovesAnyOther() {
		// Two collectors on one command line is a startup failure, so the overlay cannot just append.
		String merged = jvm.mergeArguments("-Xmx512m -XX:+UseG1GC -Dkeep=me", "-Xmx2g -XX:+UseZGC",
				new ArrayList<>());

		assertTrue(merged.contains("-XX:+UseZGC"));
		assertFalse(merged.contains("-XX:+UseG1GC"));
		assertTrue(merged.contains("-Dkeep=me"));
	}

	@Test
	public void aBooleanFlagCanBeTurnedOff() {
		// -XX:-Foo must displace -XX:+Foo rather than sit alongside it.
		String merged = jvm.mergeArguments("-XX:+AlwaysPreTouch", "-XX:-AlwaysPreTouch", new ArrayList<>());

		assertEquals("-XX:-AlwaysPreTouch", merged.trim());
	}

	@Test
	public void aSystemPropertyIsReplacedByValueNotDuplicated() {
		String merged = jvm.mergeArguments("-Dwlss.replication=off", "-Dwlss.replication=on", new ArrayList<>());

		assertEquals("-Dwlss.replication=on", merged.trim());
	}

	@Test
	public void preservedTokensAreReportedForTheOperator() {
		List<String> kept = new ArrayList<>();
		jvm.mergeArguments(BASELINE, PROFILE, kept);

		// The operator needs to see the baseline survived without diffing two long strings by eye.
		assertTrue(kept.contains("-Dwls.home=/opt/oracle/occas/8.3/wlserver/server"));
		assertFalse(kept.contains("-Xmx512m"), "an overridden token was not preserved");
	}

	@Test
	public void anEmptyBaselineJustTakesTheProfile() {
		assertEquals("-Xmx2g", jvm.mergeArguments(null, "-Xmx2g", new ArrayList<>()).trim());
		assertEquals("-Xmx2g", jvm.mergeArguments("   ", "-Xmx2g", new ArrayList<>()).trim());
	}

	@Test
	public void unrecognisedTokensAreNeverDisplaced() {
		String merged = jvm.mergeArguments("-XX:+SomeFutureFlag -Dcustom.thing=1", "-Xmx2g", new ArrayList<>());

		assertTrue(merged.contains("-XX:+SomeFutureFlag"));
		assertTrue(merged.contains("-Dcustom.thing=1"));
	}

	@Test
	public void argumentKeyIdentifiesTheKnobNotTheValue() {
		assertEquals(JvmSettings.argumentKey("-Xmx512m"), JvmSettings.argumentKey("-Xmx4g"));
		assertEquals(JvmSettings.argumentKey("-XX:+AlwaysPreTouch"), JvmSettings.argumentKey("-XX:-AlwaysPreTouch"));
		assertEquals(JvmSettings.argumentKey("-XX:MetaspaceSize=128m"),
				JvmSettings.argumentKey("-XX:MetaspaceSize=256m"));
		assertEquals(JvmSettings.argumentKey("-Dwls.home=/a"), JvmSettings.argumentKey("-Dwls.home=/b"));
		// Different knobs must not collide.
		assertFalse(JvmSettings.argumentKey("-Xms1g").equals(JvmSettings.argumentKey("-Xmx1g")));
		assertFalse(JvmSettings.argumentKey("-Dwls.home=/a").equals(JvmSettings.argumentKey("-Dweblogic.home=/a")));
	}
}
