package org.vorpal.blade.framework.v3.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Reads a curated, confined set of read-only Linux kernel tunables from
/// `/proc/sys`, `/sys`, and `/proc/self/limits` via plain file I/O — no sudo, no
/// `sysctl` binary, no external process (stays inside BLADE's no-`ProcessBuilder`
/// rule). The list is compiled in and the only paths read are derived from it, so
/// there is no path-injection surface. The base directory is injectable so the
/// reader can be unit-tested against a fake tree; in production it is `/`.
public class KernelProbe implements KernelProbeMXBean {

	private static final ObjectMapper mapper = new ObjectMapper();

	/// sysctl keys -> `/proc/sys/<key with '.' replaced by '/'>`.
	static final String[] SYSCTL = {
			"net.core.somaxconn", "net.core.netdev_max_backlog",
			"net.core.rmem_max", "net.core.wmem_max", "net.core.rmem_default", "net.core.wmem_default",
			"net.ipv4.tcp_max_syn_backlog", "net.ipv4.tcp_fin_timeout", "net.ipv4.tcp_tw_reuse",
			"net.ipv4.ip_local_port_range", "net.ipv4.tcp_rmem", "net.ipv4.tcp_wmem",
			"net.ipv4.udp_mem", "net.ipv4.udp_rmem_min", "net.ipv4.udp_wmem_min",
			"net.ipv4.tcp_keepalive_time",
			"net.netfilter.nf_conntrack_max", "net.netfilter.nf_conntrack_count",
			"fs.file-max", "fs.file-nr", "fs.nr_open",
			"vm.swappiness", "vm.max_map_count", "vm.overcommit_memory"
	};

	private static final String[] LIMIT_ROWS = {
			"Max open files", "Max processes", "Max locked memory", "Max stack size"
	};

	/// Protocol counters from /proc/net/snmp and /proc/net/netstat (world-readable):
	/// the "are we dropping SIP packets" signals. {proto, counterName}.
	private static final String[][] SNMP_WANTS = {
			{"Udp", "InDatagrams"}, {"Udp", "NoPorts"}, {"Udp", "InErrors"},
			{"Udp", "RcvbufErrors"}, {"Udp", "SndbufErrors"},
			{"Tcp", "ActiveOpens"}, {"Tcp", "PassiveOpens"}, {"Tcp", "CurrEstab"}, {"Tcp", "RetransSegs"}
	};
	private static final String[][] NETSTAT_WANTS = {
			{"TcpExt", "SyncookiesSent"}, {"TcpExt", "SyncookiesFailed"},
			{"TcpExt", "ListenOverflows"}, {"TcpExt", "ListenDrops"}, {"TcpExt", "TCPBacklogDrop"}
	};
	private static final String[] IFACE_STATS = {
			"rx_dropped", "rx_errors", "rx_missed_errors", "rx_fifo_errors", "tx_dropped"
	};

	private final String serverName;
	private final Path base;

	public KernelProbe(String serverName, Path base) {
		this.serverName = (serverName != null && !serverName.isEmpty()) ? serverName : "standalone";
		this.base = base != null ? base : Paths.get("/");
	}

	@Override
	public String readJson() {
		ObjectNode root = mapper.createObjectNode();
		root.put("server", serverName);
		root.put("os", System.getProperty("os.name", ""));

		ObjectNode readings = root.putObject("readings");
		for (String key : SYSCTL) {
			readings.put(key, readFirstLine(base.resolve("proc/sys/" + key.replace('.', '/'))));
		}
		readings.put("transparent_hugepage",
				readFirstLine(base.resolve("sys/kernel/mm/transparent_hugepage/enabled")));
		readings.put("cpu_scaling_governor",
				readFirstLine(base.resolve("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));

		root.set("limits", readLimits(base.resolve("proc/self/limits")));

		ObjectNode net = root.putObject("netCounters");
		addCounters(net, base.resolve("proc/net/snmp"), SNMP_WANTS);
		addCounters(net, base.resolve("proc/net/netstat"), NETSTAT_WANTS);
		readSockstat(root.putObject("sockstat"), base.resolve("proc/net/sockstat"));
		root.set("interfaces", readInterfaces(base.resolve("sys/class/net")));

		try {
			return mapper.writeValueAsString(root);
		} catch (Exception e) {
			return "{\"server\":\"" + serverName + "\",\"error\":\"json\"}";
		}
	}

	/// First line of a file, whitespace-collapsed (handles tab-separated values
	/// like tcp_rmem). Missing / unreadable file -> "n/a"; never throws.
	private String readFirstLine(Path p) {
		try {
			if (!Files.isReadable(p)) return "n/a";
			List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
			return lines.isEmpty() ? "" : lines.get(0).trim().replaceAll("\\s+", " ");
		} catch (IOException | RuntimeException e) {
			return "n/a";
		}
	}

	/// /proc/self/limits rows we care about, rendered as "soft / hard".
	private ObjectNode readLimits(Path p) {
		ObjectNode limits = mapper.createObjectNode();
		try {
			if (!Files.isReadable(p)) {
				for (String w : LIMIT_ROWS) limits.put(w, "n/a");
				return limits;
			}
			List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
			for (String w : LIMIT_ROWS) {
				String val = "n/a";
				for (String line : lines) {
					if (line.startsWith(w)) {
						String[] cols = line.substring(w.length()).trim().split("\\s+");
						if (cols.length >= 2) val = cols[0] + " / " + cols[1];
						else if (cols.length == 1) val = cols[0];
						break;
					}
				}
				limits.put(w, val);
			}
		} catch (Exception e) {
			for (String w : LIMIT_ROWS) limits.put(w, "n/a");
		}
		return limits;
	}

	/// /proc/net/snmp and /proc/net/netstat are header/value line pairs per
	/// protocol ("Udp: name1 name2 ..." then "Udp: val1 val2 ..."). Match wanted
	/// counters by name -> the value at the same column. Output keys "Proto.Name".
	private void addCounters(ObjectNode out, Path p, String[][] wants) {
		try {
			if (!Files.isReadable(p)) {
				for (String[] w : wants) out.put(w[0] + "." + w[1], "n/a");
				return;
			}
			Map<String, String[]> headers = new HashMap<>();
			Map<String, String[]> values = new HashMap<>();
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
				int colon = line.indexOf(':');
				if (colon < 0) continue;
				String proto = line.substring(0, colon);
				String[] toks = line.substring(colon + 1).trim().split("\\s+");
				if (!headers.containsKey(proto)) headers.put(proto, toks); // 1st = header
				else if (!values.containsKey(proto)) values.put(proto, toks); // 2nd = values
			}
			for (String[] w : wants) {
				String val = "n/a";
				String[] h = headers.get(w[0]), v = values.get(w[0]);
				if (h != null && v != null) {
					for (int i = 0; i < h.length; i++) {
						if (h[i].equals(w[1])) { if (i < v.length) val = v[i]; break; }
					}
				}
				out.put(w[0] + "." + w[1], val);
			}
		} catch (Exception e) {
			for (String[] w : wants) out.put(w[0] + "." + w[1], "n/a");
		}
	}

	/// /proc/net/sockstat: "TCP: inuse 100 orphan 0 tw 50 ..." -> keys "TCP.inuse" etc.
	private void readSockstat(ObjectNode out, Path p) {
		try {
			if (!Files.isReadable(p)) return;
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
				int colon = line.indexOf(':');
				if (colon < 0) continue;
				String proto = line.substring(0, colon).trim();
				String[] toks = line.substring(colon + 1).trim().split("\\s+");
				for (int i = 0; i + 1 < toks.length; i += 2) out.put(proto + "." + toks[i], toks[i + 1]);
			}
		} catch (Exception ignore) {
		}
	}

	/// Per-NIC drop/error counters from /sys/class/net/<dev>/statistics/* (world-
	/// readable). Skips loopback. Sorted for stable output.
	private ArrayNode readInterfaces(Path netDir) {
		ArrayNode arr = mapper.createArrayNode();
		try {
			if (!Files.isDirectory(netDir)) return arr;
			Map<String, Path> ifaces = new TreeMap<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(netDir)) {
				for (Path ifc : ds) {
					String name = ifc.getFileName().toString();
					if (!"lo".equals(name)) ifaces.put(name, ifc);
				}
			}
			for (Map.Entry<String, Path> e : ifaces.entrySet()) {
				ObjectNode n = arr.addObject();
				n.put("name", e.getKey());
				Path st = e.getValue().resolve("statistics");
				for (String s : IFACE_STATS) n.put(s, readFirstLine(st.resolve(s)));
			}
		} catch (Exception ignore) {
		}
		return arr;
	}
}
