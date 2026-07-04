package org.vorpal.blade.applications.files;

import java.io.File;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// Server-lifecycle control for the Files app: restart the AdminServer so a
/// hand-edited descriptor takes effect.
///
/// ## Why this can't be an in-process MBean call
///
/// This webapp runs ON the AdminServer. Telling the AdminServer to shut itself
/// down kills the JVM that is handling the request — there is no thread left to
/// issue the matching "start", and the HTTP response never completes. So the
/// restart is handed OFF to something that outlives the JVM: the site's Node
/// Manager (a separate process). We launch `misc/start-admin-nm.sh` **detached**
/// (its own session, so Node Manager killing the AdminServer's process group
/// does not take the helper down with it); that script does
/// `nmConnect → nmKill → nmStart` against the AdminServer.
///
/// ## Why a *forced* restart, not graceful
///
/// On a graceful shutdown the AdminServer flushes its in-memory config back to
/// `config.xml`, which would overwrite the very hand-edit we are trying to
/// apply. `nmKill` is a forced stop — the edited file is left intact — then the
/// server boots and reads it. (This is why the button exists for `config.xml`
/// at all.)
///
/// ## Scope today
///
/// Only `tier=admin` is wired. `tier=engine`/`both` files (approuter.xml,
/// sipserver.xml, coherence) are read by the SIP engine nodes; applying those
/// needs the file pushed to the engine hosts and those bounced — the paused
/// cluster-file-sync work — so this endpoint reports it is not yet available
/// rather than run a restart that wouldn't help.
@javax.ws.rs.Path("/server")
@Tag(name = "Server Control", description = "Restart the AdminServer to apply a hand-edited descriptor")
public class ServerControlAPI {

	private static final Logger log = Logger.getLogger(ServerControlAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));

	@Context
	private ServletContext servletContext;

	/// Liveness + capability probe. Always 200 while the AdminServer is up, so
	/// the browser can poll it to detect the server coming back after a restart.
	/// `restartConfigured` tells the UI whether to offer the restart action.
	@GET
	@javax.ws.rs.Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Liveness probe; reports whether AdminServer restart is configured.")
	public Response health() {
		ObjectNode n = mapper.createObjectNode();
		n.put("ok", true);
		ServerControlConfig cfg = config();
		boolean configured = cfg != null && cfg.getScriptPath() != null
				&& !cfg.getScriptPath().trim().isEmpty()
				&& new File(cfg.getScriptPath()).isFile();
		n.put("restartConfigured", configured);
		n.put("adminServerName", cfg == null ? "AdminServer" : cfg.getAdminServerName());
		return Response.ok(n.toString()).build();
	}

	/// Restart a server tier. Only `admin` is implemented; `engine`/`both` are
	/// rejected with an explanation. On success the work is detached and this
	/// returns 202 immediately — the AdminServer (and this very response's
	/// connection) goes down moments later; the browser polls /health to learn
	/// when it is back.
	@POST
	@javax.ws.rs.Path("/restart")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Restart the AdminServer (tier=admin) via a detached Node Manager helper.")
	public Response restart(@QueryParam("tier") String tier) {
		String t = tier == null ? "admin" : tier.trim().toLowerCase();

		if ("engine".equals(t) || "both".equals(t)) {
			return json(Response.Status.NOT_IMPLEMENTED, false,
					"Engine-tier files are read by the SIP engine nodes. Applying them needs the file "
							+ "pushed to the engine hosts and those restarted (cluster-file-sync), which is "
							+ "not yet wired. Restarting the AdminServer would not apply this edit.");
		}
		if (!"admin".equals(t)) {
			return json(Response.Status.BAD_REQUEST, false, "Unknown tier: " + tier);
		}

		ServerControlConfig cfg = config();
		if (cfg == null || cfg.getScriptPath() == null || cfg.getScriptPath().trim().isEmpty()) {
			return json(Response.Status.PRECONDITION_FAILED, false,
					"Restart is not configured. Set serverControl.scriptPath (path to start-admin-nm.sh) "
							+ "in the files app config, and place a .nmsecret next to that script.");
		}

		File script = new File(cfg.getScriptPath());
		if (!script.isFile()) {
			return json(Response.Status.PRECONDITION_FAILED, false,
					"Configured restart helper not found: " + cfg.getScriptPath());
		}

		String mwHome = System.getProperty("MW_HOME", System.getenv("MW_HOME"));
		if (mwHome == null || mwHome.trim().isEmpty()) {
			return json(Response.Status.PRECONDITION_FAILED, false,
					"MW_HOME is not set in the AdminServer environment; the restart helper needs it to find WLST.");
		}

		String domainHome = Paths.get(DOMAIN_HOME).toAbsolutePath().normalize().toString();
		String domainName = new File(domainHome).getName();
		File logFile = new File(domainHome, "blade-restart.log");

		try {
			launchDetached(script.getAbsolutePath(), logFile.getAbsolutePath(), mwHome, domainHome, domainName, cfg);
		} catch (Exception e) {
			log.log(Level.SEVERE, "failed to launch detached AdminServer restart", e);
			return json(Response.Status.INTERNAL_SERVER_ERROR, false,
					"Could not launch the restart helper: " + e.getMessage());
		}

		log.log(Level.WARNING, "AdminServer restart launched (detached) via {0}; output -> {1}",
				new Object[] { script.getAbsolutePath(), logFile.getAbsolutePath() });
		return json(Response.Status.ACCEPTED, true,
				"Restarting " + cfg.getAdminServerName() + ". The admin tier will be offline for a minute or two; "
						+ "output is logged to " + logFile.getAbsolutePath() + ".");
	}

	// ---- detached launch -------------------------------------------------

	/// Launch the helper in its own session so Node Manager killing the
	/// AdminServer's process group does not also kill the helper that is doing
	/// the killing. `setsid` (Linux) is preferred; `nohup` is the fallback for
	/// hosts without it. The `&` lets the wrapper return at once — we never wait.
	private void launchDetached(String scriptPath, String logPath, String mwHome, String domainHome,
			String domainName, ServerControlConfig cfg) throws Exception {
		String inner = "/bin/bash " + shq(scriptPath);
		String shell = "{ command -v setsid >/dev/null 2>&1 && setsid " + inner
				+ " || nohup " + inner + " ; } >" + shq(logPath) + " 2>&1 </dev/null &";

		ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", shell);
		java.util.Map<String, String> env = pb.environment();
		env.put("NM_ACTION", "restart");
		env.put("MW_HOME", mwHome);
		env.put("DOMAIN_HOME", domainHome);
		env.put("DOMAIN_NAME", domainName);
		env.put("ADMIN_SERVER", cfg.getAdminServerName());
		env.put("NM_HOST", cfg.getNmHost());
		env.put("NM_PORT", String.valueOf(cfg.getNmPort()));
		env.put("NM_TYPE", cfg.getNmType());
		env.put("NM_USER", cfg.getNmUser());
		pb.start();
	}

	/// Single-quote a value for safe inclusion in a `/bin/bash -c` string.
	private static String shq(String s) {
		return "'" + s.replace("'", "'\\''") + "'";
	}

	// ---- config / responses ----------------------------------------------

	@SuppressWarnings("unchecked")
	private ServerControlConfig config() {
		Object mgr = servletContext == null ? null : servletContext.getAttribute(FilesSettingsStartup.SETTINGS_ATTR);
		if (!(mgr instanceof SettingsManager)) {
			return null;
		}
		FilesSettings settings = ((SettingsManager<FilesSettings>) mgr).getCurrent();
		return settings == null ? null : settings.getServerControl();
	}

	private Response json(Response.Status status, boolean ok, String message) {
		ObjectNode n = mapper.createObjectNode();
		n.put("ok", ok);
		n.put("message", message);
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(n.toString()).build();
	}
}
