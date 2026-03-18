package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The persistent class for the event database table.
 * 
 */
@Entity
@Table(name = "event")
@NamedQuery(name = "Event.findAll", query = "SELECT e FROM Event e")
@JsonPropertyOrder({"name", "attributes", "id", "application_id","sessionId", "created"})
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
	private Long sessionId;

	// unidirectional one-to-many association to Attribute
	@OneToMany(cascade = { CascadeType.ALL }, orphanRemoval = true)
	@JoinColumn(name = "event_id", nullable = false)
	@MapKeyColumn(name = "name", insertable = false, updatable = false)
	private Map<String, Attribute> attributes = new HashMap<>();

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

		// save and remove the attributes
		Map<String, Attribute> _attributes = this.getAttributes();
		this.attributes = new HashMap<>();

		// persist just the event and flush to get the generated id
		em.persist(this);
		em.flush();

		// persist each attribute individually with the correct event id
		for (Attribute attr : _attributes.values()) {
			attr.getId().setEventId(id);
			em.persist(attr);
		}

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

	public Long getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public Map<String, Attribute> getAttributes() {
		return this.attributes;
	}

	public void setAttributes(Map<String, Attribute> attributes) {
		this.attributes = attributes;
	}

	public Attribute addAttribute(Attribute attribute) {
		getAttributes().put(attribute.getId().getName(), attribute);
		return attribute;
	}

	public Attribute removeAttribute(Attribute attribute) {
		getAttributes().remove(attribute.getId().getName());
		return attribute;
	}

}