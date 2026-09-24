package org.vorpal.blade.applications.agent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The console's queue on disk, so a redeploy or a restart does not empty every agent's screen.
///
/// The queue is the replay ring ([AgentConsoleRegistry]): the last few calls, each with its pop
/// and every frame the card was sent, and the reference each call needs to be updated or added to
/// later. It is node-local, like the ring, and so is the file:
/// `servers/<server>/data/blade-agent/replay.json` under the domain. It is never under the
/// domain's `config`, which the AdminServer pushes to every engine.
///
/// Saved two seconds after the ring changes, so a busy call's frames cost one write rather than
/// one each, and written to a temporary file and moved into place, so a crash mid-write leaves the
/// last good copy. Loaded once when the application starts. A missing or unreadable file is an
/// empty queue, never a failed start.
final class ReplayStore {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final long SAVE_DELAY_MILLIS = 2000;

	private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "agent-replay-save");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicBoolean PENDING = new AtomicBoolean();

	private ReplayStore() {
	}

	static Path file() {
		return Paths.get(System.getProperty("user.dir", "."), "servers",
				System.getProperty("weblogic.Name", "server"), "data", "blade-agent", "replay.json");
	}

	/// The ring changed: save it shortly, once, however many changes land meanwhile.
	static void changed() {
		if (PENDING.compareAndSet(false, true)) {
			SAVER.schedule(() -> {
				PENDING.set(false);
				save();
			}, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	static void save() {
		try {
			ObjectNode root = JSON.createObjectNode();
			ArrayNode calls = root.putArray("calls");
			for (AgentConsoleRegistry.Kept kept : AgentConsoleRegistry.kept()) {
				ObjectNode call = calls.addObject();
				call.put("vorpalId", kept.vorpalId);
				if (kept.agent != null) {
					call.put("agent", kept.agent);
				}
				ArrayNode frames = call.putArray("frames");
				for (String frame : kept.frames) {
					frames.add(frame);
				}
				AgentConsoleRegistry.CallRef ref = kept.ref;
				if (ref != null) {
					ObjectNode r = call.putObject("ref");
					if (ref.vorpalId != null) {
						r.put("id", ref.vorpalId);
					}
					if (ref.startedAt != null) {
						r.put("startedAt", ref.startedAt.getTime());
					}
					if (ref.ani != null) {
						r.put("ani", ref.ani);
					}
					r.put("answeredAt", ref.answeredAt);
				}
			}
			Path target = file();
			Files.createDirectories(target.getParent());
			Path tmp = target.resolveSibling("replay.json.tmp");
			Files.write(tmp, JSON.writeValueAsBytes(root));
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			AgentConsoleRegistry.log("agent: the queue could not be saved to " + file() + ": " + e);
		}
	}

	/// Put the saved queue back, oldest call first. Returns how many calls came back.
	static int load() {
		File f = file().toFile();
		if (!f.isFile()) {
			return 0;
		}
		try {
			JsonNode root = JSON.readTree(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			List<AgentConsoleRegistry.Kept> back = new ArrayList<>();
			for (JsonNode call : root.path("calls")) {
				String vorpalId = call.path("vorpalId").asText(null);
				if (vorpalId == null) {
					continue;
				}
				List<String> frames = new ArrayList<>();
				for (JsonNode frame : call.path("frames")) {
					frames.add(frame.asText());
				}
				AgentConsoleRegistry.CallRef ref = null;
				JsonNode r = call.path("ref");
				if (r.isObject()) {
					ref = new AgentConsoleRegistry.CallRef(r.has("id") ? r.path("id").asLong() : null,
							r.has("startedAt") ? new Date(r.path("startedAt").asLong()) : null, r.path("ani").asText(null));
					ref.answeredAt = r.path("answeredAt").asLong(0);
				}
				back.add(new AgentConsoleRegistry.Kept(vorpalId, call.path("agent").asText(null), frames, ref));
			}
			AgentConsoleRegistry.restore(back);
			return back.size();
		} catch (Exception e) {
			AgentConsoleRegistry.log("agent: the saved queue at " + f + " could not be read, starting empty: " + e);
			return 0;
		}
	}
}
