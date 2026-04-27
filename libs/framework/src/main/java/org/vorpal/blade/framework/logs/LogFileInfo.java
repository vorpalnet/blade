package org.vorpal.blade.framework.logs;

import java.beans.ConstructorProperties;

/// Metadata about a single log file. JMX MXBean value type — `@ConstructorProperties`
/// lets the platform reconstruct it from a CompositeData on the admin side
/// without us shipping the class to the consumer.
public class LogFileInfo {

	public static final String KIND_WLS_SERVER = "WLS_SERVER";
	public static final String KIND_WLS_ACCESS = "WLS_ACCESS";
	public static final String KIND_VORPAL_APP = "VORPAL_APP";
	public static final String KIND_OTHER = "OTHER";

	private final String relativePath;
	private final long sizeBytes;
	private final long lastModifiedMs;
	private final String kind;

	@ConstructorProperties({ "relativePath", "sizeBytes", "lastModifiedMs", "kind" })
	public LogFileInfo(String relativePath, long sizeBytes, long lastModifiedMs, String kind) {
		this.relativePath = relativePath;
		this.sizeBytes = sizeBytes;
		this.lastModifiedMs = lastModifiedMs;
		this.kind = kind;
	}

	public String getRelativePath() { return relativePath; }
	public long getSizeBytes() { return sizeBytes; }
	public long getLastModifiedMs() { return lastModifiedMs; }
	public String getKind() { return kind; }
}
