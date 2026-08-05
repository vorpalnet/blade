package org.vorpal.blade.applications.logs;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.vorpal.blade.framework.v2.logging.LogFileInfo;
import org.vorpal.blade.framework.v2.logging.LogMatch;
import org.vorpal.blade.framework.v2.logging.LogSearchResult;
import org.vorpal.blade.framework.v2.logging.LogSlice;
import org.vorpal.blade.framework.v2.logging.VorpalLogReaderMXBean;

@Path("/")
@Tag(name = "Logs", description = "Cluster-wide log viewer")
public class LogQueryAPI {

	private static final Logger log = Logger.getLogger(LogQueryAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	/// Matches the reader's own `MAX_BYTES_PER_CALL`. Asking for more per call
	/// would silently get this much anyway, and the loop would then mis-count
	/// how far it had advanced.
	private static final int CHUNK_BYTES = 1 << 20;

	@GET
	@Path("/servers")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List servers in the domain (cluster topology).")
	public Response servers() {
		try {
			List<ClusterDiscovery.ServerInfo> servers = ClusterDiscovery.listServers();
			ArrayNode arr = mapper.createArrayNode();
			for (ClusterDiscovery.ServerInfo s : servers) {
				ObjectNode n = mapper.createObjectNode();
				n.put("name", s.name);
				n.put("listenAddress", s.listenAddress);
				n.put("listenPort", s.listenPort);
				n.put("cluster", s.cluster);
				arr.add(n);
			}
			return Response.ok(arr.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	@GET
	@Path("/servers/{name}/logs")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List log files visible on the named server.")
	public Response logs(@PathParam("name") String serverName) {
		try {
			LogFileInfo[] files = LogReaderClient.listLogs(serverName);
			ArrayNode arr = mapper.createArrayNode();
			for (LogFileInfo f : files) {
				ObjectNode n = mapper.createObjectNode();
				n.put("relativePath", f.getRelativePath());
				n.put("sizeBytes", f.getSizeBytes());
				n.put("lastModifiedMs", f.getLastModifiedMs());
				n.put("kind", f.getKind());
				arr.add(n);
			}
			return Response.ok(arr.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Read a slice of a log file.
	///
	/// The response body is the raw bytes of the slice. Clients that need exact
	/// byte accounting (the viewer's sliding window does) must read it as an
	/// ArrayBuffer rather than text: the window start is
	/// `X-Log-NewOffset - byteLength`, which only holds for undecoded bytes.
	///
	/// `offset=-1&max=0` is the cheap "how big is this file right now" probe.
	/// It falls out of the contract rather than being a special case: offset -1
	/// means "position so the last `max` bytes are returned", so with max 0 the
	/// window starts at EOF, reads nothing, and reports `X-Log-NewOffset` =
	/// the current length.
	@GET
	@Path("/servers/{name}/logs/{path:.+}")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Read a slice of a log file. offset=-1 returns the last `max` bytes; max=0 reports the size.")
	public Response slice(
			@PathParam("name") String serverName,
			@PathParam("path") String relativePath,
			@DefaultValue("-1") @QueryParam("offset") long offset,
			@DefaultValue("65536") @QueryParam("max") int maxBytes) {
		try {
			LogSlice s = LogReaderClient.readSlice(serverName, relativePath, offset, maxBytes);
			String body = new String(s.getBytes(), StandardCharsets.UTF_8);
			return Response.ok(body)
					.header("X-Log-NewOffset", Long.toString(s.getNewOffset()))
					.header("X-Log-EofReached", Boolean.toString(s.isEofReached()))
					.header("X-Log-TruncatedAtStart", Boolean.toString(s.isTruncatedAtStart()))
					.build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Follow a log file from a byte cursor — the read half of live tailing.
	///
	/// Distinct from [#slice] because the underlying reader treats a cursor past
	/// end-of-file as rotation and restarts from byte 0, reporting it in
	/// `X-Log-TruncatedAtStart`. A slice would silently clamp to the new length
	/// instead, and the viewer would show the same bytes over and over.
	///
	/// Callers advance the cursor by what they consumed, not by what they were
	/// given: trailing bytes after the last newline are a half-written line, so
	/// the viewer stops the cursor at that newline and re-reads the remainder on
	/// the next tick, when the writer has finished it.
	@GET
	@Path("/servers/{name}/tail/{path:.+}")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Read forward from a cursor, detecting rotation. Used by the viewer's follow mode.")
	public Response tail(
			@PathParam("name") String serverName,
			@PathParam("path") String relativePath,
			@DefaultValue("0") @QueryParam("cursor") long cursor,
			@DefaultValue("65536") @QueryParam("max") int maxBytes) {
		try {
			LogSlice s = LogReaderClient.tail(serverName, relativePath, cursor, maxBytes);
			String body = new String(s.getBytes(), StandardCharsets.UTF_8);
			return Response.ok(body)
					.header("X-Log-NewOffset", Long.toString(s.getNewOffset()))
					.header("X-Log-EofReached", Boolean.toString(s.isEofReached()))
					.header("X-Log-TruncatedAtStart", Boolean.toString(s.isTruncatedAtStart()))
					.build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Stream a whole log file to the browser as an attachment.
	///
	/// Lives under its own `/api/download/` prefix rather than as a query
	/// parameter on [#slice] so `web.xml` can hold it to a narrower role set —
	/// servlet security patterns match by path prefix, so a flag on the slice
	/// URL could not be constrained separately.
	///
	/// The file crosses the AdminServer's JMX link one MiB at a time (the
	/// reader's `MAX_BYTES_PER_CALL` cap), but nothing is buffered here, so
	/// memory stays flat regardless of file size.
	///
	/// The length is fixed at the size observed when the request starts, which
	/// is the right semantic for a download — you get the file as of now, not a
	/// tail that never ends on a busy server. If the file rotates mid-transfer a
	/// read comes up short and the stream stops early; the declared
	/// `Content-Length` then makes the browser report a failed download rather
	/// than silently handing over a truncated file.
	@GET
	@Path("/download/{name}/{path:.+}")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Operation(summary = "Download a whole log file as an attachment.")
	public Response download(
			@PathParam("name") String serverName,
			@PathParam("path") String relativePath) {
		try {
			VorpalLogReaderMXBean reader = LogReaderClient.forServer(serverName);

			long total = 0L;
			for (LogFileInfo f : reader.listLogFiles()) {
				if (f.getRelativePath().equals(relativePath)) {
					total = f.getSizeBytes();
					break;
				}
			}

			final long size = total;
			StreamingOutput body = new StreamingOutput() {
				@Override
				public void write(OutputStream out) throws java.io.IOException {
					long offset = 0L;
					while (offset < size) {
						int want = (int) Math.min(CHUNK_BYTES, size - offset);
						LogSlice s = reader.readSlice(relativePath, offset, want);
						byte[] bytes = s.getBytes();
						if (bytes.length == 0) {
							break; // rotated or unreadable — stop short, see above
						}
						out.write(bytes);
						offset += bytes.length;
					}
					out.flush();
				}
			};

			return Response.ok(body)
					.header("Content-Disposition", "attachment; filename=\"" + attachmentName(serverName, relativePath) + "\"")
					.header("Content-Length", Long.toString(size))
					.build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Search a log file on the server that owns it.
	///
	/// Shares the `/api/search/` prefix rationale with [#download]: servlet
	/// security patterns match by path prefix, so a privileged operation needs
	/// its own prefix to be constrainable at all.
	///
	/// A node whose reader predates this method answers `supported: false` with
	/// the reason, rather than erroring. That is not a rare fallback — the
	/// reader in a JVM is registered by the first BLADE application to start
	/// there and survives redeployment, so until a node restarts it keeps the
	/// reader it booted with. See
	/// [LogReaderClient#supportsSearch].
	@GET
	@Path("/search/{name}/{path:.+}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Scan a log file for matching lines, on the node that holds it.")
	public Response search(
			@PathParam("name") String serverName,
			@PathParam("path") String relativePath,
			@QueryParam("q") String pattern,
			@DefaultValue("false") @QueryParam("regex") boolean regex,
			@DefaultValue("true") @QueryParam("ignoreCase") boolean ignoreCase,
			@DefaultValue("0") @QueryParam("from") long fromOffset,
			@DefaultValue("500") @QueryParam("maxMatches") int maxMatches,
			@DefaultValue("33554432") @QueryParam("maxScan") long maxBytesScanned) {
		try {
			ObjectNode n = mapper.createObjectNode();

			if (!LogReaderClient.supportsSearch(serverName)) {
				n.put("supported", false);
				n.put("reason", serverName + "'s log reader predates search. The reader is created "
						+ "by the first BLADE application to start in that JVM and is not replaced "
						+ "on redeployment, so restart that server to enable it.");
				return Response.ok(n.toString()).build();
			}

			LogSearchResult r = LogReaderClient.search(serverName, relativePath, pattern,
					regex, ignoreCase, fromOffset, maxMatches, maxBytesScanned);

			ArrayNode arr = mapper.createArrayNode();
			for (LogMatch m : r.getMatches()) {
				ObjectNode o = mapper.createObjectNode();
				o.put("offset", m.getOffset());
				o.put("text", m.getText());
				arr.add(o);
			}
			n.put("supported", true);
			n.set("matches", arr);
			n.put("nextOffset", r.getNextOffset());
			n.put("complete", r.isComplete());
			n.put("bytesScanned", r.getBytesScanned());
			return Response.ok(n.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Build a safe `Content-Disposition` filename.
	///
	/// [org.vorpal.blade.framework.v2.logging.VorpalLogReader#resolveSafe] guards
	/// which file gets read; this guards what goes into a response header, which
	/// is a separate concern. Quotes, control characters and path separators are
	/// all replaced, so a crafted path cannot inject a second header.
	private static String attachmentName(String serverName, String relativePath) {
		String base = serverName + "-" + relativePath;
		StringBuilder sb = new StringBuilder(base.length());
		for (int i = 0; i < base.length(); i++) {
			char c = base.charAt(i);
			sb.append((c < 0x20 || c == 0x7f || c == '"' || c == '\\' || c == '/') ? '_' : c);
		}
		return sb.toString();
	}

	private Response error(Throwable t) {
		log.log(Level.WARNING, "logs API failed", t);
		StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			t.printStackTrace(pw);
		}
		// Plain text response — visible in browser, easier to read than JSON
		// when an exception escapes JAX-RS provider serialization.
		String body = t.getClass().getName() + ": "
				+ (t.getMessage() != null ? t.getMessage() : "(no message)") + "\n\n" + sw;
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.TEXT_PLAIN)
				.entity(body)
				.build();
	}
}
