package org.vorpal.blade.framework.v3.net.agent;

import java.net.SocketAddress;
import java.net.URL;

import org.vorpal.blade.framework.v3.net.agent.boot.NetEvents;

import net.bytebuddy.asm.Advice;

/// The code the agent adds to JDK network methods. ByteBuddy copies each
/// method's body into the JDK method it rewrites, so these classes are never
/// called themselves; the copies call [NetEvents], which is on the boot class
/// path where JDK code can see it. Every copy catches nothing and changes
/// nothing: it notes the start time on the way in and reports on the way out.
final class Advices {

	private Advices() {
	}

	/// `java.net.Socket.connect(SocketAddress, int)`.
	static final class SocketConnect {
		@Advice.OnMethodEnter
		static long enter() {
			return System.nanoTime();
		}

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.Argument(0) SocketAddress endpoint, @Advice.Enter long start,
				@Advice.Thrown Throwable thrown) {
			NetEvents.socketConnect(endpoint, thrown, start);
		}
	}

	/// `sun.nio.ch.SocketChannelImpl.connect(SocketAddress)`.
	static final class ChannelConnect {
		@Advice.OnMethodEnter
		static long enter() {
			return System.nanoTime();
		}

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.This Object channel, @Advice.Argument(0) SocketAddress endpoint,
				@Advice.Return boolean connected, @Advice.Enter long start, @Advice.Thrown Throwable thrown) {
			NetEvents.channelConnect(channel, endpoint, connected, thrown, start);
		}
	}

	/// `sun.nio.ch.SocketChannelImpl.finishConnect()`.
	static final class ChannelFinish {
		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.This Object channel, @Advice.Return boolean done, @Advice.Thrown Throwable thrown) {
			NetEvents.channelFinish(channel, done, thrown);
		}
	}

	/// `sun.nio.ch.UnixAsynchronousSocketChannelImpl.implConnect(remote,
	/// attachment, handler)`, behind both of NIO.2's `connect` methods (the JDK
	/// WebSocket clients use it). It returns connected, failed (the channel is
	/// closed and has no remote address) or still pending.
	static final class AsyncConnect {
		@Advice.OnMethodEnter
		static long enter() {
			return System.nanoTime();
		}

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.This Object channel, @Advice.Argument(0) SocketAddress endpoint,
				@Advice.FieldValue("connectPending") boolean pending,
				@Advice.FieldValue("remoteAddress") Object connectedTo, @Advice.Enter long start,
				@Advice.Thrown Throwable thrown) {
			NetEvents.asyncConnect(channel, endpoint, pending, connectedTo != null, thrown, start);
		}
	}

	/// `sun.nio.ch.UnixAsynchronousSocketChannelImpl.finishConnect(boolean)`:
	/// a pending connect completed. A failure has closed the channel already.
	static final class AsyncFinish {
		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.This Object channel, @Advice.FieldValue("remoteAddress") Object connectedTo) {
			if (connectedTo != null) {
				NetEvents.channelFinish(channel, true, null);
			}
		}
	}

	/// `sun.nio.ch.UnixAsynchronousSocketChannelImpl.implClose()`: a connect
	/// still pending when its channel closes failed, whether the connect itself
	/// failed or the caller gave up waiting.
	static final class AsyncClose {
		@Advice.OnMethodEnter
		static void enter(@Advice.This Object channel) {
			NetEvents.asyncClosed(channel);
		}
	}

	/// `sun.net.www.protocol.http.HttpURLConnection.getInputStream()`: every
	/// HTTP(S) exchange through `HttpURLConnection` passes here, whether or not
	/// the caller asks for the response code; the code is read from the field
	/// the connection keeps (-1 when no response came).
	static final class HttpUrlConnectionInput {
		@Advice.OnMethodEnter
		static long enter() {
			return System.nanoTime();
		}

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		static void exit(@Advice.This Object connection, @Advice.FieldValue("url") URL url,
				@Advice.FieldValue("responseCode") int status, @Advice.Enter long start,
				@Advice.Thrown Throwable thrown) {
			NetEvents.httpUrlConnection(connection, url, status, thrown, start);
		}
	}

	/// `jdk.internal.net.http.HttpClientImpl.sendAsync(request, handler,
	/// pushHandler, executor)`: the synchronous `send` calls it too.
	static final class HttpClientSend {
		@Advice.OnMethodEnter
		static long enter() {
			return System.nanoTime();
		}

		@Advice.OnMethodExit
		static void exit(@Advice.Argument(0) Object request, @Advice.Return Object future, @Advice.Enter long start) {
			NetEvents.httpClientSend(request, future, start);
		}
	}
}
