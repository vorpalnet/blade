package org.vorpal.blade.applications.portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Aggregates the portal launcher deck.
///
/// Two JMX walks compose the deck:
///
/// 1. **`WebApplicationRuntimeMBean` walk** — every deployed admin app
///    (context-root starts with `blade/`, `ApplicationRuntime.ActiveVersionState == 2`).
///    Every match becomes a card. The portal filters itself out.
/// 2. **`SettingsMXBean` walk** — query `vorpal.blade:Name=*,Type=Configuration,*`
///    and invoke `getCurrentJson()` on each match to read the top-level `name`,
///    metadata. Same federated DomainRuntime path Configurator uses.
///
/// **Join key**: lastSegment(contextRoot) — admin convention is that
/// `<wls:context-root>blade/<app></wls:context-root>` ends in the same slug
/// the WAR uses as `<display-name>`, which is the `Name=` key the
/// SettingsMXBean registers under. See memory `[[portal-card-discovery]]`.
///
/// Apps without a SettingsManager (or without `name` / `tagline` / `description` populated in
/// their settings) still appear in the deck — they get a barebones card
/// with the humanized context-root slug as label.
///
/// This is the only REST endpoint the portal exposes for the browser. All
/// internal reads are via JMX, which propagates the authenticated user's
/// identity implicitly. See memory `[[internal-communication-jmx]]`.
@Path("/v1/cards")
public class PortalCardsResource {

	private static final Logger log = Logger.getLogger(PortalCardsResource.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final int STATE_ACTIVE = 2; // ApplicationRuntimeMBean.ActiveVersionState ACTIVE
	private static final String CTX_ROOT_PREFIX = "blade/";
	private static final String SELF_CONTEXT_ROOT = "blade/portal";

	/// Apps whose SettingsMXBean Name (their web.xml display-name) deliberately
	/// differs from their context-root slug — so the join below looks up the
	/// right MBean. The Analytics Console serves at /blade/analytics but
	/// registers Name=analytics-console, so its MBean does not collide with the
	/// analytics CLUSTER SERVICE (which also registers Name=analytics).
	/// Maps context-root last-segment → SettingsMXBean Name.
	private static final Map<String, String> SETTINGS_NAME_BY_SLUG = new HashMap<>();
	static {
		SETTINGS_NAME_BY_SLUG.put("analytics", "analytics-console");
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response list() {
		try {
			List<Card> cards = discover();
			ObjectNode out = mapper.createObjectNode();
			ArrayNode arr = out.putArray("cards");
			for (Card c : cards) {
				ObjectNode n = arr.addObject();
				n.put("contextRoot", c.contextRoot);
				n.put("name", c.name);
				if (c.tagline != null) n.put("tagline", c.tagline);
				if (c.description != null) n.put("description", c.description);
				n.put("hasMetadata", c.hasMetadata);
			}
			return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			log.log(Level.WARNING, "PortalCardsResource: JMX walk failed", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.type(MediaType.TEXT_PLAIN)
					.entity("cards lookup failed: " + e.getClass().getSimpleName()
							+ ": " + (e.getMessage() != null ? e.getMessage() : ""))
					.build();
		}
	}

	private List<Card> discover() throws Exception {
		List<Card> cards = new ArrayList<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			List<String> contextRoots = walkAdminContextRoots(mbs);
			Map<String, ObjectName> settingsMBeans = walkSettingsMBeans(mbs);

			for (String ctxRoot : contextRoots) {
				if (SELF_CONTEXT_ROOT.equals(ctxRoot)) {
					continue;
				}
				String slug = lastSegment(ctxRoot);
				String settingsKey = SETTINGS_NAME_BY_SLUG.getOrDefault(slug, slug);
				cards.add(buildCard(mbs, settingsKey, ctxRoot, settingsMBeans.get(settingsKey)));
			}
		}
		cards.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return cards;
	}

	private List<String> walkAdminContextRoots(MBeanServer mbs) throws Exception {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		ObjectName service = new ObjectName(
				"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
		ObjectName[] servers = (ObjectName[]) mbs.getAttribute(service, "ServerRuntimes");
		if (servers == null) return new ArrayList<>(out);
		for (ObjectName server : servers) {
			ObjectName[] apps = arrAttr(mbs, server, "ApplicationRuntimes");
			if (apps == null) continue;
			for (ObjectName app : apps) {
				if (!isActive(mbs, app)) continue;
				ObjectName[] components = arrAttr(mbs, app, "ComponentRuntimes");
				if (components == null) continue;
				for (ObjectName component : components) {
					String contextRoot = strAttr(mbs, component, "ContextRoot");
					if (contextRoot == null) continue;
					// WLS quirk: ContextRoot comes back with a leading slash even though
					// weblogic.xml declares it without one. Normalize.
					String normalized = contextRoot.startsWith("/")
							? contextRoot.substring(1)
							: contextRoot;
					if (normalized.startsWith(CTX_ROOT_PREFIX)) {
						out.add(normalized);
					}
				}
			}
		}
		return new ArrayList<>(out);
	}

	private Map<String, ObjectName> walkSettingsMBeans(MBeanServer mbs) throws Exception {
		Map<String, ObjectName> out = new HashMap<>();
		ObjectName pattern = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
		for (ObjectInstance inst : mbs.queryMBeans(pattern, null)) {
			ObjectName on = inst.getObjectName();
			String name = on.getKeyProperty("Name");
			if (name == null) continue;
			ObjectName existing = out.get(name);
			// A Name can be registered twice across the domain: by an admin app
			// (on AdminServer — domain-scoped, NO Cluster key) and by a
			// like-named SIP SERVICE on the engine cluster (WITH a Cluster key).
			// "analytics" is both an admin tool and a service. The deck is the
			// admin tier, so prefer the AdminServer-local MBean; otherwise the
			// service's metadata (or lack of it) leaks onto the admin card.
			if (existing == null || (hasCluster(existing) && !hasCluster(on))) {
				out.put(name, on);
			}
		}
		return out;
	}

	private static boolean hasCluster(ObjectName on) {
		return on.getKeyProperty("Cluster") != null;
	}

	private Card buildCard(MBeanServer mbs, String appName, String ctxRoot, ObjectName settingsMBean) {
		String name = humanize(lastSegment(ctxRoot));
		String tagline = null;
		String description = null;
		boolean hasMetadata = false;

		if (settingsMBean != null) {
			try {
				// MXBean exposes no-arg getter `getCurrentJson()` as ATTRIBUTE
				// `CurrentJson`, NOT as an operation — standard MXBean rule. Use
				// getAttribute, not invoke. SettingsManager registers Settings
				// via an explicit StandardMBean wrapper so this attribute is
				// guaranteed to be exposed. See memory
				// `[[settingsmxbean-introspection-bug]]`.
				String json = (String) mbs.getAttribute(settingsMBean, "CurrentJson");
				if (json != null) {
					JsonNode root = mapper.readTree(json);
					// Metadata lives under the `about` object on the Configuration.
					JsonNode about = root.get("about");
					if (about != null && !about.isNull()) {
						String n = textOrNull(about, "name");
						String t = textOrNull(about, "tagline");
						String d = textOrNull(about, "description");
						if (n != null) { name = n; hasMetadata = true; }
						if (t != null) { tagline = t; hasMetadata = true; }
						if (d != null) { description = d; hasMetadata = true; }
					}
				}
			} catch (Exception e) {
				log.log(Level.WARNING, "metadata read failed for " + appName
						+ " (using fallback): " + e.getClass().getSimpleName()
						+ ": " + e.getMessage(), e);
			}
		}
		return new Card(ctxRoot, name, tagline, description, hasMetadata);
	}

	private static String lastSegment(String contextRoot) {
		int slash = contextRoot.lastIndexOf('/');
		return slash >= 0 ? contextRoot.substring(slash + 1) : contextRoot;
	}

	/// Humanizes a slug for display: "crud-editor" → "Crud Editor".
	private static String humanize(String slug) {
		if (slug == null || slug.isEmpty()) return slug;
		StringBuilder out = new StringBuilder();
		boolean capNext = true;
		for (int i = 0; i < slug.length(); i++) {
			char c = slug.charAt(i);
			if (c == '-' || c == '_') {
				out.append(' ');
				capNext = true;
			} else if (capNext) {
				out.append(Character.toUpperCase(c));
				capNext = false;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String textOrNull(JsonNode parent, String field) {
		JsonNode n = parent.get(field);
		if (n == null || n.isNull()) return null;
		String s = n.asText();
		return (s == null || s.isEmpty()) ? null : s;
	}

	private static boolean isActive(MBeanServer mbs, ObjectName app) {
		try {
			Object v = mbs.getAttribute(app, "ActiveVersionState");
			return v == null || ((Number) v).intValue() == STATE_ACTIVE;
		} catch (Exception e) {
			return true;
		}
	}

	private static ObjectName[] arrAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			return (ObjectName[]) mbs.getAttribute(on, name);
		} catch (Exception e) {
			return null;
		}
	}

	private static String strAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static final class Card {
		final String contextRoot;
		final String name;
		final String tagline;
		final String description;
		final boolean hasMetadata;

		Card(String contextRoot, String name, String tagline, String description, boolean hasMetadata) {
			this.contextRoot = contextRoot;
			this.name = name;
			this.tagline = tagline;
			this.description = description;
			this.hasMetadata = hasMetadata;
		}
	}
}
