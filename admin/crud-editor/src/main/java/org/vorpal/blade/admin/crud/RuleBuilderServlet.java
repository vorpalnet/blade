package org.vorpal.blade.admin.crud;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.ai.AiConfig;
import org.vorpal.blade.framework.v3.ai.AiSettings;
import org.vorpal.blade.framework.v3.configuration.ConfigFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The AI rule builder's endpoints ([RuleBuilderAgent]):
///
/// - `GET  …/api/ai`: whether it is on (`blade-ai.json`), and why not.
/// - `POST …/api/ai/draft`: starts a draft; answers `{job}` at once. A draft
///   takes several model turns, longer than a proxy's idle timeout, so the
///   page polls for it.
/// - `GET  …/api/ai/jobs/<job>`: `{state, steps, result | error}`, `state`
///   `running`, `done` or `failed`.
///
/// Jobs live in this JVM for ten minutes after they finish; the editor runs
/// on the AdminServer alone.
public class RuleBuilderServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final long KEEP_MS = 10 * 60 * 1000L;

	/// One draft at a time per editor page is plenty; a few pages may run at once.
	private transient ExecutorService pool;
	private final Map<String, Job> jobs = new ConcurrentHashMap<>();

	static final class Job {
		final List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		volatile String state = "running";
		volatile RuleBuilderAgent.Result result;
		volatile String error;
		volatile long finished;
	}

	@Override
	public void init() {
		pool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "crud-rule-builder");
			t.setDaemon(true);
			return t;
		});
		// So the Configurator lists blade-ai, where the key is set.
		AiConfig.publishSchema();
	}

	@Override
	public void destroy() {
		if (pool != null) pool.shutdownNow();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String path = (req.getPathInfo() == null) ? "" : req.getPathInfo().replaceAll("^/+|/+$", "");
		if (path.isEmpty()) {
			AiSettings s = AiConfig.load();
			ObjectNode out = MAPPER.createObjectNode();
			out.put("enabled", s.unavailable() == null);
			if (s.unavailable() != null) out.put("reason", s.unavailable());
			out.put("maskNumbers", s.isMaskNumbers());
			write(resp, 200, out);
			return;
		}
		if (path.startsWith("jobs/")) {
			Job job = jobs.get(path.substring("jobs/".length()));
			if (job == null) {
				write(resp, 404, error("no such draft; it may have finished more than ten minutes ago"));
				return;
			}
			ObjectNode out = MAPPER.createObjectNode();
			out.put("state", job.state);
			synchronized (job.steps) {
				out.set("steps", MAPPER.valueToTree(new ArrayList<>(job.steps)));
			}
			if (job.result != null) out.set("result", MAPPER.valueToTree(job.result));
			if (job.error != null) out.put("error", job.error);
			write(resp, 200, out);
			return;
		}
		write(resp, 404, error("unknown endpoint"));
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String path = (req.getPathInfo() == null) ? "" : req.getPathInfo().replaceAll("^/+|/+$", "");
		if (!"draft".equals(path)) {
			write(resp, 404, error("unknown endpoint"));
			return;
		}
		AiSettings settings = AiConfig.load();
		if (settings.unavailable() != null) {
			write(resp, 409, error(settings.unavailable()));
			return;
		}
		RuleBuilderAgent.Request request;
		try {
			request = MAPPER.readValue(body(req), RuleBuilderAgent.Request.class);
		} catch (Exception e) {
			write(resp, 400, error("not valid JSON: " + e.getMessage()));
			return;
		}
		if (request.app != null && !AppCrudSection.APP_NAME.matcher(request.app).matches()) {
			write(resp, 400, error("bad application name"));
			return;
		}
		JsonNode schema = (request.app != null && !request.app.isEmpty())
				? AppCrudSection.schema(ConfigFiles.BASE.resolve("_schemas"), request.app)
				: AppCrudSection.schema();
		forgetOld();
		Job job = new Job();
		final RuleBuilderAgent a;
		try {
			// Built here, so a bad draft is a 400 and not a failed job.
			a = new RuleBuilderAgent(settings, request, schema, job.steps::add);
		} catch (Exception e) {
			write(resp, 400, error("could not read the draft: " + e.getMessage()));
			return;
		}
		String id = UUID.randomUUID().toString();
		jobs.put(id, job);
		pool.submit(() -> {
			try {
				job.result = a.run();
				job.state = "done";
			} catch (Throwable t) {
				job.error = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
				job.state = "failed";
			} finally {
				job.finished = System.currentTimeMillis();
			}
		});
		ObjectNode out = MAPPER.createObjectNode();
		out.put("job", id);
		write(resp, 202, out);
	}

	private void forgetOld() {
		long now = System.currentTimeMillis();
		for (Iterator<Job> it = jobs.values().iterator(); it.hasNext();) {
			Job j = it.next();
			if (j.finished > 0 && now - j.finished > KEEP_MS) it.remove();
		}
	}

	private static String body(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		req.setCharacterEncoding(StandardCharsets.UTF_8.name());
		try (BufferedReader r = req.getReader()) {
			char[] buf = new char[8192];
			for (int n; (n = r.read(buf)) > 0;) sb.append(buf, 0, n);
		}
		return sb.toString();
	}

	private static ObjectNode error(String message) {
		return MAPPER.createObjectNode().put("error", message);
	}

	private static void write(HttpServletResponse resp, int status, JsonNode body) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(MAPPER.writeValueAsString(body));
	}
}
