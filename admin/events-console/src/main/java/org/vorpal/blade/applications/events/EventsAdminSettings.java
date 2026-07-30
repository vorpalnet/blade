package org.vorpal.blade.applications.events;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Events console.
///
/// `@SchemaAbout` is what puts the launcher card on the Admin Portal deck: the
/// portal reads `title` / `x-tagline` / `description` straight out of this
/// class's generated schema. Without it the card renders as a humanized slug.
@SchemaAbout(
		name = "Events",
		tagline = "Event catalog, designer and JMS administration",
		description = "One place for BLADE messaging: declare event types and generate the code to produce and consume them, and administer the WebLogic JMS resources that carry them — destinations, quotas, durable subscriptions, depths and consumers.")
public class EventsAdminSettings extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	private boolean allowDestructiveOperations = true;
	private String protectedDestinations = "";

	@JsonPropertyDescription("Whether the console offers delete, purge and drain at all. Turning this off leaves the console read-write for creation and tuning but removes every irreversible action, which is a reasonable posture for a production domain.")
	public boolean isAllowDestructiveOperations() {
		return allowDestructiveOperations;
	}

	public void setAllowDestructiveOperations(boolean allowDestructiveOperations) {
		this.allowDestructiveOperations = allowDestructiveOperations;
	}

	@JsonPropertyDescription("Comma-separated JNDI names the console refuses to delete or purge, whatever the role. An allowlist of what may be destroyed would be safer still, but a denylist matches how operators actually think about the one or two destinations that must never go away.")
	public String getProtectedDestinations() {
		return protectedDestinations;
	}

	public void setProtectedDestinations(String protectedDestinations) {
		this.protectedDestinations = (protectedDestinations == null) ? "" : protectedDestinations;
	}
}
