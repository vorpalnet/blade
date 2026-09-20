package org.vorpal.blade.framework.v3.configuration.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

/// One compiled expression is shared by every call thread. With a variable right
/// side, each thread must match against its own pattern, never another's.
class ExpressionConcurrencyTest {

	private static int wrongAnswers(String expression, String[][] cases) throws Exception {
		Expression shared = new Expression(expression);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				final String[] c = cases[t % cases.length];
				Callable<Integer> task = () -> {
					int wrong = 0;
					for (int i = 0; i < 20_000; i++) {
						MemoryContext ctx = new MemoryContext();
						ctx.put("value", c[0]);
						ctx.put("rhs", c[1]);
						if (shared.evaluate(ctx) != Boolean.parseBoolean(c[2])) {
							wrong++;
						}
					}
					return wrong;
				};
				futures.add(pool.submit(task));
			}
			int wrong = 0;
			for (Future<Integer> f : futures) {
				wrong += f.get();
			}
			return wrong;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void variablePatternsDoNotCross() throws Exception {
		assertEquals(0, wrongAnswers("${value} matches ${rhs}", new String[][] {
				{ "2025550150", "202\\d+", "true" },
				{ "2025550150", "303\\d+", "false" },
				{ "alice", "a.*", "true" },
				{ "alice", "b.*", "false" } }));
	}

	@Test
	void variableSubnetsDoNotCross() throws Exception {
		assertEquals(0, wrongAnswers("${value} insubnet ${rhs}", new String[][] {
				{ "10.20.1.5", "10.20.0.0/16", "true" },
				{ "10.20.1.5", "192.0.2.0/24", "false" } }));
	}
}
