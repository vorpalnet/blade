package org.vorpal.blade.applications.dashboard;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A place on the network map: a datacenter or a carrier's edge, with the
/// servers and addresses that live there.
///
/// A site has one or more roles. `cluster` is where this domain's servers run;
/// the map draws a line from it to every `downstream` site (servers calls are
/// sent to) and from every `inbound` site (SBCs and carrier edges calls arrive
/// from). A carrier that both delivers and takes calls is both.
///
/// What belongs to a site is matched by address: a balancer endpoint's URI, a
/// gateway's registrar, a call's first INVITE's source, and the Request-URI of
/// a call a downstream server refused, a datasource's JDBC URL. An address is
/// a host name, an IPv4 address, or an IPv4 CIDR block; a host name also
/// matches the addresses it resolves to. This domain's own servers are named
/// in `servers`, and its datasources in `datasources`.
public class NetworkSite implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private String city;
	private Double lat;
	private Double lon;
	private List<String> roles = new ArrayList<>();
	private List<String> servers = new ArrayList<>();
	private List<String> addresses = new ArrayList<>();
	private List<String> datasources = new ArrayList<>();
	private Integer quietMinutes;

	public NetworkSite() {
	}

	public NetworkSite(String name, String city, double lat, double lon, List<String> roles) {
		this.name = name;
		this.city = city;
		this.lat = lat;
		this.lon = lon;
		this.roles = new ArrayList<>(roles);
	}

	@JsonPropertyDescription("The site's name on the map, such as a datacenter or a carrier's edge.")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("Where it is, in words: the city shown beside the name.")
	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	@JsonPropertyDescription("Latitude, in decimal degrees. A site without coordinates is listed beside the map, not drawn on it.")
	public Double getLat() {
		return lat;
	}

	public void setLat(Double lat) {
		this.lat = lat;
	}

	@JsonPropertyDescription("Longitude, in decimal degrees; negative west of Greenwich.")
	public Double getLon() {
		return lon;
	}

	public void setLon(Double lon) {
		this.lon = lon;
	}

	@JsonPropertyDescription("What the site is to this domain: cluster (this domain's servers run here), inbound (calls arrive from here), downstream (calls are sent here). One or more.")
	public List<String> getRoles() {
		return roles;
	}

	public void setRoles(List<String> roles) {
		this.roles = roles == null ? new ArrayList<>() : roles;
	}

	@JsonPropertyDescription("This domain's WebLogic servers at this site, by name, such as engine0 or media0.")
	public List<String> getServers() {
		return servers;
	}

	public void setServers(List<String> servers) {
		this.servers = servers == null ? new ArrayList<>() : servers;
	}

	@JsonPropertyDescription("The addresses that belong to this site: host names, IPv4 addresses, or IPv4 CIDR blocks such as 192.0.2.0/28.")
	public List<String> getAddresses() {
		return addresses;
	}

	public void setAddresses(List<String> addresses) {
		this.addresses = addresses == null ? new ArrayList<>() : addresses;
	}

	@JsonPropertyDescription("This domain's JDBC datasources whose database is at this site, by name, such as BladeAnalytics. Needed when the datasource's URL names no host (a TNS alias); a URL that does is matched against the addresses.")
	public List<String> getDatasources() {
		return datasources;
	}

	public void setDatasources(List<String> datasources) {
		this.datasources = datasources == null ? new ArrayList<>() : datasources;
	}

	@JsonPropertyDescription("For an inbound site: minutes without a call after which the map warns that it has gone quiet. Empty for no warning, as for an SBC that may rightly be idle for hours.")
	public Integer getQuietMinutes() {
		return quietMinutes;
	}

	public void setQuietMinutes(Integer quietMinutes) {
		this.quietMinutes = quietMinutes;
	}

	boolean has(String role) {
		return roles != null && roles.contains(role);
	}
}
