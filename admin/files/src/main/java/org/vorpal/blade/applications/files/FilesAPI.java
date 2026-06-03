package org.vorpal.blade.applications.files;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.io.VersionedFileStore;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST endpoints for the schema-less domain-file editor.
///
/// The editable-file registry ([FilesSettings#getFiles]) is the security
/// boundary: every `path` parameter must match a registered entry exactly
/// (whitelist), and the resolved absolute path must stay inside `DOMAIN_HOME`
/// (path-traversal guard). There is deliberately no "browse" endpoint.
///
/// File I/O and version history go through the framework's [VersionedFileStore]
/// — the same backup discipline the Configurator uses — so every save is
/// recoverable from a sibling `.versions/` directory.
@javax.ws.rs.Path("/")
@Tag(name = "Files", description = "Schema-less domain-file editor")
public class FilesAPI {

	private static final Logger log = Logger.getLogger(FilesAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final VersionedFileStore store = new VersionedFileStore();

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));

	@Context
	private ServletContext servletContext;

	/// List the registry, annotated with on-disk presence/size/mtime.
	@GET
	@javax.ws.rs.Path("/files")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List the editable-file registry with on-disk status.")
	public Response list() {
		try {
			ArrayNode arr = mapper.createArrayNode();
			for (EditableFile entry : registry()) {
				ObjectNode n = mapper.createObjectNode();
				n.put("label", entry.getLabel());
				n.put("path", entry.getPath());
				n.put("type", entry.getType().name());
				try {
					Path resolved = resolve(entry.getPath());
					boolean exists = Files.exists(resolved);
					n.put("exists", exists);
					if (exists) {
						n.put("sizeBytes", Files.size(resolved));
						n.put("lastModifiedMs", Files.getLastModifiedTime(resolved).toMillis());
					}
				} catch (Exception scopeError) {
					// A registry entry that escapes the domain: surface it as
					// unreachable rather than failing the whole listing.
					n.put("exists", false);
					n.put("error", scopeError.getMessage());
				}
				arr.add(n);
			}
			return Response.ok(arr.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Read a registered file's current content.
	@GET
	@javax.ws.rs.Path("/content")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Read a registered file. The path must be in the registry.")
	public Response read(@QueryParam("path") String path) {
		try {
			EditableFile entry = requireEntry(path);
			Path resolved = resolve(entry.getPath());
			if (!Files.exists(resolved)) {
				return Response.ok("")
						.header("X-File-Type", entry.getType().name())
						.header("X-File-Exists", "false")
						.build();
			}
			return Response.ok(store.read(resolved))
					.header("X-File-Type", entry.getType().name())
					.header("X-File-Exists", "true")
					.build();
		} catch (BadInput b) {
			return badRequest(b.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Validate (by entry type) and save a registered file. The new content is
	/// the request body. A version backup is taken before overwrite.
	@POST
	@javax.ws.rs.Path("/content")
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Save a registered file after a type-based well-formedness check.")
	public Response write(@QueryParam("path") String path, String content) {
		try {
			EditableFile entry = requireEntry(path);
			Path resolved = resolve(entry.getPath());

			String validationError = FileValidators.validate(entry.getType(), content);
			if (validationError != null) {
				return badRequest(validationError);
			}

			store.write(resolved, content == null ? "" : content);

			ObjectNode result = mapper.createObjectNode();
			result.put("ok", true);
			result.put("message", "Saved " + entry.getPath());
			return Response.ok(result.toString()).build();
		} catch (BadInput b) {
			return badRequest(b.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	/// List the retained backups for a registered file, newest first.
	@GET
	@javax.ws.rs.Path("/versions")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List retained backups for a registered file.")
	public Response versions(@QueryParam("path") String path) {
		try {
			EditableFile entry = requireEntry(path);
			Path resolved = resolve(entry.getPath());
			ArrayNode arr = mapper.createArrayNode();
			for (VersionedFileStore.VersionInfo v : store.listVersions(resolved)) {
				ObjectNode n = mapper.createObjectNode();
				n.put("timestamp", v.getTimestamp());
				n.put("sizeBytes", v.getSizeBytes());
				arr.add(n);
			}
			return Response.ok(arr.toString()).build();
		} catch (BadInput b) {
			return badRequest(b.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Restore a backup as the live file (backing up current content first).
	@POST
	@javax.ws.rs.Path("/restore")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Restore a backup of a registered file; returns the restored content.")
	public Response restore(@QueryParam("path") String path, @QueryParam("timestamp") long timestamp) {
		try {
			EditableFile entry = requireEntry(path);
			Path resolved = resolve(entry.getPath());
			String restored = store.restore(resolved, timestamp);
			return Response.ok(restored)
					.header("X-File-Type", entry.getType().name())
					.build();
		} catch (BadInput b) {
			return badRequest(b.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	// ---- registry / scope ------------------------------------------------

	@SuppressWarnings("unchecked")
	private List<EditableFile> registry() {
		Object mgr = servletContext == null ? null : servletContext.getAttribute(FilesSettingsStartup.SETTINGS_ATTR);
		if (!(mgr instanceof SettingsManager)) {
			throw new IllegalStateException("Files SettingsManager not registered");
		}
		FilesSettings settings = ((SettingsManager<FilesSettings>) mgr).getCurrent();
		return settings == null ? java.util.Collections.emptyList() : settings.getFiles();
	}

	/// Look up a registry entry by its registered path. This is the whitelist
	/// check — a path not in the registry is rejected before any disk access.
	private EditableFile requireEntry(String path) throws BadInput {
		if (path == null || path.trim().isEmpty()) {
			throw new BadInput("Missing 'path' parameter");
		}
		for (EditableFile entry : registry()) {
			if (path.equals(entry.getPath())) {
				return entry;
			}
		}
		throw new BadInput("File not in the editable registry: " + path);
	}

	/// Resolve a registered relative path against DOMAIN_HOME and confine it to
	/// the domain. Defense-in-depth: even a registry entry containing `..`
	/// cannot point outside the domain.
	private Path resolve(String relativePath) throws BadInput {
		Path domain = Paths.get(DOMAIN_HOME).toAbsolutePath().normalize();
		Path resolved = domain.resolve(relativePath).normalize();
		if (!resolved.startsWith(domain)) {
			throw new BadInput("Path escapes DOMAIN_HOME: " + relativePath);
		}
		return resolved;
	}

	// ---- responses -------------------------------------------------------

	/// Thrown for client errors (unknown file, scope violation) — mapped to 400.
	private static final class BadInput extends Exception {
		private static final long serialVersionUID = 1L;

		BadInput(String message) {
			super(message);
		}
	}

	private Response badRequest(String message) {
		ObjectNode n = mapper.createObjectNode();
		n.put("ok", false);
		n.put("message", message);
		return Response.status(Response.Status.BAD_REQUEST)
				.type(MediaType.APPLICATION_JSON)
				.entity(n.toString())
				.build();
	}

	private Response error(Throwable t) {
		log.log(Level.WARNING, "files API failed", t);
		StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			t.printStackTrace(pw);
		}
		String body = t.getClass().getName() + ": "
				+ (t.getMessage() != null ? t.getMessage() : "(no message)") + "\n\n" + sw;
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.TEXT_PLAIN)
				.entity(body)
				.build();
	}
}
