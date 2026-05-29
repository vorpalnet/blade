package org.vorpal.blade.framework.v2.analytics;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// 64-bit Snowflake-style ID generator for analytics session IDs.
///
/// Layout (high bit to low bit):
///
/// ```
///  63 ────── 23   22 ── 16   15 ── 10   9 ── 0
/// [   ts 41    ][cluster 7][ node 6 ][seq 10]
/// ```
///
/// - **timestamp** — 41 bits of milliseconds since `2026-01-01T00:00:00Z`,
///   for a ~70-year horizon (→ 2097). The sign bit is the top timestamp
///   bit, so IDs render as negative in the second half of the range —
///   storage and joins are unaffected.
/// - **cluster** — 7 bits (0–127), one operator-assigned value per WLS
///   cluster. Sourced from `<domain>/config/custom/vorpal/analytics.json`
///   field `clusterId`. Required for any clustered deployment.
/// - **node** — 6 bits (0–63), the position of this managed server in
///   its WLS cluster's sorted member-name list. Derived at startup via
///   the local DomainConfiguration MBean (no operator action).
/// - **seq** — 10 bits (0–1023) per millisecond per node. Per-JVM
///   monotonic counter; spins to next ms if exhausted.
public final class SnowflakeId {

	/// Milliseconds-since-Unix-epoch corresponding to `2026-01-01T00:00:00Z`.
	public static final long EPOCH_MS = 1767225600000L;

	private static final int SEQ_BITS = 10;
	private static final int NODE_BITS = 6;
	private static final int CLUSTER_BITS = 7;

	private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;
	private static final long MAX_NODE = (1L << NODE_BITS) - 1;
	private static final long MAX_CLUSTER = (1L << CLUSTER_BITS) - 1;

	private static final int NODE_SHIFT = SEQ_BITS;
	private static final int CLUSTER_SHIFT = SEQ_BITS + NODE_BITS;
	private static final int TS_SHIFT = SEQ_BITS + NODE_BITS + CLUSTER_BITS;

	private final long clusterId;
	private final long nodeId;
	private long lastMs = -1L;
	private long sequence = 0L;

	public SnowflakeId(int clusterId, int nodeId) {
		if (clusterId < 0 || clusterId > MAX_CLUSTER) {
			throw new IllegalArgumentException(
					"clusterId " + clusterId + " out of range 0.." + MAX_CLUSTER);
		}
		if (nodeId < 0 || nodeId > MAX_NODE) {
			throw new IllegalArgumentException(
					"nodeId " + nodeId + " out of range 0.." + MAX_NODE);
		}
		this.clusterId = clusterId;
		this.nodeId = nodeId;
	}

	/// Returns the next ID. Thread-safe; per-JVM contention is negligible
	/// at expected SIP CPS (the critical section is microseconds).
	public synchronized long nextId() {
		long now = System.currentTimeMillis();
		if (now < lastMs) {
			try {
				Thread.sleep(lastMs - now);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			now = System.currentTimeMillis();
			if (now < lastMs) {
				now = lastMs;
			}
		}
		if (now == lastMs) {
			sequence = (sequence + 1) & MAX_SEQ;
			if (sequence == 0) {
				while (now <= lastMs) {
					now = System.currentTimeMillis();
				}
			}
		} else {
			sequence = 0L;
		}
		lastMs = now;
		return ((now - EPOCH_MS) << TS_SHIFT)
				| (clusterId << CLUSTER_SHIFT)
				| (nodeId << NODE_SHIFT)
				| sequence;
	}

	/// Recovers the milliseconds-since-Unix-epoch from a snowflake ID.
	public static long extractTimestamp(long snowflakeId) {
		return (snowflakeId >> TS_SHIFT) + EPOCH_MS;
	}

	/// Recovers the cluster ID (0–127) from a snowflake ID.
	public static long extractClusterId(long snowflakeId) {
		return (snowflakeId >> CLUSTER_SHIFT) & MAX_CLUSTER;
	}

	/// Recovers the node ID (0–63) from a snowflake ID.
	public static long extractNodeId(long snowflakeId) {
		return (snowflakeId >> NODE_SHIFT) & MAX_NODE;
	}

	// ─── JVM-singleton accessor with config-file + JMX bootstrap ────────

	private static final Logger JUL = Logger.getLogger(SnowflakeId.class.getName());
	private static volatile SnowflakeId shared;

	/// Returns the JVM-singleton generator, lazily initialized.
	///
	/// `clusterId` is read from `<domain>/config/custom/vorpal/analytics.json`.
	/// `nodeId` is derived as this server's alphabetic ordinal in its WLS
	/// cluster's member list, via the local DomainConfiguration MBean.
	///
	/// On any failure (missing config file, JMX query error, server not in
	/// a cluster), both default to 0 with a severe log entry. Defaults
	/// will collide in any multi-node deployment — fix the underlying issue
	/// rather than ignoring the warning.
	public static SnowflakeId shared() {
		SnowflakeId s = shared;
		if (s == null) {
			synchronized (SnowflakeId.class) {
				s = shared;
				if (s == null) {
					int c = resolveClusterId();
					int n = resolveNodeId();
					JUL.info("SnowflakeId: clusterId=" + c + " nodeId=" + n);
					shared = s = new SnowflakeId(c, n);
				}
			}
		}
		return s;
	}

	/// Reads `clusterId` from `<domain>/config/custom/vorpal/analytics.json`.
	/// Returns 0 (with a severe log) on any failure.
	private static int resolveClusterId() {
		File configFile = locateConfigFile();
		if (configFile == null || !configFile.isFile()) {
			JUL.log(Level.SEVERE,
					"SnowflakeId: config file not found (expected <domain>/config/custom/vorpal/analytics.json). "
							+ "Defaulting clusterId=0; analytics IDs will collide across clusters reporting to the same DB. "
							+ "Create the file with content {\"clusterId\": N} where N is 0-127, unique per cluster.");
			return 0;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(configFile);
			JsonNode cidNode = root.get("clusterId");
			if (cidNode == null || !cidNode.isInt()) {
				JUL.log(Level.SEVERE,
						"SnowflakeId: " + configFile
								+ " missing or non-integer 'clusterId' field. Defaulting to 0.");
				return 0;
			}
			int cid = cidNode.asInt();
			if (cid < 0 || cid > MAX_CLUSTER) {
				JUL.log(Level.SEVERE,
						"SnowflakeId: clusterId " + cid + " out of range 0.." + MAX_CLUSTER
								+ " in " + configFile + ". Defaulting to 0.");
				return 0;
			}
			return cid;
		} catch (IOException ex) {
			JUL.log(Level.SEVERE,
					"SnowflakeId: failed to read " + configFile + ": " + ex.getMessage()
							+ ". Defaulting clusterId=0.");
			return 0;
		}
	}

	private static File locateConfigFile() {
		String[] candidates = {
				System.getProperty("weblogic.RootDirectory"),
				System.getProperty("user.dir")
		};
		for (String base : candidates) {
			if (base == null) {
				continue;
			}
			File f = new File(base, "config/custom/vorpal/analytics.json");
			if (f.isFile()) {
				return f;
			}
		}
		String root = System.getProperty("weblogic.RootDirectory");
		if (root == null) {
			root = System.getProperty("user.dir");
		}
		return root != null ? new File(root, "config/custom/vorpal/analytics.json") : null;
	}

	/// Derives this server's `nodeId` from its alphabetic position in its
	/// WLS cluster's member list. Returns 0 (with severe log) if JMX
	/// bootstrap fails or this server is not in a cluster.
	private static int resolveNodeId() {
		try {
			InitialContext ctx = new InitialContext();
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/runtime");
			ObjectName runtimeService = new ObjectName(
					"com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean");

			ObjectName serverRuntime = (ObjectName) mbs.getAttribute(runtimeService, "ServerRuntime");
			String myName = (String) mbs.getAttribute(serverRuntime, "Name");

			ObjectName domainConfig = (ObjectName) mbs.getAttribute(runtimeService, "DomainConfiguration");
			ObjectName myServerConfig = findServerConfig(mbs, domainConfig, myName);
			if (myServerConfig == null) {
				JUL.log(Level.SEVERE,
						"SnowflakeId: this server '" + myName
								+ "' not found in DomainConfiguration.Servers. Defaulting nodeId=0.");
				return 0;
			}
			ObjectName myCluster = (ObjectName) mbs.getAttribute(myServerConfig, "Cluster");
			if (myCluster == null) {
				JUL.log(Level.SEVERE, "SnowflakeId: server '" + myName
						+ "' is not configured into a cluster. Defaulting nodeId=0.");
				return 0;
			}
			ObjectName[] clusterServers = (ObjectName[]) mbs.getAttribute(myCluster, "Servers");
			String[] names = new String[clusterServers.length];
			for (int i = 0; i < clusterServers.length; i++) {
				names[i] = (String) mbs.getAttribute(clusterServers[i], "Name");
			}
			Arrays.sort(names);
			for (int i = 0; i < names.length; i++) {
				if (myName.equals(names[i])) {
					if (i > MAX_NODE) {
						JUL.log(Level.SEVERE,
								"SnowflakeId: server '" + myName + "' has cluster ordinal " + i
										+ ", exceeding the " + (MAX_NODE + 1)
										+ "-node limit. Defaulting nodeId=0.");
						return 0;
					}
					return i;
				}
			}
			JUL.log(Level.SEVERE, "SnowflakeId: server '" + myName
					+ "' not found in its own cluster's member list. Defaulting nodeId=0.");
			return 0;
		} catch (Exception ex) {
			JUL.log(Level.SEVERE, "SnowflakeId: nodeId JMX bootstrap failed: "
					+ ex.getClass().getSimpleName() + " " + ex.getMessage() + ". Defaulting nodeId=0.");
			return 0;
		}
	}

	private static ObjectName findServerConfig(MBeanServer mbs, ObjectName domainConfig, String serverName)
			throws Exception {
		ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");
		for (ObjectName s : servers) {
			String n = (String) mbs.getAttribute(s, "Name");
			if (serverName.equals(n)) {
				return s;
			}
		}
		return null;
	}
}
