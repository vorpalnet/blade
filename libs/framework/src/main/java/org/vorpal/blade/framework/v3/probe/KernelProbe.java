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

		// Kernel boot cmdline params (numa, transparent_hugepage) — these are set at
		// boot, not via /proc/sys, so they need their own read.
		ObjectNode boot = root.putObject("boot");
		String cmdline = readFirstLine(base.resolve("proc/cmdline"));
		boot.put("cmdline", cmdline);
		boot.put("numa", bootParam(cmdline, "numa"));
		boot.put("transparent_hugepage", bootParam(cmdline, "transparent_hugepage"));

		// Virtualization signals (no sudo): hypervisor identity, clocksource, CPU
		// steal %, balloon driver. All read-only; the frontend interprets/flags them.
		ObjectNode virt = root.putObject("virt");
		virt.put("vendor", readFirstLine(base.resolve("sys/class/dmi/id/sys_vendor")));
		virt.put("product", readFirstLine(base.resolve("sys/class/dmi/id/product_name")));
		virt.put("clocksource", readFirstLine(base.resolve("sys/devices/system/clocksource/clocksource0/current_clocksource")));
		virt.put("stealPct", readStealPct(base.resolve("proc/stat")));
		virt.put("balloon", readBalloon(base.resolve("proc/modules")));

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

	/// Value of a {@code name=value} token in the kernel boot cmdline. Returns
	/// the value, {@code "(set)"} for a bare flag, {@code "(default)"} when the
	/// param isn't on the cmdline, or {@code "n/a"} if the cmdline was unreadable.
	private String bootParam(String cmdline, String name) {
		if (cmdline == null || cmdline.isEmpty() || "n/a".equals(cmdline)) return "n/a";
		for (String tok : cmdline.split("\\s+")) {
			if (tok.equals(name)) return "(set)";
			if (tok.startsWith(name + "=")) return tok.substring(name.length() + 1);
		}
		return "(default)";
	}

	/// Steal time as a percent of total CPU time since boot, from the aggregate
	/// {@code cpu} line of /proc/stat (8th value = steal jiffies). A sustained
	/// non-zero value means the hypervisor is descheduling our vCPUs (host
	/// oversubscription). "n/a" if unreadable; never throws.
	private String readStealPct(Path p) {
		try {
			if (!Files.isReadable(p)) return "n/a";
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
				if (!line.startsWith("cpu ")) continue;
				String[] t = line.trim().split("\\s+");
				long total = 0, steal = 0;
				for (int i = 1; i < t.length; i++) {
					long v = Long.parseLong(t[i]);
					total += v;
					if (i == 8) steal = v; // user nice system idle iowait irq softirq STEAL ...
				}
				if (total <= 0) return "0.00";
				return String.format(java.util.Locale.ROOT, "%.2f", 100.0 * steal / total);
			}
			return "n/a";
		} catch (IOException | RuntimeException e) {
			return "n/a";
		}
	}

	/// Name of a loaded memory-balloon driver (vmw_balloon / virtio_balloon /
	/// hv_balloon) from /proc/modules, "none" if absent, "n/a" if unreadable.
	private String readBalloon(Path p) {
		try {
			if (!Files.isReadable(p)) return "n/a";
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
				String mod = line.split("\\s+", 2)[0];
				if (mod.equals("vmw_balloon") || mod.equals("virtio_balloon") || mod.equals("hv_balloon")) return mod;
			}
			return "none";
		} catch (IOException | RuntimeException e) {
			return "n/a";
		}
	}

	/// NIC driver name from the /sys/class/net/<dev>/device/driver symlink's
	/// target (e.g. vmxnet3, virtio_net, e1000). "n/a" if absent; never throws.
	private String readDriver(Path ifDir) {
		try {
			Path link = ifDir.resolve("device/driver");
			if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "n/a";
			Path target = Files.isSymbolicLink(link) ? Files.readSymbolicLink(link) : link;
			return target.getFileName().toString();
		} catch (IOException | RuntimeException e) {
			return "n/a";
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
				n.put("driver", readDriver(e.getValue())); // vmxnet3 / virtio_net (good) vs e1000 (emulated)
				Path st = e.getValue().resolve("statistics");
				for (String s : IFACE_STATS) n.put(s, readFirstLine(st.resolve(s)));
			}
		} catch (Exception ignore) {
		}
		return arr;
	}
}
