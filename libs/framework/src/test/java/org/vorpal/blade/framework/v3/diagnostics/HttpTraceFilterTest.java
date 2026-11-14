package org.vorpal.blade.framework.v3.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.CallStep;

/// [HttpTraceFilter] without a container: the servlet objects are proxies.
/// Proves a request is saved only when a rule is armed AND its thread claims
/// it for a traced call, that credentials are masked, and that the thread is
/// left clean whatever the chain does.
class HttpTraceFilterTest {

	private static final String BODY = "{\"from\":\"sip:+15550100@vorpal.net\"}";

	@AfterEach
	void reset() {
		TraceLog.diagnostics().setEnabled(false);
		TraceLog.clear();
	}

	@Test
	void passesStraightThroughWhenNothingIsArmed() throws Exception {
		HttpServletRequest request = request();
		HttpServletResponse response = response(new ByteArrayOutputStream());
		ServletRequest[] seen = new ServletRequest[1];
		new HttpTraceFilter().doFilter(request, response, (req, res) -> {
			seen[0] = req;
			assertNull(HttpTraceFilter.claim("ABCD0001"));
		});
		assertSame(request, seen[0]);
		assertTrue(TraceLog.snapshot().isEmpty());
	}

	@Test
	void savesNothingWhenNoTracedCallClaimsTheRequest() throws Exception {
		TraceLog.diagnostics().setEnabled(true);
		new HttpTraceFilter().doFilter(request(), response(new ByteArrayOutputStream()), (req, res) -> readAll(req));
		assertTrue(TraceLog.snapshot().isEmpty());
	}

	@Test
	void aClaimedRequestAndItsResponseJoinTheCall() throws Exception {
		TraceLog.diagnostics().setEnabled(true);
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		new HttpTraceFilter().doFilter(request(), response(wire), (req, res) -> {
			assertEquals(BODY, readAll(req));
			CallStep step = HttpTraceFilter.claim("ABCD0001");
			TraceLog.append(step); // as v3.Callflow's trace recording does
			assertNull(HttpTraceFilter.claim("ABCD0002"), "a request joins one call");
			res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
		});

		assertEquals("{\"ok\":true}", wire.toString("UTF-8"), "the body still reaches the client");
		List<CallStep> steps = TraceLog.snapshot();
		assertEquals(2, steps.size());

		CallStep in = steps.get(0);
		assertEquals("ABCD0001", in.getSessionId());
		assertEquals(CallStep.IN, in.getDirection());
		assertEquals(HttpSteps.REQUEST, in.getKind());
		assertEquals("POST /tpcc/resources/api/v1/call", in.getLabel());
		assertTrue(in.getMessage().startsWith("POST /tpcc/resources/api/v1/call HTTP/1.1\n"));
		assertTrue(in.getMessage().contains("Authorization: ********"));
		assertFalse(in.getMessage().contains("Basic "), "the credential never reaches the trace");
		assertTrue(in.getMessage().endsWith("\n\n" + BODY));

		CallStep out = steps.get(1);
		assertEquals("ABCD0001", out.getSessionId());
		assertEquals(CallStep.OUT, out.getDirection());
		assertEquals(HttpSteps.RESPONSE, out.getKind());
		assertEquals("200", out.getLabel());
		assertTrue(out.getMessage().contains("Set-Cookie: ********"));
		assertTrue(out.getMessage().endsWith("\n\n{\"ok\":true}"));
	}

	@Test
	void theThreadIsLeftCleanWhenTheChainThrows() {
		TraceLog.diagnostics().setEnabled(true);
		assertThrows(ServletException.class, () -> new HttpTraceFilter().doFilter(request(),
				response(new ByteArrayOutputStream()), (req, res) -> {
					throw new ServletException("resource failed");
				}));
		assertNull(HttpTraceFilter.claim("ABCD0001"), "no request is left parked on the thread");
		assertTrue(TraceLog.snapshot().isEmpty());
	}

	@Test
	void aLongBodyIsCapped() throws Exception {
		TraceLog.diagnostics().setEnabled(true);
		byte[] big = new byte[org.vorpal.blade.framework.v3.Callflow.MESSAGE_MAX_CHARS * 2];
		java.util.Arrays.fill(big, (byte) 'x');
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		new HttpTraceFilter().doFilter(request(), response(wire), (req, res) -> {
			TraceLog.append(HttpTraceFilter.claim("ABCD0001"));
			res.getOutputStream().write(big);
		});
		assertEquals(big.length, wire.size(), "every byte passes through");
		CallStep out = TraceLog.snapshot().get(1);
		assertNotNull(out.getMessage());
		assertTrue(out.getMessage().endsWith("[truncated]"));
		assertTrue(out.getMessage().length() < big.length);
	}

	// ---- servlet objects ----

	private static String readAll(ServletRequest req) throws IOException {
		return new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static HttpServletRequest request() {
		Map<String, String> headers = Map.of("Authorization", "Basic ZGVtbzpkZW1v", "Content-Type",
				"application/json");
		ByteArrayInputStream body = new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8));
		ServletInputStream in = new ServletInputStream() {
			@Override
			public int read() {
				return body.read();
			}

			@Override
			public boolean isFinished() {
				return body.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getMethod":
						return "POST";
					case "getRequestURI":
						return "/tpcc/resources/api/v1/call";
					case "getProtocol":
						return "HTTP/1.1";
					case "getHeaderNames":
						return Collections.enumeration(headers.keySet());
					case "getHeaders":
						return Collections.enumeration(List.of(headers.get(args[0])));
					case "getInputStream":
						return in;
					case "isAsyncStarted":
						return false;
					default:
						return null;
					}
				});
	}

	private static HttpServletResponse response(ByteArrayOutputStream wire) {
		ServletOutputStream out = new ServletOutputStream() {
			@Override
			public void write(int b) {
				wire.write(b);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener listener) {
			}
		};
		return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getStatus":
						return 200;
					case "getHeaderNames":
						return List.of("Set-Cookie");
					case "getHeaders":
						return List.of("JSESSIONID=abc");
					case "getOutputStream":
						return out;
					default:
						return null;
					}
				});
	}
}
