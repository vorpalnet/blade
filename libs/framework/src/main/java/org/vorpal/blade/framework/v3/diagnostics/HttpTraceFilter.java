package org.vorpal.blade.framework.v3.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Collections;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.vorpal.blade.framework.v3.CallStep;
import org.vorpal.blade.framework.v3.Callflow;

/// Puts the HTTP request that places or changes a traced call into that call's
/// trace, with its response. Declared for every application in the framework's
/// `web-fragment.xml`.
///
/// While no trace rule is armed in this application, every request passes
/// straight through. While one is, the filter copies the request body as the
/// application reads it and the response body as it is written, keeping the
/// first [Callflow#MESSAGE_MAX_CHARS] bytes of each, and parks the request on
/// the thread serving it. Nothing is saved at that point. The request joins a
/// trace only when that thread sends SIP on a call that is traced: the trace
/// recording in `v3.Callflow` calls [#claim] with the call's id. The response
/// is saved when it completes, which for an asynchronous resource (tpcc answers
/// when the call connects) happens on another thread. A request that sends no
/// SIP, or sends it only on calls that are not traced, leaves nothing behind.
///
/// Bodies read through `getReader()` or written through `getWriter()` are not
/// copied. Wrapping the writer would hold characters in a buffer the container
/// never flushes. JAX-RS reads and writes through the streams.
///
/// The copies pass every byte through as it comes, so streaming responses
/// (an event stream, a recording download) behave as they do unfiltered.
public class HttpTraceFilter implements Filter {

	private static final ThreadLocal<Exchange> serving = new ThreadLocal<>();

	/// Joins the request this thread is serving to the traced call `sessionId`
	/// and returns its step, or returns null when the thread serves no request
	/// or its request already joined a call.
	public static CallStep claim(String sessionId) {
		Exchange exchange = serving.get();
		if (exchange == null || exchange.sessionId != null || sessionId == null || sessionId.isEmpty()) {
			return null;
		}
		exchange.sessionId = sessionId;
		exchange.resource = HttpSteps.resourceFrame();
		return HttpSteps.step(sessionId, exchange.arrived, CallStep.IN, HttpSteps.REQUEST, exchange.label,
				exchange.resource, methodOf(exchange.resource),
				exchange.head + exchange.requestBody.text(exchange.requestEncoding));
	}

	private static String methodOf(StackWalker.StackFrame frame) {
		return frame != null ? frame.getMethodName() : null;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!TraceLog.diagnostics().isEnabled() || !(request instanceof HttpServletRequest)
				|| !(response instanceof HttpServletResponse)) {
			chain.doFilter(request, response);
			return;
		}
		Exchange exchange = new Exchange((HttpServletRequest) request);
		CopyingRequest req = new CopyingRequest((HttpServletRequest) request, exchange.requestBody);
		CopyingResponse res = new CopyingResponse((HttpServletResponse) response, exchange);
		serving.set(exchange);
		try {
			chain.doFilter(req, res);
		} finally {
			// WebLogic serves HTTP and SIP from one thread pool: a request left
			// parked here would join whatever call this thread runs next.
			serving.remove();
		}
		if (exchange.sessionId == null) {
			return;
		}
		// A completion called during the dispatch is held until the dispatch
		// returns, so the listener is in place before it fires.
		if (req.isAsyncStarted()) {
			req.getAsyncContext().addListener(new AsyncListener() {
				@Override
				public void onComplete(AsyncEvent event) {
					exchange.respond(res);
				}

				@Override
				public void onTimeout(AsyncEvent event) {
				}

				@Override
				public void onError(AsyncEvent event) {
				}

				@Override
				public void onStartAsync(AsyncEvent event) {
				}
			});
		} else {
			exchange.respond(res);
		}
	}

	/// One request in flight: what was read when it arrived, the body copies,
	/// and the call it joined.
	static final class Exchange {
		final long arrived = System.currentTimeMillis();
		final String label;
		final String head;
		final String protocol;
		final String requestEncoding;
		final Copy requestBody = new Copy();
		final Copy responseBody = new Copy();
		volatile String sessionId;
		/// The resource method, found when the request joined a call.
		volatile StackWalker.StackFrame resource;
		/// The line that resumed the response, found as the response starts
		/// ([#answering]).
		volatile StackWalker.StackFrame resumer;
		private volatile boolean answering;
		private boolean responded;

		Exchange(HttpServletRequest request) {
			String query = request.getQueryString();
			label = request.getMethod() + " " + request.getRequestURI();
			protocol = request.getProtocol();
			requestEncoding = request.getCharacterEncoding();
			StringBuilder sb = new StringBuilder();
			sb.append(label).append(query != null ? "?" + query : "").append(' ').append(protocol).append('\n');
			for (String name : Collections.list(request.getHeaderNames())) {
				for (String value : Collections.list(request.getHeaders(name))) {
					HttpSteps.appendHeader(sb, name, value);
				}
			}
			head = sb.append('\n').toString();
		}

		/// Called as the response starts: its status set, or its body opened.
		/// For an asynchronous response that is on the thread that resumed it,
		/// so the stack still holds the line that did. Only a request that
		/// joined a call pays for the stack walk, and only once.
		void answering() {
			if (answering || sessionId == null) {
				return;
			}
			answering = true;
			try {
				resumer = HttpSteps.resumerFrame();
			} catch (Exception ignore) {
				// a source position is best-effort
			}
		}

		synchronized void respond(HttpServletResponse response) {
			if (responded) {
				return;
			}
			responded = true;
			try {
				int status = response.getStatus();
				StringBuilder sb = new StringBuilder();
				sb.append(protocol).append(' ').append(status).append('\n');
				for (String name : response.getHeaderNames()) {
					for (String value : response.getHeaders(name)) {
						HttpSteps.appendHeader(sb, name, value);
					}
				}
				sb.append('\n').append(responseBody.text(response.getCharacterEncoding()));
				// A synchronous resource has returned before its response is
				// written: pin the resource method, with no line.
				long now = System.currentTimeMillis();
				String text = sb.toString();
				TraceLog.append(resumer != null
						? HttpSteps.step(sessionId, now, CallStep.OUT, HttpSteps.RESPONSE, String.valueOf(status),
								resumer, resumer.getMethodName(), text)
						: HttpSteps.step(sessionId, now, CallStep.OUT, HttpSteps.RESPONSE, String.valueOf(status),
								resource != null ? HttpSteps.topLevel(resource.getClassName()) : null,
								methodOf(resource), -1, text));
			} catch (Exception ignore) {
				// tracing is best-effort; it must never affect the response
			}
		}
	}

	/// The first [Callflow#MESSAGE_MAX_CHARS] bytes of a body; the rest pass by.
	static final class Copy extends ByteArrayOutputStream {
		synchronized void copy(int b) {
			if (count < Callflow.MESSAGE_MAX_CHARS) {
				write(b);
			}
		}

		synchronized void copy(byte[] b, int off, int len) {
			int n = Math.min(len, Callflow.MESSAGE_MAX_CHARS - count);
			if (n > 0) {
				write(b, off, n);
			}
		}

		synchronized String text(String encoding) {
			try {
				return toString(encoding != null ? encoding : "UTF-8");
			} catch (UnsupportedEncodingException e) {
				return toString();
			}
		}
	}

	static final class CopyingRequest extends HttpServletRequestWrapper {
		private final Copy copy;
		private ServletInputStream in;

		CopyingRequest(HttpServletRequest request, Copy copy) {
			super(request);
			this.copy = copy;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (in == null) {
				ServletInputStream real = super.getInputStream();
				in = new ServletInputStream() {
					@Override
					public int read() throws IOException {
						int b = real.read();
						if (b >= 0) {
							copy.copy(b);
						}
						return b;
					}

					@Override
					public int read(byte[] b, int off, int len) throws IOException {
						int n = real.read(b, off, len);
						if (n > 0) {
							copy.copy(b, off, n);
						}
						return n;
					}

					@Override
					public boolean isFinished() {
						return real.isFinished();
					}

					@Override
					public boolean isReady() {
						return real.isReady();
					}

					@Override
					public void setReadListener(ReadListener listener) {
						real.setReadListener(listener);
					}
				};
			}
			return in;
		}
	}

	static final class CopyingResponse extends HttpServletResponseWrapper {
		private final Copy copy;
		private final Exchange exchange;
		private ServletOutputStream out;

		CopyingResponse(HttpServletResponse response, Exchange exchange) {
			super(response);
			this.copy = exchange.responseBody;
			this.exchange = exchange;
		}

		@Override
		public void setStatus(int sc) {
			exchange.answering();
			super.setStatus(sc);
		}

		@Override
		public void sendError(int sc) throws IOException {
			exchange.answering();
			super.sendError(sc);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			exchange.answering();
			super.sendError(sc, msg);
		}

		@Override
		public java.io.PrintWriter getWriter() throws IOException {
			exchange.answering();
			return super.getWriter();
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			exchange.answering();
			if (out == null) {
				ServletOutputStream real = super.getOutputStream();
				out = new ServletOutputStream() {
					@Override
					public void write(int b) throws IOException {
						real.write(b);
						copy.copy(b);
					}

					@Override
					public void write(byte[] b, int off, int len) throws IOException {
						real.write(b, off, len);
						copy.copy(b, off, len);
					}

					@Override
					public void flush() throws IOException {
						real.flush();
					}

					@Override
					public void close() throws IOException {
						real.close();
					}

					@Override
					public boolean isReady() {
						return real.isReady();
					}

					@Override
					public void setWriteListener(WriteListener listener) {
						real.setWriteListener(listener);
					}
				};
			}
			return out;
		}
	}
}
