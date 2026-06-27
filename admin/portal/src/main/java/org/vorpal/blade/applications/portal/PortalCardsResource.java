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

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Aggregates the portal launcher deck — two tiers of cards.
///
/// **Apps tier** (`kind:"app"`) — launchable admin tools. Composed by:
///
/// 1. **`WebApplicationRuntimeMBean` walk** — every deployed admin app
///    (context-root starts with `blade/`, `ApplicationRuntime.ActiveVersionState == 2`).
///    Every match becomes a card. The portal filters itself out.
/// 2. **`SettingsMXBean` walk** — query `vorpal.blade:Name=*,Type=Configuration,*`
///    and invoke `getCurrentJson()` on each match to read the top-level `name`,
///    metadata. Same federated DomainRuntime path Configurator uses.
///
/// **Services tier** (`kind:"service"`) — SIP services. These have no `blade/*`
/// webapp to walk, so the apps tier never sees them; instead `walkServiceSettings`
/// keeps the Cluster-keyed Configuration MXBeans the apps tier discards and renders
/// each as a documentation card whose action opens the Configurator (`configuratorDomain`).
/// Same `@SchemaAbout` identity drives the text.
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
				n.put("kind", c.kind);
				if (c.configuratorDomain != null) n.put("configuratorDomain", c.configuratorDomain);
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
		List<Card> appCards = new ArrayList<>();
		List<Card> serviceCards = new ArrayList<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			List<String> contextRoots = walkAdminContextRoots(mbs);
			Map<String, ObjectName> settingsMBeans = walkSettingsMBeans(mbs);

			// Pass 1 — admin apps. One card per deployed `blade/*` webapp,
			// joined to its (AdminServer-local) SettingsMXBean by flatten().
			for (String ctxRoot : contextRoots) {
				if (SELF_CONTEXT_ROOT.equals(ctxRoot)) {
					continue;
				}
				// SettingsManager names every app's MBean after its flattened
				// context path ("blade/crud" → "blade-crud"), so the join is the
				// same flatten — no display-name conventions, no special cases.
				String settingsKey = SettingsManager.flatten(ctxRoot);
				appCards.add(buildCard(mbs, settingsKey, ctxRoot,
						settingsMBeans.get(settingsKey), "app", null));
			}

			// Pass 2 — SIP services. These have NO `blade/*` webapp to walk
			// (flat context-roots, no browser GUI), so pass 1 never sees them.
			// But each registers a Configuration MXBean on the engine CLUSTER
			// (with a `Cluster` key — the discriminator pass 1 uses to keep
			// services OFF the admin deck). We collect exactly those here and
			// turn each into a documentation card whose action opens the
			// Configurator for that service. The card text comes from the same
			// @SchemaAbout schema identity admin cards read.
			for (Map.Entry<String, ObjectName> e : walkServiceSettings(mbs).entrySet()) {
				String name = e.getKey();
				Card card = buildCard(mbs, name, name, e.getValue(), "service", name);
				// A service joins the deck only once a developer has authored
				// its identity (@SchemaAbout). Unlike admin apps — which are a
				// curated, deployed-on-purpose set — the cluster carries config
				// MBeans we don't want on a customer-facing deck: the test tier
				// (test-uac/uas/b2bua) and assorted internals. Requiring authored
				// identity is the curation gate. A new service shows up the moment
				// it's given @SchemaAbout.
				if (card.hasMetadata) {
					serviceCards.add(card);
				}
			}
		}
		appCards.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		serviceCards.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

		List<Card> cards = new ArrayList<>(appCards.size() + serviceCards.size());
		cards.addAll(appCards);
		cards.addAll(serviceCards);
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

	/// Collects the SIP-service Configuration MXBeans — the Cluster-keyed ones
	/// `walkSettingsMBeans` deliberately discards so the service tier doesn't
	/// leak onto the admin deck. This is the inverse selection: keep ONLY the
	/// Cluster-keyed registrations.
	///
	/// A service registers once per engine node, so the same `Name` comes back
	/// several times (one ObjectName per server). `putIfAbsent` collapses them
	/// to a single card — they share one schema, so any node's MBean answers.
	private Map<String, ObjectName> walkServiceSettings(MBeanServer mbs) throws Exception {
		Map<String, ObjectName> out = new HashMap<>();
		ObjectName pattern = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
		for (ObjectInstance inst : mbs.queryMBeans(pattern, null)) {
			ObjectName on = inst.getObjectName();
			String name = on.getKeyProperty("Name");
			if (name == null || !hasCluster(on)) continue;
			out.putIfAbsent(name, on);
		}
		return out;
	}

	private Card buildCard(MBeanServer mbs, String appName, String ctxRoot, ObjectName settingsMBean,
			String kind, String configuratorDomain) {
		String name = humanize(lastSegment(ctxRoot));
		String tagline = null;
		String description = null;
		boolean hasMetadata = false;

		if (settingsMBean != null) {
			// Preferred: developer-owned identity from the generated schema root
			// (`title` / `x-tagline` / `description`, emitted from @SchemaAbout) —
			// NOT the operator's config data, which a config save would blank.
			// MXBean no-arg getters surface as ATTRIBUTEs (`SchemaJson`), not
			// operations — standard MXBean rule, so getAttribute, not invoke.
			//
			// `SchemaJson` is a newer attribute: an app still bound to an older
			// framework shared library (mid-rolling-deploy, or simply not yet
			// re-pushed) won't expose it. readAttr returns null instead of
			// throwing so that case falls through to the legacy `about` read
			// rather than blanking the card down to its humanized slug — which
			// is exactly the regression a single shared try/catch caused.
			String schemaJson = readAttr(mbs, settingsMBean, "SchemaJson", appName);
			if (schemaJson != null) {
				try {
					JsonNode root = mapper.readTree(schemaJson);
					String n = textOrNull(root, "title");
					String t = textOrNull(root, "x-tagline");
					String d = textOrNull(root, "description");
					if (n != null) { name = n; hasMetadata = true; }
					if (t != null) { tagline = t; hasMetadata = true; }
					if (d != null) { description = d; hasMetadata = true; }
				} catch (Exception e) {
					log.log(Level.WARNING, "schema parse failed for " + appName
							+ ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}

			// Transitional fallback: legacy `about` block in CurrentJson, for
			// apps not on @SchemaAbout / the new framework yet. On those, About
			// still carries name/tagline/description, so this restores the card.
			// Delete once every app exposes schema identity.
			if (!hasMetadata) {
				String json = readAttr(mbs, settingsMBean, "CurrentJson", appName);
				if (json != null) {
					try {
						JsonNode about = mapper.readTree(json).get("about");
						if (about != null && !about.isNull()) {
							String n = textOrNull(about, "name");
							String t = textOrNull(about, "tagline");
							String d = textOrNull(about, "description");
							if (n != null) { name = n; hasMetadata = true; }
							if (t != null) { tagline = t; hasMetadata = true; }
							if (d != null) { description = d; hasMetadata = true; }
						}
					} catch (Exception e) {
						log.log(Level.WARNING, "about parse failed for " + appName
								+ ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
					}
				}
			}
		}
		return new Card(ctxRoot, name, tagline, description, hasMetadata, kind, configuratorDomain);
	}

	/// Reads a String MBean attribute, returning null instead of throwing when
	/// it can't be read — most importantly when the attribute is absent because
	/// the target app is bound to an older framework shared library that predates
	/// it (e.g. `SchemaJson`). A missing attribute on one app must never abort
	/// that app's card or the surrounding read; the caller falls back.
	private String readAttr(MBeanServer mbs, ObjectName on, String attr, String appName) {
		try {
			return (String) mbs.getAttribute(on, attr);
		} catch (Exception e) {
			log.log(Level.FINE, "attribute " + attr + " unavailable for " + appName
					+ ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
			return null;
		}
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
		/// "app" — a launchable admin tool (navigate to its context-root).
		/// "service" — a SIP service (no GUI; the card opens the Configurator).
		final String kind;
		/// For service cards: the Configurator `domain` (the service's flattened
		/// MBean Name). Null for admin apps.
		final String configuratorDomain;

		Card(String contextRoot, String name, String tagline, String description, boolean hasMetadata,
				String kind, String configuratorDomain) {
			this.contextRoot = contextRoot;
			this.name = name;
			this.tagline = tagline;
			this.description = description;
			this.hasMetadata = hasMetadata;
			this.kind = kind;
			this.configuratorDomain = configuratorDomain;
		}
	}
}
