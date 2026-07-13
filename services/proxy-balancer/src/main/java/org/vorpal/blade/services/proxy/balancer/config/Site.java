package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A named physical location — datacenter, POP, region — defined once in the
/// top-level `sites` registry and referenced by endpoints via their `site`
/// field. The admin GUI's map view places sites by coordinates and rolls
/// endpoint health up to the site glyph. Coordinates are optional: a site
/// without lat/lon still groups its endpoints; it just renders in the map's
/// "unplaced" tray instead of on the map.
public class Site implements Serializable {
	private static final long serialVersionUID = 1L;

	private String label;
	private Double lat;
	private Double lon;

	public Site() {
	}

	public Site(String label) {
		this.label = label;
	}

	public Site(String label, Double lat, Double lon) {
		this.label = label;
		this.lat = lat;
		this.lon = lon;
	}

	@JsonPropertyDescription("Display name, e.g. 'Eastern DC'; the registry key is shown when absent")
	public String getLabel() {
		return label;
	}

	public Site setLabel(String label) {
		this.label = label;
		return this;
	}

	@JsonPropertyDescription("Latitude in decimal degrees, positive north; omit to keep the site off the map")
	public Double getLat() {
		return lat;
	}

	public Site setLat(Double lat) {
		this.lat = lat;
		return this;
	}

	@JsonPropertyDescription("Longitude in decimal degrees, negative west; omit to keep the site off the map")
	public Double getLon() {
		return lon;
	}

	public Site setLon(Double lon) {
		this.lon = lon;
		return this;
	}

}
