package org.vorpal.blade.services.analytics.model;

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

import org.vorpal.blade.framework.v3.analytics.NaturalKey;

/// One application instance — one app, on one server, with one configuration.
/// A restart is a new instance, deliberately.
///
/// **The key is [#idFor] of `(name, domain, server, created)`** — computed, not
/// assigned, and not invented either.
///
/// Both earlier designs are worth naming, because this one is neither. The
/// producer originally minted `id` as a *random* 64-bit value and put it on the
/// wire: reproducible by nobody, so the same instance seen twice became two
/// rows. Replacing that with a database-assigned key fixed the duplication and
/// bought a different problem — the key had to be read back before any event
/// could reference it. Hashing the natural key keeps what both were reaching
/// for: every node computes the same id for the same instance, before the
/// insert, without a round trip.
///
/// The natural-key lookup query this class used to carry is gone: with the key
/// computed, resolving an instance is a primary-key `find`, and a query that
/// asks the database to search on the same four columns the key already
/// encodes is just a slower way to get the same row.
@Entity
@Table(name = "applications")
@NamedQuery(name = "Application.findAll", query = "SELECT a FROM Application a")
public class Application implements Serializable {
	private static final long serialVersionUID = 1L;

	/// This instance's key, from the four fields every event already carries.
	public static long idFor(String name, String domain, String server, Date created) {
		return NaturalKey.idFor(name, domain, server, created);
	}

	@Id
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

	@Column(length = 128)
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