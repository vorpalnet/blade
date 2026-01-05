package org.vorpal.blade.services.analytics.jpa;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * The persistent class for the event database table.
 * 
 */
@Entity
@Table(name = "event")
@NamedQuery(name = "Event.findAll", query = "SELECT e FROM Event e")
public class Event implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(updatable = false, unique = true, nullable = false)
	private long id;

	@Column(name = "application_id", nullable = false)
	private int applicationId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable = false, nullable = false)
	private Date created;

	@Column(nullable = false, length = 64)
	private String name;

	@Column(name = "session_id")
	private long sessionId;

	// unidirectional one-to-many association to Attribute
	@OneToMany(cascade = { CascadeType.ALL }, orphanRemoval = true)
	@JoinColumn(name = "event_id", nullable = false)
	private List<Attribute> attributes = new LinkedList<Attribute>();

	public Event() {
		this.setCreated(new Date());
	}

	public Event(int applicationId, long sessionId, String name) {
		this.applicationId = applicationId;
		this.sessionId = sessionId;
		this.name = name;
		this.setCreated(new Date());
	}

	public Event addAttribute(String name, String value) {
		addAttribute(new Attribute(name, value));
		return this;
	}

	// jwm - handcoded method to update the AttributePK to include the latest
	// Event.id
	public void persistEvent(EntityManager em) {

		// remove the attributes
		List<Attribute> _attributes = this.getAttributes();
		this.attributes = new LinkedList<Attribute>();

		// persist just the event
		em.getTransaction().begin();
		em.persist(this);
		em.getTransaction().commit();

		// update the event id in the attributes
		for (Attribute attr : _attributes) {
			attr.getId().setEventId(id);
		}

		// restore the attributes
		this.attributes = _attributes;

		// persist the attributes
		em.getTransaction().begin();
		em.persist(this);
		em.getTransaction().commit();

	}

	public long getId() {
		return this.id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public int getApplicationId() {
		return this.applicationId;
	}

	public void setApplicationId(int applicationId) {
		this.applicationId = applicationId;
	}

	public Date getCreated() {
		return this.created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public long getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(long sessionId) {
		this.sessionId = sessionId;
	}

	public List<Attribute> getAttributes() {
		return this.attributes;
	}

	public void setAttributes(List<Attribute> attributes) {
		this.attributes = attributes;
	}

	public Attribute addAttribute(Attribute attribute) {
		getAttributes().add(attribute);
		return attribute;
	}

	public Attribute removeAttribute(Attribute attribute) {
		getAttributes().remove(attribute);
		return attribute;
	}

}