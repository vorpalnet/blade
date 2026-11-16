package org.vorpal.blade.framework.v3.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.container.AsyncResponse;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.CallStep;

/// The source positions HTTP steps are pinned to: found on the stack, so each
/// case runs the code it looks for.
class HttpStepsSourceTest {

	static class Resource {
		StackWalker.StackFrame found;

		@POST
		public void call() {
			found = HttpSteps.resourceFrame();
		}
	}

	interface Api {
		@GET
		void status();
	}

	static class ApiImpl implements Api {
		StackWalker.StackFrame found;

		@Override
		public void status() {
			found = HttpSteps.resourceFrame();
		}
	}

	@Test
	void theResourceMethodIsFound() {
		Resource r = new Resource();
		r.call();
		assertNotNull(r.found);
		assertEquals(Resource.class.getName(), r.found.getClassName());
		assertEquals("call", r.found.getMethodName());
	}

	@Test
	void aResourceMethodAnnotatedOnItsInterfaceIsFound() {
		ApiImpl r = new ApiImpl();
		r.status();
		assertNotNull(r.found);
		assertEquals("status", r.found.getMethodName());
	}

	@Test
	void noResourceMethodGivesNull() {
		assertNull(HttpSteps.resourceFrame());
	}

	@Test
	void theLineThatResumedAnAsyncResponseIsFound() {
		StackWalker.StackFrame[] found = new StackWalker.StackFrame[1];
		AsyncResponse response = (AsyncResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { AsyncResponse.class }, (proxy, method, args) -> {
					found[0] = HttpSteps.resumerFrame();
					return true;
				});
		int line = StackWalker.getInstance().walk(s -> s.findFirst().get().getLineNumber()) + 1;
		response.resume("connected");
		assertNotNull(found[0]);
		assertEquals(getClass().getName(), found[0].getClassName());
		assertEquals("theLineThatResumedAnAsyncResponseIsFound", found[0].getMethodName());
		assertEquals(line, found[0].getLineNumber());
	}

	@Test
	void outsideResumeThereIsNoResumer() {
		assertNull(HttpSteps.resumerFrame());
	}

	@Test
	void aCalloutIsPinnedToTheLineThatCalledTheTraceHelper() {
		int line = StackWalker.getInstance().walk(s -> s.findFirst().get().getLineNumber()) + 1;
		CallStep step = trace();
		assertEquals(getClass().getName(), step.getClassName());
		assertEquals(line, step.getLine());
		assertEquals("connector lookup", step.getMethodName());
	}

	private static CallStep trace() {
		return HttpSteps.step("s1", 0L, CallStep.OUT, HttpSteps.REQUEST, "GET /x", "connector lookup", "GET /x");
	}

	@Test
	void aNestedClassIsPinnedToItsFile() {
		assertEquals("a.b.Outer", HttpSteps.topLevel("a.b.Outer$Inner$1"));
		assertEquals("a.b.Outer", HttpSteps.topLevel("a.b.Outer"));
	}
}
