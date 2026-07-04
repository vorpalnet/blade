package org.vorpal.blade.applications.files;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// How the Files app reaches Node Manager to restart the AdminServer.
///
/// These are the same knobs `misc/start-admin-nm.sh` already takes — the editor
/// launches that script detached and passes these through as environment. The
/// Node Manager password is deliberately NOT here: the webapp never handles the
/// secret. The operator places a `.nmsecret` file next to the script (the
/// existing convention), and the detached script sources it.
///
/// `scriptPath` is empty by default, which leaves the restart capability OFF
/// (fail-loud: the endpoint reports "not configured" rather than guessing a
/// path). Set it once per site to the absolute path of `start-admin-nm.sh`.
public class ServerControlConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String scriptPath = "";
	protected String adminServerName = "AdminServer";
	protected String nmHost = "localhost";
	protected int nmPort = 5556;
	protected String nmType = "ssl";
	protected String nmUser = "weblogic";

	@JsonPropertyDescription("Absolute path to the detached restart helper (misc/start-admin-nm.sh) on the AdminServer host. "
			+ "Empty (the default) disables the restart button — the endpoint reports it is not configured rather than guess a path. "
			+ "Place the .nmsecret file (one line: NM_PASSWORD=...) next to this script.")
	public String getScriptPath() {
		return scriptPath;
	}

	public ServerControlConfig setScriptPath(String scriptPath) {
		this.scriptPath = scriptPath;
		return this;
	}

	@JsonPropertyDescription("WebLogic name of the AdminServer to restart. Default \"AdminServer\".")
	public String getAdminServerName() {
		return adminServerName;
	}

	public ServerControlConfig setAdminServerName(String adminServerName) {
		this.adminServerName = adminServerName;
		return this;
	}

	@JsonPropertyDescription("Node Manager listen address on the AdminServer host. Default \"localhost\".")
	public String getNmHost() {
		return nmHost;
	}

	public ServerControlConfig setNmHost(String nmHost) {
		this.nmHost = nmHost;
		return this;
	}

	@JsonPropertyDescription("Node Manager listen port. Default 5556.")
	public int getNmPort() {
		return nmPort;
	}

	public ServerControlConfig setNmPort(int nmPort) {
		this.nmPort = nmPort;
		return this;
	}

	@JsonPropertyDescription("Node Manager connection type — \"ssl\" or \"plain\". Must match the SecureListener in nodemanager.properties. Default \"ssl\".")
	public String getNmType() {
		return nmType;
	}

	public ServerControlConfig setNmType(String nmType) {
		this.nmType = nmType;
		return this;
	}

	@JsonPropertyDescription("Node Manager username. Default \"weblogic\".")
	public String getNmUser() {
		return nmUser;
	}

	public ServerControlConfig setNmUser(String nmUser) {
		this.nmUser = nmUser;
		return this;
	}
}
