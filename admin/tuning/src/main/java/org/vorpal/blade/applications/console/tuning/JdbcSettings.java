package org.vorpal.blade.applications.console.tuning;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for JDBC connection pool sizing.
///
/// Reads/writes each data source's `JDBCConnectionPoolParams`
/// (`InitialCapacity` / `MinCapacity` / `MaxCapacity`) by navigating
/// `DomainConfiguration → JDBCSystemResources → JDBCResource →
/// JDBCConnectionPoolParams`. Writes go through a `startEdit`/`save`/`activate`
/// session like the other tabs.
///
/// Recommended sizing for a high-CPS deployment: pin `Initial = Min = Max` so
/// the pool is fully allocated at startup and never pays connection
/// create/teardown latency under load (see the Tuning app's "Recommended"
/// preset). Note `MaxCapacity`/`MinCapacity` resize dynamically on activate,
/// but `InitialCapacity` is restart-required.
@Path("/jdbc")
@Tag(name = "JDBC", description = "JDBC connection pool sizing")
public class JdbcSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get connection pool sizing for all data sources")
	public Response getAll() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// DomainRuntimeServiceMBean.DomainConfiguration. Memory: [[wls-domain-jmx-bootstrap]].
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName[] dataSources = (ObjectName[]) mbs.getAttribute(domainConfig, "JDBCSystemResources");

			ArrayNode result = mapper.createArrayNode();
			if (dataSources != null) {
				for (ObjectName ds : dataSources) {
					ObjectNode node = readDataSource(mbs, ds);
					if (node != null) {
						result.add(node);
					}
				}
			}
			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Path("/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update connection pool sizing for a specific data source")
	public Response update(@PathParam("name") String name, String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");
				ObjectName poolParams = resolvePoolParams(editMbs, domainConfig, name);
				if (poolParams == null) {
					throw new IllegalStateException("data source not found: " + name);
				}

				// Set Min before Max isn't required, but activate validates that
				// Min <= Initial <= Max, so an inconsistent trio is rejected here
				// rather than silently written.
				setIfPresent(editMbs, poolParams, "InitialCapacity", input, "initialCapacity");
				setIfPresent(editMbs, poolParams, "MinCapacity", input, "minCapacity");
				setIfPresent(editMbs, poolParams, "MaxCapacity", input, "maxCapacity");

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	private ObjectNode readDataSource(MBeanServer mbs, ObjectName ds) {
		try {
			ObjectName resource = (ObjectName) mbs.getAttribute(ds, "JDBCResource");
			if (resource == null) {
				return null;
			}
			ObjectName pool = (ObjectName) mbs.getAttribute(resource, "JDBCConnectionPoolParams");
			if (pool == null) {
				return null;
			}

			ObjectNode node = mapper.createObjectNode();
			node.put("name", attr(mbs, ds, "Name", ""));
			node.put("jndiName", firstJndiName(mbs, resource));
			node.put("initialCapacity", attrInt(mbs, pool, "InitialCapacity", 0));
			node.put("minCapacity", attrInt(mbs, pool, "MinCapacity", 0));
			node.put("maxCapacity", attrInt(mbs, pool, "MaxCapacity", 0));
			node.put("capacityIncrement", attrInt(mbs, pool, "CapacityIncrement", 0));
			return node;
		} catch (Exception e) {
			// Skip data sources we can't read (e.g. multi-data-sources with no
			// pool params) rather than failing the whole list.
			return null;
		}
	}

	private ObjectName resolvePoolParams(MBeanServer mbs, ObjectName domainConfig, String name) throws Exception {
		ObjectName[] dataSources = (ObjectName[]) mbs.getAttribute(domainConfig, "JDBCSystemResources");
		if (dataSources == null) {
			return null;
		}
		for (ObjectName ds : dataSources) {
			if (name.equals(mbs.getAttribute(ds, "Name"))) {
				ObjectName resource = (ObjectName) mbs.getAttribute(ds, "JDBCResource");
				if (resource == null) {
					return null;
				}
				return (ObjectName) mbs.getAttribute(resource, "JDBCConnectionPoolParams");
			}
		}
		return null;
	}

	private String firstJndiName(MBeanServer mbs, ObjectName resource) {
		try {
			ObjectName dsParams = (ObjectName) mbs.getAttribute(resource, "JDBCDataSourceParams");
			if (dsParams != null) {
				String[] names = (String[]) mbs.getAttribute(dsParams, "JNDINames");
				if (names != null && names.length > 0) {
					return names[0];
				}
			}
		} catch (Exception ignored) {
		}
		return "";
	}

	private void setIfPresent(MBeanServer mbs, ObjectName on, String attr, ObjectNode input, String field)
			throws Exception {
		if (input.has(field) && !input.get(field).isNull()) {
			mbs.setAttribute(on, new Attribute(attr, input.get(field).asInt()));
		}
	}

	private String attr(MBeanServer mbs, ObjectName on, String name, String dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? val.toString() : dflt;
		} catch (Exception e) {
			return dflt;
		}
	}

	private int attrInt(MBeanServer mbs, ObjectName on, String name, int dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? ((Number) val).intValue() : dflt;
		} catch (Exception e) {
			return dflt;
		}
	}

	private Response errorResponse(Exception e) {
		String msg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error";
		return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
