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
