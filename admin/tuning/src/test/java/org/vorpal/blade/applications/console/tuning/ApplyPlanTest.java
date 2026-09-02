package org.vorpal.blade.applications.console.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.vorpal.blade.applications.console.tuning.ApplyPlan.Change;

/// The preview must tell the operator, per knob, what an apply is about to do, and must
/// flag the combinations that have stopped servers coming back.
public class ApplyPlanTest {

	/// What install.sh writes for a 512m/1g box, verbatim in shape.
	private static final String BASELINE = "-Xms512m -Xmx1024m -da "
			+ "-javaagent:/opt/oracle/occas/current/wlserver/server/lib/debugpatch-agent.jar "
			+ "-Dwls.home=/opt/oracle/occas/current/wlserver/server "
			+ "-Dweblogic.home=/opt/oracle/occas/current/wlserver/server "
			+ "-Dwlss.maddr.enable=true -Dwlss.replication=on "
			+ "-Dwlss.callstate.manager.classname=com.bea.wcp.sip.replicatedstore.server.CoherenceCallStateManager "
			+ "-Dweblogic.security.SSL.minimumProtocolVersion=TLSv1.2 "
			+ "-Dweblogic.servlet.ClasspathServlet.disableSecureMode=false "
			+ "-Dweblogic.nodemanager.sslHostNameVerificationEnabled=false";

	private static final String CLASSPATH = "/opt/oracle/occas/current/wlserver/server/lib/weblogic.jar:"
			+ "/opt/oracle/occas/current/occas/server/lib/platform/oracle.sdp.occas.depended.jar:"
			+ "/opt/oracle/occas/current/wlserver/sip/server/lib/weblogic_sip.jar";

	private final JvmSettings jvm = new JvmSettings();

	private Change plan(String before, String profile) {
		ArrayList<String> kept = new ArrayList<>();
		String after = jvm.mergeArguments(before, profile, kept);
		return ApplyPlan.diff("engine0", "server", "test", before, after, kept);
	}

	@Test
	public void aChangedHeapIsOneChangeNotARemovalAndAnAddition() {
		Change c = plan(BASELINE, "-Xmx8g -XX:+UseG1GC");

		assertEquals(1, c.changed.size());
		assertEquals("-Xmx", c.changed.get(0).key);
		assertEquals("-Xmx1024m", c.changed.get(0).from);
		assertEquals("-Xmx8g", c.changed.get(0).to);
		assertEquals(1, c.added.size());
		assertEquals("-XX:+UseG1GC", c.added.get(0));
		assertTrue(c.removed.isEmpty());
	}

	@Test
	public void thePlatformBaselineShowsUpAsPreservedNotRemoved() {
		Change c = plan(BASELINE, "-Xmx8g");

		assertTrue(c.removed.isEmpty(), c.removed.toString());
		assertTrue(c.preserved.contains("-Dwls.home=/opt/oracle/occas/current/wlserver/server"));
		assertTrue(c.preserved.contains(
				"-Dwlss.callstate.manager.classname=com.bea.wcp.sip.replicatedstore.server.CoherenceCallStateManager"));
	}

	@Test
	public void swappingCollectorsIsARemovalAndAnAddition() {
		Change c = plan("-Xmx1g -XX:+UseG1GC", "-XX:+UseZGC");

		assertEquals(1, c.removed.size());
		assertEquals("-XX:+UseG1GC", c.removed.get(0));
		assertEquals(1, c.added.size());
		assertEquals("-XX:+UseZGC", c.added.get(0));
	}

	@Test
	public void applyingWhatIsAlreadyThereIsUnchanged() {
		Change c = plan(BASELINE, "-Xmx1024m -Dwlss.replication=on");

		assertTrue(c.added.isEmpty());
		assertTrue(c.removed.isEmpty());
		assertTrue(c.changed.isEmpty());
		assertTrue(c.isUnchanged());
		// The overlay reorders the line even so; apply must key off the knobs, not the text.
		assertFalse(ApplyPlan.sameArguments(c.before, c.after));
	}

	@Test
	public void sameArgumentsIgnoresWhitespaceAndLayout() {
		assertTrue(ApplyPlan.sameArguments("-Xms1g\n-Xmx1g", "-Xms1g   -Xmx1g "));
		assertFalse(ApplyPlan.sameArguments("-Xms1g -Xmx1g", "-Xms1g -Xmx2g"));
	}

	// ---- warnings -----------------------------------------------------------------------------

	@Test
	public void introducingAMetaspaceCapIsFlagged() {
		Change c = plan(BASELINE, "-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m");
		ApplyPlan.warnMetaspaceCap(c);

		assertEquals(1, c.warnings.size());
		assertTrue(c.warnings.get(0).contains("MaxMetaspaceSize"));
	}

	@Test
	public void changingAnExistingMetaspaceCapIsNotFlagged() {
		// The operator already chose a cap; resizing it is their business.
		Change c = plan(BASELINE + " -XX:MaxMetaspaceSize=256m", "-XX:MaxMetaspaceSize=512m");
		ApplyPlan.warnMetaspaceCap(c);

		assertTrue(c.warnings.isEmpty());
	}

	@Test
	public void theShippedProfilesNoLongerCapMetaspace() {
		for (JvmProfile p : new TuningSettingsSample().getJvmProfiles()) {
			Change c = plan(BASELINE, p.getArguments());
			ApplyPlan.warnMetaspaceCap(c);
			assertTrue(c.warnings.isEmpty(), p.getName() + ": " + c.warnings);
			assertFalse(c.after.contains("MaxMetaspaceSize"), p.getName());
		}
	}

	@Test
	public void serverVarOnATemplateIsFlaggedButOnAServerIsNot() {
		String profile = "-XX:HeapDumpPath=./servers/${server}/logs";

		Change template = ApplyPlan.diff("engine-template", "template", "p", "", profile, null);
		ApplyPlan.warnTemplateServerVar(template);
		assertEquals(1, template.warnings.size());
		assertTrue(template.warnings.get(0).contains("${server}"));

		Change server = ApplyPlan.diff("engine0", "server", "p", "", "-XX:HeapDumpPath=./servers/engine0/logs", null);
		ApplyPlan.warnTemplateServerVar(server);
		assertTrue(server.warnings.isEmpty());
	}

	@Test
	public void aClasspathWithoutTheSipJarIsTheNoSipContainerFailure() {
		assertNull(ApplyPlan.classPathWarning(CLASSPATH));
		assertNotNull(ApplyPlan.classPathWarning(""));
		assertNotNull(ApplyPlan.classPathWarning(null));
		String noSip = ApplyPlan.classPathWarning("/opt/oracle/occas/current/wlserver/server/lib/weblogic.jar");
		assertNotNull(noSip);
		assertTrue(noSip.contains("weblogic_sip.jar"));
		String noWls = ApplyPlan.classPathWarning("/x/weblogic_sip.jar");
		assertNotNull(noWls);
		assertTrue(noWls.contains("weblogic.jar"));
	}

	@Test
	public void twoEightGigHeapsOnASixteenGigAdminBoxAreFlagged() {
		Map<String, String> box = new LinkedHashMap<>();
		box.put("AdminServer", "-Xms8g -Xmx8g -XX:+AlwaysPreTouch");
		box.put("engine0", "-Xms8g -Xmx8g -XX:+AlwaysPreTouch");

		String w = ApplyPlan.adminBoxHeapWarning(box, 16 * 1024);
		assertNotNull(w);
		assertTrue(w.contains("AdminServer 8g + engine0 8g"), w);
		assertTrue(w.contains("16.0 GB"), w);
		assertTrue(w.contains("pre-touched"), w);
	}

	@Test
	public void theInstallDefaultsOnTheSameBoxAreNot() {
		Map<String, String> box = new LinkedHashMap<>();
		box.put("AdminServer", "-Xms512m -Xmx1024m");
		box.put("engine0", "-Xms256m -Xmx768m");

		assertNull(ApplyPlan.adminBoxHeapWarning(box, 16 * 1024));
		// Unreadable RAM: say nothing rather than guess.
		assertNull(ApplyPlan.adminBoxHeapWarning(box, -1));
	}

	@Test
	public void jvmSizesParse() {
		assertEquals(8L * 1024 * 1024 * 1024, ApplyPlan.parseSize("8g"));
		assertEquals(512L * 1024 * 1024, ApplyPlan.parseSize("512M"));
		assertEquals(1024L * 1024, ApplyPlan.parseSize("1024k"));
		assertEquals(4096L, ApplyPlan.parseSize("4096"));
		assertEquals(0L, ApplyPlan.parseSize("lots"));
		assertEquals(0L, ApplyPlan.parseSize(""));
	}
}
