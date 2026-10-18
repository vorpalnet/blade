package org.vorpal.blade.applications.balancer;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Settings for the Balancer Health admin app. The tool is read-only — it
/// aggregates every engine node's endpoint-health view over federated JMX —
/// so it carries only the inherited metadata for the portal deck.
@SchemaAbout(
		name = "Balancer",
		tagline = "Is every endpoint taking calls?",
		description = "Every engine node's live view of the Proxy Balancer's endpoint pool — "
				+ "each plan, tier, and endpoint marked UP, DOWN, or backing off (503 Retry-After), "
				+ "with the last observation that decided it. Nodes ping and mark independently; "
				+ "disagreement between nodes is itself a diagnostic.")
public class BalancerSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public BalancerSettings() {
	}
}
