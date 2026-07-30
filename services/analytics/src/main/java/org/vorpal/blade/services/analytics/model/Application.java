package org.vorpal.blade.services.analytics.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.Lob;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.servlet.sip.SipServletContextEvent;

/// One application instance — one app, on one server, with one configuration.
/// A restart is a new instance, deliberately.
///
/// **The key is DB-assigned and resolved from the natural key.** The producer
/// used to mint `id` itself as a random 64-bit value and put it on the wire — a
/// surrogate primary key invented by the one participant with no database, with a
/// stated ~1e-11 collision risk. `(name, domain, server, created)` says the same
/// thing and is already on every event, so [Application#NATURAL_KEY] resolves it
/// here instead.
@Entity
@Table(name = "applications")
@NamedQueries({
		@NamedQuery(name = "Application.findAll", query = "SELECT a FROM Application a"),
		@NamedQuery(name = Application.NATURAL_KEY,
				query = "SELECT a FROM Application a WHERE a.name = :name AND a.domain = :domain"
						+ " AND a.server = :server AND a.created = :created") })
public class Application implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Resolve an instance by what it is, rather than by a key the producer
	/// invented.
	public static final String NATURAL_KEY = "Application.findByNaturalKey";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(updatable = false, unique = true, nullable = false)
	private long id;

	@Lob
	private String comments;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = false)
	private Date created;

	/// When the instance stopped.
	///
	/// **Writable, unlike `created`.** This was `updatable = false`, which meant
	/// `application.stopped` could not be recorded through JPA at all: the merge
	/// ran, reported success, and silently did not write the column. The SQL
	/// column has always been nullable and writable — only the mapping disagreed.
	@Temporal(TemporalType.TIMESTAMP)
	@Column(nullable = true)
	private Date destroyed;

	@Column(length = 64)
	private String domain;

	@Column(length = 256)
	private String host;

	@Column(nullable = false, length = 32)
	private String name;

	@Column(length = 64)
	private String server;

	/** Customer code for multi-tenant RLS; null on single-tenant deployments. */
	@Column(length = 64)
	private String tenant;

	@Column(length = 16)
	private String version;

	public Application() {
	}

	public Application(SipServletContextEvent event) {
	}

	public long getId() {
		return this.id;
	}

	public void setId(long id) {
		this.id = id;
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

	public String getTenant() {
		return this.tenant;
	}

	public void setTenant(String tenant) {
		this.tenant = tenant;
	}

	public String getVersion() {
		return this.version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

}