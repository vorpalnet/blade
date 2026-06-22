package org.vorpal.blade.framework.v3.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke-test driver for [KernelProbe] against a FAKE /proc tree (so it runs on
/// macOS, which has no /proc). Verifies the sysctl key -> path mapping
/// (dots -> slashes), whitespace collapsing, the /proc/self/limits parse, and
/// that missing files become "n/a". Plain main() convention; exits non-zero on
/// the first failed expectation.
///
/// ```
/// mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/// java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
///   org.vorpal.blade.framework.v3.probe.KernelProbeSmokeTest
/// ```
public class KernelProbeSmokeTest {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path base = Files.createTempDirectory("kprobe");

		// sysctl: net.core.somaxconn -> proc/sys/net/core/somaxconn
		write(base, "proc/sys/net/core/somaxconn", "4096\n");
		// tab-separated triplet, should collapse to single spaces
		write(base, "proc/sys/net/ipv4/tcp_rmem", "4096\t87380\t6291456\n");
		// THP and governor
		write(base, "sys/kernel/mm/transparent_hugepage/enabled", "always [madvise] never\n");
		write(base, "sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "performance\n");
		// /proc/self/limits-style file
		write(base, "proc/self/limits",
				"Limit                     Soft Limit           Hard Limit           Units\n"
				+ "Max open files            65536                65536                files\n"
				+ "Max processes             200000               200000               processes\n");

		// /proc/net/snmp: header/value line pairs per protocol.
		write(base, "proc/net/snmp",
				"Udp: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors\n"
				+ "Udp: 1000 5 7 990 8 0\n"
				+ "Tcp: RtoAlgorithm RtoMin ActiveOpens PassiveOpens CurrEstab InSegs RetransSegs\n"
				+ "Tcp: 1 200 11 22 33 100 44\n");
		write(base, "proc/net/netstat",
				"TcpExt: SyncookiesSent SyncookiesFailed ListenOverflows ListenDrops TCPBacklogDrop\n"
				+ "TcpExt: 2 1 9 10 3\n");
		write(base, "proc/net/sockstat",
				"sockets: used 500\nTCP: inuse 100 orphan 0 tw 50 alloc 120 mem 10\nUDP: inuse 30 mem 5\n");
		write(base, "sys/class/net/eth0/statistics/rx_dropped", "12\n");
		write(base, "sys/class/net/eth0/statistics/tx_dropped", "0\n");
		write(base, "sys/class/net/lo/statistics/rx_dropped", "999\n"); // must be skipped
		// kernel boot cmdline: numa=off present, transparent_hugepage NOT present
		write(base, "proc/cmdline", "BOOT_IMAGE=/vmlinuz root=/dev/sda1 ro numa=off crashkernel=auto\n");
		// virtualization signals
		write(base, "proc/stat", "cpu  100 0 50 800 0 0 0 50 0 0\ncpu0 100 0 50 800 0 0 0 50 0 0\n"); // steal=50/1000=5%
		write(base, "proc/modules", "vmw_balloon 36864 0 - Live 0x0000000000000000\nip_tables 32768 0 - Live 0x0\n");
		write(base, "sys/class/dmi/id/sys_vendor", "VMware, Inc.\n");
		write(base, "sys/class/dmi/id/product_name", "VMware Virtual Platform\n");
		write(base, "sys/devices/system/clocksource/clocksource0/current_clocksource", "tsc\n");
		// eth0 NIC driver via the device/driver symlink
		Files.createDirectories(base.resolve("sys/class/net/eth0/device"));
		Files.createSymbolicLink(base.resolve("sys/class/net/eth0/device/driver"), Paths.get("../../../bus/pci/drivers/vmxnet3"));

		String json = new KernelProbe("engine1", base).readJson();
		JsonNode root = mapper.readTree(json);
		JsonNode r = root.get("readings");

		expect("engine1".equals(root.path("server").asText()), "server name carried through");
		expect("4096".equals(r.path("net.core.somaxconn").asText()), "somaxconn maps dots->slashes and reads value");
		expect("4096 87380 6291456".equals(r.path("net.ipv4.tcp_rmem").asText()), "tcp_rmem tabs collapsed to spaces");
		expect("always [madvise] never".equals(r.path("transparent_hugepage").asText()), "THP read raw");
		expect("performance".equals(r.path("cpu_scaling_governor").asText()), "cpu governor read");
		// a sysctl whose file we did NOT create -> n/a
		expect("n/a".equals(r.path("vm.swappiness").asText()), "missing file -> n/a");
		// limits parse -> "soft / hard"
		expect("65536 / 65536".equals(root.path("limits").path("Max open files").asText()), "nofile parsed soft/hard");
		expect("200000 / 200000".equals(root.path("limits").path("Max processes").asText()), "nproc parsed soft/hard");
		expect("n/a".equals(root.path("limits").path("Max locked memory").asText()), "absent limit row -> n/a");

		// /proc/net/snmp + netstat counters extracted by name->column
		JsonNode nc = root.get("netCounters");
		expect("7".equals(nc.path("Udp.InErrors").asText()), "snmp Udp.InErrors by column");
		expect("8".equals(nc.path("Udp.RcvbufErrors").asText()), "snmp Udp.RcvbufErrors by column");
		expect("44".equals(nc.path("Tcp.RetransSegs").asText()), "snmp Tcp.RetransSegs by column");
		expect("9".equals(nc.path("TcpExt.ListenOverflows").asText()), "netstat TcpExt.ListenOverflows by column");
		expect("2".equals(nc.path("TcpExt.SyncookiesSent").asText()), "netstat TcpExt.SyncookiesSent by column");

		// sockstat
		JsonNode ss = root.get("sockstat");
		expect("100".equals(ss.path("TCP.inuse").asText()), "sockstat TCP.inuse");
		expect("50".equals(ss.path("TCP.tw").asText()), "sockstat TCP.tw");
		expect("30".equals(ss.path("UDP.inuse").asText()), "sockstat UDP.inuse");

		// interfaces: eth0 present with stats, lo skipped
		JsonNode ifs = root.get("interfaces");
		expect(ifs.size() == 1 && "eth0".equals(ifs.get(0).path("name").asText()), "interfaces lists eth0, skips lo");
		expect("12".equals(ifs.get(0).path("rx_dropped").asText()), "eth0 rx_dropped read");
		expect("vmxnet3".equals(ifs.get(0).path("driver").asText()), "eth0 driver from device/driver symlink");

		// virtualization signals
		JsonNode virt = root.get("virt");
		expect("VMware, Inc.".equals(virt.path("vendor").asText()), "dmi sys_vendor read");
		expect("VMware Virtual Platform".equals(virt.path("product").asText()), "dmi product_name read");
		expect("tsc".equals(virt.path("clocksource").asText()), "clocksource read");
		expect("5.00".equals(virt.path("stealPct").asText()), "steal% computed from /proc/stat (50/1000)");
		expect("vmw_balloon".equals(virt.path("balloon").asText()), "balloon driver detected in /proc/modules");

		// boot cmdline: numa=off parsed to its value; absent param -> "(default)"
		JsonNode boot = root.get("boot");
		expect("off".equals(boot.path("numa").asText()), "boot numa=off parsed from cmdline");
		expect("(default)".equals(boot.path("transparent_hugepage").asText()), "boot THP absent -> (default)");

		// empty base dir -> everything n/a, never throws
		String empty = new KernelProbe("x", Files.createTempDirectory("kempty")).readJson();
		expect(mapper.readTree(empty).path("readings").path("fs.file-max").asText().equals("n/a"), "empty tree -> n/a, no throw");
		expect(mapper.readTree(empty).path("netCounters").path("Udp.RcvbufErrors").asText().equals("n/a"), "empty tree -> counters n/a");
		expect(mapper.readTree(empty).path("boot").path("numa").asText().equals("n/a"), "empty tree -> boot cmdline n/a");
		expect(mapper.readTree(empty).path("virt").path("balloon").asText().equals("n/a"), "empty tree -> virt balloon n/a");
		expect(mapper.readTree(empty).path("virt").path("stealPct").asText().equals("n/a"), "empty tree -> virt steal n/a");

		System.out.println(failures == 0 ? "KernelProbeSmokeTest: ALL PASSED" : "KernelProbeSmokeTest: " + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void write(Path base, String rel, String content) throws Exception {
		Path p = base.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.write(p, content.getBytes(StandardCharsets.UTF_8));
	}

	private static void expect(boolean ok, String label) {
		if (ok) {
			System.out.println("  ok   " + label);
		} else {
			failures++;
			System.out.println("  FAIL " + label);
		}
	}
}
