Design Notes

Consider this;

JDBC datasource creation: The recording session has started. Recording to /Users/jeff/Oracle/occas-8.1/user_projects/domains/gamera/Script1754412054124.py.





The recording session has started. Recording to /Users/jeff/Oracle/occas-8.1/user_projects/domains/replicated/Script1746049307491.py.


Research: @MessageDriven annotation

Step 1
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-1-how-to-create-a-simple-jms-queue-in-weblogic-server-11g

Step 2
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-2-using-the-queuesendjava-sample-program-to-send-a-message-to-a-jms-queue

Step 3
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-3-using-the-queuereceivejava-sample-program-to-read-a-message-from-a-jms-queue

Step 4
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-4-how-to-create-an-11g-bpel-process-which-writes-a-message-based-on-an-xml-schema-to-a-jms-queue

Step 5
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-5-how-to-create-an-11g-bpel-process-which-reads-a-message-based-on-an-xml-schema-from-a-jms-queue

Step 6
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-6-how-to-set-up-an-aq-jms-advanced-queueing-jms-for-soa-purposes

Step 7
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-7-how-to-write-to-an-aq-jms-advanced-queueing-jms-queue-from-a-bpel-process

Step 8
https://blogs.oracle.com/fusionmiddlewaresupport/post/jms-step-8-how-to-read-from-an-aq-jms-advanced-queueing-jms-from-a-bpel-process



Manual Edits to generates source files...

In Eclipse, switch to JPA Perspective, then Disconnect / Connect

In Eclipse > Right mouse click on Project > JPA Tools > Generate Entities from Tables



Exception [EclipseLink-46] (Eclipse Persistence Services - 2.7.6.v20200131-b7c997804f): org.eclipse.persistence.exceptions.DescriptorException
Exception Description: There should be one non-read-only mapping defined for the primary key field [event_attribute.event_id].
Descriptor: RelationalDescriptor(org.vorpal.blade.services.analytics.jpa.EventAttribute --> [DatabaseTable(event_attribute)])






In Selector:
	//uni-directional many-to-one association to Session
	@ManyToOne
	@JoinColumns({
		@JoinColumn(name="session_created", referencedColumnName="created", nullable=false),
		@JoinColumn(name="session_name", referencedColumnName="name", nullable=false)
		})
	private Session session;

in Event:
	// jwm - hand coded
	@ElementCollection
	@CollectionTable(name = "attribute", joinColumns = @JoinColumn(name = "event_id"))
	@MapKeyColumn(name = "name")
	@Column(name = "value")
	private Map<String, String> attributes;

	// uni-directional many-to-one association to Session
	@ManyToOne(cascade = { CascadeType.ALL })
	@JoinColumns({ @JoinColumn(name = "session_created", referencedColumnName = "created"),
			@JoinColumn(name = "session_name", referencedColumnName = "name") })
	private Session session;


in Attribute:
	//bi-directional many-to-one association to Event
	@ManyToOne(cascade={CascadeType.ALL})
	@JoinColumn(name="event_id", nullable=false, insertable=false, updatable=true)
	private Event event;











