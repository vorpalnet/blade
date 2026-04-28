package org.vorpal.blade.applications.logs;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.vorpal.blade.framework.logs.LogFileInfo;
import org.vorpal.blade.framework.logs.LogSlice;

@Path("/")
@Tag(name = "Logs", description = "Cluster-wide log viewer")
public class LogQueryAPI {

	private static final Logger log = Logger.getLogger(LogQueryAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

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

	@GET
	@Path("/servers/{name}/logs/{path:.+}")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Read a slice of a log file. offset=-1 returns the last `max` bytes.")
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
