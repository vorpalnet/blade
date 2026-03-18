package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.servlet.sip.SipServletContextEvent;

/**
 * The persistent class for the application database table.
 * 
 */
@Entity
@Table(name = "application")
@NamedQuery(name = "Application.findAll", query = "SELECT a FROM Application a")
public class Application implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(updatable = false, unique = true, nullable = false)
	private int id;

	@Lob
	private String collisions;

	@Lob
	private String comments;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = false)
	private Date created;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = true)
	private Date destroyed;

	@Column(length = 64)
	private String domain;

	@Column(length = 256)
	private String host;

	@Column(nullable = false, length = 32)
	private String name;

	@Column(length = 64)
	private String server;

	@Column(length = 16)
	private String version;

	public Application() {
	}

	public Application(SipServletContextEvent event) {
	}

	public int getId() {
		return this.id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getCollisions() {
		return this.collisions;
	}

	public void setCollisions(String collisions) {
		this.collisions = collisions;
	}

	public String getComments() {
		return this.comments;
	}

	public void setComments(String comments) {
		this.comments = comments;
	}

	public Date getCreated() {
		return this.created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	public Date getDestroyed() {
		return this.destroyed;
	}

	public void setDestroyed(Date destroyed) {
		this.destroyed = destroyed;
	}

	public String getDomain() {
		return this.domain;
	}

	public void setDomain(String domain) {
		this.domain = domain;
	}

	public String getHost() {
		return this.host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getServer() {
		return this.server;
	}

	public void setServer(String server) {
		this.server = server;
	}

	public String getVersion() {
		return this.version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

}