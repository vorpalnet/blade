package org.vorpal.blade.framework.v3;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

/// Smoke-test driver for [MemoryContext] and the behavior-preserving
/// [Context#resolve] change. Run via `main`, like the other v3 smoke tests.
///
/// Proves: (1) a session-free MemoryContext stores/reads/resolves from its map;
/// (2) inherited substitution (reserved vars, env/sysprop fallback, iteration)
/// works through the overridden get(); (3) the base Context with no request is
/// unchanged — unknown placeholders stay literal, reserved vars still resolve.
public final class MemoryContextSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testPutGet();
		testResolveFromMap();
		testWriteTimeResolution();
		testReservedVars();
		testFallbackChain();
		testIterativeResolution();
		testSnapshot();
		testSharedBackingMapPersists();
		testBaseContextNullRequestUnchanged();
		summary();
	}

	private static void testPutGet() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "bob");
		check("put/get", "bob".equals(ctx.get("user")));
		check("get missing -> null", ctx.get("nope") == null);
	}

	private static void testResolveFromMap() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "bob");
		ctx.put("host", "registrar");
		check("resolve template", "sip:bob@registrar".equals(ctx.resolve("sip:${user}@${host}")));
		check("unknown placeholder stays literal", "x:${missing}".equals(ctx.resolve("x:${missing}")));
	}

	private static void testWriteTimeResolution() {
		// put() resolves ${var} before storing, matching base Context.put semantics.
		MemoryContext ctx = new MemoryContext();
		ctx.put("domain", "example.com");
		ctx.put("contact", "sip:fraud@${domain}");
		check("write-time resolution", "sip:fraud@example.com".equals(ctx.get("contact")));
	}

	private static void testReservedVars() {
		MemoryContext ctx = new MemoryContext();
		String uuid = ctx.resolve("${uuid}");
		check("uuid resolved", uuid != null && uuid.length() == 36 && !uuid.contains("${"));
		String now = ctx.resolve("${now}");
		check("now resolved to digits", now != null && now.matches("\\d+"));
	}

	private static void testFallbackChain() {
		System.setProperty("MEMCTX_TEST_PROP", "fromSysProp");
		MemoryContext ctx = new MemoryContext();
		check("sysprop fallback", "fromSysProp".equals(ctx.resolve("${MEMCTX_TEST_PROP}")));
		// Map wins over sysprop.
		ctx.put("MEMCTX_TEST_PROP", "fromMap");
		check("map wins over sysprop", "fromMap".equals(ctx.resolve("${MEMCTX_TEST_PROP}")));
		System.clearProperty("MEMCTX_TEST_PROP");
	}

	private static void testIterativeResolution() {
		MemoryContext ctx = new MemoryContext();
		// put resolves at write time against then-current state: 'b' isn't set
		// yet, so 'a' is stored as the literal "${b}" (not "deep").
		ctx.put("a", "${b}");
		ctx.put("b", "deep");
		check("write-time: a stays literal (b was unset)", "${b}".equals(ctx.get("a")));
		// At read time, resolve() iterates: ${a} -> ${b} -> deep.
		check("read-time iterative resolve", "deep".equals(ctx.resolve("${a}")));
	}

	private static void testSnapshot() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("k1", "v1");
		ctx.put("k2", "v2");
		Map<String, String> snap = ctx.snapshot();
		check("snapshot size", snap.size() == 2 && "v1".equals(snap.get("k1")));
		// snapshot is a copy — mutating it must not affect the context.
		snap.put("k3", "v3");
		check("snapshot is a copy", ctx.get("k3") == null);
	}

	private static void testSharedBackingMapPersists() {
		// The stateInfo use-case: a caller-owned map carried across hops.
		Map<String, String> stateInfo = new HashMap<>();
		new MemoryContext(stateInfo).put("hop1", "value1");
		// Later hop wraps the same map and sees the prior value.
		MemoryContext later = new MemoryContext(stateInfo);
		check("backing map persists across contexts", "value1".equals(later.get("hop1")));
		check("backing map exposed", stateInfo == later.getVars());
	}

	private static void testBaseContextNullRequestUnchanged() {
		// Behavior-preserving check for the resolve() tweak: a base Context with
		// no request resolves reserved vars + sysprop fallback, and leaves
		// session-style placeholders literal (no session to consult).
		Context base = new Context(null);
		check("base null-request reserved", base.resolve("${uuid}") != null
				&& !base.resolve("${uuid}").contains("${"));
		check("base null-request unknown literal", "${user}".equals(base.resolve("${user}")));
		System.setProperty("MEMCTX_BASE_PROP", "envish");
		check("base null-request sysprop fallback", "envish".equals(base.resolve("${MEMCTX_BASE_PROP}")));
		System.clearProperty("MEMCTX_BASE_PROP");
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println("MemoryContextSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
