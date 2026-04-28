"use strict";

const $ = (id) => document.getElementById(id);
const setStatus = (msg, isError) => {
	const el = $("status");
	el.textContent = msg;
	el.classList.toggle("error", !!isError);
};

async function loadServers() {
	const r = await fetch("api/servers");
	if (!r.ok) {
		const body = await r.text();
		throw new Error("GET /api/servers failed: " + r.status + " " + body);
	}
	const list = await r.json();
	const sel = $("server");
	sel.innerHTML = "";
	for (const s of list) {
		const opt = document.createElement("option");
		opt.value = s.name;
		const cluster = s.cluster ? " [" + s.cluster + "]" : "";
		opt.textContent = s.name + cluster;
		sel.appendChild(opt);
	}
	if (list.length === 0) {
		setStatus("No servers found via DomainConfiguration.", true);
	}
}

async function loadLogFiles() {
	const server = $("server").value;
	if (!server) return;
	setStatus("Loading log catalog for " + server + "…");
	const r = await fetch("api/servers/" + encodeURIComponent(server) + "/logs");
	if (!r.ok) {
		setStatus("Catalog request failed: " + r.status, true);
		return;
	}
	const files = await r.json();
	const sel = $("logFile");
	sel.innerHTML = "";
	files.sort((a, b) => a.relativePath.localeCompare(b.relativePath));
	for (const f of files) {
		const opt = document.createElement("option");
		opt.value = f.relativePath;
		const sizeKb = (f.sizeBytes / 1024).toFixed(1);
		opt.textContent = f.relativePath + "   (" + f.kind + ", " + sizeKb + " KB)";
		sel.appendChild(opt);
	}
	setStatus(files.length + " log files on " + server);
}

async function loadTail() {
	const server = $("server").value;
	const file = $("logFile").value;
	const max = parseInt($("tailBytes").value, 10) || 65536;
	if (!server || !file) {
		setStatus("Select a server and log file first.", true);
		return;
	}
	setStatus("Loading last " + max + " bytes of " + file + " from " + server + "…");
	const url = "api/servers/" + encodeURIComponent(server) + "/logs/" + file
		.split("/").map(encodeURIComponent).join("/")
		+ "?offset=-1&max=" + max;
	const r = await fetch(url);
	if (!r.ok) {
		setStatus("Slice request failed: " + r.status, true);
		return;
	}
	const text = await r.text();
	$("output").textContent = text;
	setStatus("Loaded " + text.length + " chars from " + file + " on " + server);
}

document.addEventListener("DOMContentLoaded", async () => {
	try {
		await loadServers();
		$("server").addEventListener("change", loadLogFiles);
		$("load").addEventListener("click", loadTail);
		await loadLogFiles();
	} catch (e) {
		setStatus(String(e), true);
	}
});
