package org.vorpal.blade.applications.dashboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.vorpal.blade.framework.v3.events.Problems;

/// What a stuck thread is doing, not only that one is. WebLogic marks a thread
/// stuck when one request has held it past `StuckThreadMaxTime` and names it
/// `[STUCK] ExecuteThread: …` in a thread dump. While a server counts any, the
/// minute sampling ([AppSampling]) reads its dump through the domain runtime
/// (`JVMRuntime.ThreadStackDump`), and raises each stuck thread as a problem
/// under Operations › Issues: how long, its state, and the first frames of
/// its own code, so the issue says which call it waits on. Cleared when the
/// thread is free.
final class StuckThreads {

	private static final Logger logger = Logger.getLogger(StuckThreads.class.getName());

	/// Frames that say nothing about whose code is waiting.
	private static final Pattern PLUMBING = Pattern.compile("^(java\\.|javax\\.|jdk\\.|sun\\.|com\\.sun\\.|weblogic\\.work\\.)");
	private static final Pattern ELAPSED = Pattern.compile("elapsed=([0-9.]+)s");

	/// Per server, the stuck threads raised and not yet cleared.
	private static final Map<String, Set<String>> RAISED = new HashMap<>();

	private StuckThreads() {
	}

	/// One stuck thread in a dump: name, seconds since it started, state, and
	/// the first frames that are not the JDK's or the container's.
	static final class Stuck {
		final String name;
		final long seconds;
		final String state;
		final List<String> frames;

		Stuck(String name, long seconds, String state, List<String> frames) {
			this.name = name;
			this.seconds = seconds;
			this.state = state;
			this.frames = frames;
		}

		/// `stuck 11 min, WAITING: at a.B.c(B.java:10) ← at d.E.f(E.java:20)`.
		String summary(String server) {
			return name + " on " + server + " stuck " + Math.max(1, seconds / 60) + " min, " + state
					+ (frames.isEmpty() ? "" : ": " + String.join(" ← ", frames));
		}
	}

	/// The stuck threads in a dump, in the order it lists them.
	static List<Stuck> parse(String dump) {
		List<Stuck> out = new ArrayList<>();
		String[] lines = dump.split("\n");
		for (int i = 0; i < lines.length; i++) {
			String head = lines[i];
			if (!head.startsWith("\"[STUCK]")) {
				continue;
			}
			String name = head.substring(1, head.indexOf('"', 1));
			Matcher m = ELAPSED.matcher(head);
			long seconds = m.find() ? (long) Double.parseDouble(m.group(1)) : 0;
			String state = "";
			List<String> frames = new ArrayList<>();
			for (int j = i + 1; j < lines.length && !lines[j].trim().isEmpty() && !lines[j].startsWith("\""); j++) {
				String line = lines[j].trim();
				if (line.startsWith("java.lang.Thread.State:")) {
					state = line.substring("java.lang.Thread.State:".length()).trim();
				} else if (line.startsWith("at ") && frames.size() < 3
						&& !PLUMBING.matcher(line.substring(3)).find()) {
					frames.add(line.substring(3));
				}
			}
			out.add(new Stuck(name, seconds, state, frames));
		}
		return out;
	}

	/// Read each server that counts stuck threads, raise the new ones and
	/// clear the freed.
	static void check(MBeanServer dr) {
		try {
			for (ObjectName sr : dr.queryNames(new ObjectName("com.bea:Type=ServerRuntime,*"), null)) {
				String server = HealthServlet.str(dr, sr, "Name");
				ObjectName tp = HealthServlet.obj(dr, sr, "ThreadPoolRuntime");
				long stuck = tp == null ? 0 : HealthServlet.lng(dr, tp, "StuckThreadCount");
				Set<String> now = new HashSet<>();
				if (stuck > 0) {
					ObjectName jvm = HealthServlet.obj(dr, sr, "JVMRuntime");
					Object dump = jvm == null ? null : HealthServlet.get(dr, jvm, "ThreadStackDump");
					for (Stuck s : dump == null ? new ArrayList<Stuck>() : parse(String.valueOf(dump))) {
						String key = "stuck:" + server + ":" + s.name;
						now.add(key);
						if (RAISED.computeIfAbsent(server, k -> new HashSet<>()).add(key)) {
							Problems.raise(key, s.summary(server), "/blade/logs/logs.html?server=" + server + "&q=BEA-000337");
						}
					}
				}
				Set<String> was = RAISED.getOrDefault(server, new HashSet<>());
				for (String key : new ArrayList<>(was)) {
					if (!now.contains(key)) {
						was.remove(key);
						Problems.clear(key);
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "stuck threads unread", e);
		}
	}
}
