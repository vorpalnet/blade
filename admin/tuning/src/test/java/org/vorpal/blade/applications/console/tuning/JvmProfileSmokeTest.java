package org.vorpal.blade.applications.console.tuning;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Smoke-test driver for the JVM-profiles backend logic — `resolveAssignments`
/// (server -> arguments, the apply step's core) and that `TuningSettings`
/// actually serializes the new profile fields. Plain `main()` convention; exits
/// non-zero on the first failed expectation.
///
/// ```
/// mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/// java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
///   org.vorpal.blade.applications.console.tuning.JvmProfileSmokeTest
/// ```
public class JvmProfileSmokeTest {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		// --- resolveAssignments: only assignments whose profile exists resolve ---
		ObjectNode cfg = mapper.createObjectNode();
		ArrayNode profs = cfg.putArray("jvmProfiles");
		profs.addObject().put("name", "engine").put("arguments", "-server -Xmx2g");
		profs.addObject().put("name", "admin").put("arguments", "-server -Xmx1g");
		ObjectNode asg = cfg.putObject("jvmProfileAssignments");
		asg.put("engine1", "engine");
		asg.put("engine2", "engine");
		asg.put("adminserver", "admin");
		asg.put("orphan", "ghost"); // profile does not exist -> dropped

		Map<String, String> r = JvmSettings.resolveAssignments(cfg);
		expect(r.size() == 3, "three assignments resolve (orphan dropped), got " + r.size());
		expect("-server -Xmx2g".equals(r.get("engine1")), "engine1 -> engine args");
		expect("-server -Xmx2g".equals(r.get("engine2")), "engine2 -> engine args");
		expect("-server -Xmx1g".equals(r.get("adminserver")), "adminserver -> admin args");
		expect(!r.containsKey("orphan"), "orphan assignment dropped (no such profile)");

		// --- empty config resolves to nothing (apply is a no-op) ---
		expect(JvmSettings.resolveAssignments(mapper.createObjectNode()).isEmpty(), "empty config -> no assignments");

		// --- TuningSettings serializes the new profile fields ---
		TuningSettings ts = new TuningSettings();
		ts.setJvmProfiles(Arrays.asList(new JvmProfile("engine", "-server -Xmx2g")));
		Map<String, String> a = new LinkedHashMap<>();
		a.put("engine1", "engine");
		ts.setJvmProfileAssignments(a);
		String json = mapper.writeValueAsString(ts);
		expect(json.contains("jvmProfiles"), "serialized config carries jvmProfiles");
		expect(json.contains("\"engine\""), "serialized config carries the profile name");
		expect(json.contains("-server -Xmx2g"), "serialized config carries the arguments");
		expect(json.contains("jvmProfileAssignments"), "serialized config carries assignments");

		System.out.println(failures == 0
				? "JvmProfileSmokeTest: ALL PASSED"
				: "JvmProfileSmokeTest: " + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void expect(boolean ok, String label) {
		if (ok) {
			System.out.println("  ok   " + label);
		} else {
			failures++;
			System.out.println("  FAIL " + label);
		}
	}
}
