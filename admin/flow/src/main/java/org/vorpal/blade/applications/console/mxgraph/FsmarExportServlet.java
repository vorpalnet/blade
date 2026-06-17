package org.vorpal.blade.applications.console.mxgraph;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Exports the current mxGraph editor model as FSMAR 3 JSON.
///
/// Reads an `xml` request parameter containing the mxGraph XML, walks the
/// graph cells (State/Ingress/Egress vertices, Transition edges), and builds
/// the real FSMAR 3 structure: root{defaultApplication, states} →
/// State{selectors, triggers} → Trigger{transitions} → Transition{id, when,
/// next, subscriber, region, routes, routeModifier}.
///
/// **Round-trip contract — never silently strip.** `extra` attributes
/// written by [FsmarImportServlet] (JSON blobs of fields the editor doesn't
/// explicitly model) are merged back at every level, and `triggerExtras`
/// re-attaches trigger-level unknowns. Transitions are emitted in `seq`
/// order — `Trigger.transitions` is first-match-wins, so order is meaning,
/// not cosmetics.
///
/// Diagrams containing the obsolete `selectorGroup` transition model (from
/// pre-rework saved XML files) are rejected with a named reason — the data
/// can't be mapped to FSMAR 3; re-import the original JSON instead.
@WebServlet("/fsmarExport")
public class FsmarExportServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper()
			.setSerializationInclusion(Include.NON_NULL)
			.enable(SerializationFeature.INDENT_OUTPUT);

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// Decode the POSTed mxGraph XML as UTF-8; without this getParameter() falls
		// back to ISO-8859-1 and corrupts non-ASCII config text on the way back out.
		request.setCharacterEncoding("UTF-8");

		String xml = request.getParameter("xml");
		if (xml == null || xml.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing xml parameter");
			return;
		}

		try {
			ObjectNode root = buildFsmarJson(xml);

			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
			out.flush();
		} catch (IllegalArgumentException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		} catch (Exception e) {
			throw new ServletException("FSMAR export failed: " + e.getMessage(), e);
		}
	}

	/// Parses the mxGraph XML and builds the FSMAR 3 JSON tree.
	ObjectNode buildFsmarJson(String mxGraphXml) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document doc = builder.parse(new ByteArrayInputStream(mxGraphXml.getBytes(StandardCharsets.UTF_8)));

		// Obsolete model guard: pre-rework diagrams stored conditions as
		// <selectorGroup> children on transitions. That shape never matched
		// FSMAR 3 and cannot be exported faithfully.
		if (doc.getElementsByTagName("selectorGroup").getLength() > 0) {
			throw new IllegalArgumentException("This diagram uses the obsolete transition-level "
					+ "selector-group model. It cannot be exported as FSMAR 3 JSON — re-import "
					+ "your FSMAR configuration JSON to rebuild the diagram, then re-apply edits.");
		}

		// Map cell ID -> wrapper element (State or Transition or FlowModel)
		Map<String, Element> wrappersById = new HashMap<>();
		// Map cell ID -> state name (for resolving edge source/target). An
		// ingress box with a source-match is its OWN state (named by its
		// label); the default ingress (a matchless box, or legacy
		// Ingress/Egress) maps to "null".
		Map<String, String> stateNamesById = new HashMap<>();
		// State name -> wrapper, for seeding state entries (selectors + extras)
		// even when no transitions touch them. Includes plain States AND
		// matched ingress boxes — each ingress is a real state with its own
		// selectors.
		Map<String, Element> realStates = new java.util.LinkedHashMap<>();
		// Matchless ingress box(es) — the default ingress, i.e. the "null"
		// state. Normally one; selectors concatenate if several.
		List<Element> defaultIngressEls = new ArrayList<>();
		// Named ingress state -> its source-match expression (a `when`).
		// Drives the generated dispatch transitions on "null".
		Map<String, String> ingressMatch = new java.util.LinkedHashMap<>();

		// Egress exit nodes — the mirror of ingresses. An egress is NOT a state;
		// it's the target of a terminal transition. The edge pass bakes the
		// egress's routes onto that transition, with the modifier DERIVED from
		// the egress's out-edge topology: an edge back to a state Y emits
		// next=Y + ROUTE_BACK, no out-edge emits next=null + ROUTE_FINAL (the
		// engine reads them). Recorded by cell id (to resolve edges) and by name
		// (its {description, routes, returnState}, for diagram.egresses).
		Map<String, String> egressNameByCellId = new HashMap<>();
		Map<String, ObjectNode> egressDefByName = new java.util.LinkedHashMap<>();
		// Egress name -> return-state id, for an egress with an out-edge back to a
		// state (the route-back line). Drives ROUTE_BACK: the terminal transition's
		// `next` becomes this state, and the call resumes there after the external
		// round-trip. Empty = ROUTE_FINAL (the call leaves OCCAS for good).
		Map<String, String> egressReturnByName = new HashMap<>();

		String defaultApplication = null;
		String rootExtra = null;

		// Vertex positions for the diagram `states` section: state name -> {x, y}.
		// Includes "null" (the default ingress box) and every ingress state.
		Map<String, int[]> placements = new java.util.LinkedHashMap<>();

		// First pass: walk all elements with mxCell children to find wrappers
		NodeList allCells = doc.getElementsByTagName("mxCell");
		for (int i = 0; i < allCells.getLength(); i++) {
			Element mxCell = (Element) allCells.item(i);
			Node parent = mxCell.getParentNode();
			if (parent == null || parent.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element wrapper = (Element) parent;
			String cellId = mxCell.getAttribute("id");
			// mxCell may not have its own id when nested inside a wrapper —
			// the id is on the wrapper element instead
			if (cellId == null || cellId.isEmpty()) {
				cellId = wrapper.getAttribute("id");
			}
			if (cellId == null || cellId.isEmpty()) {
				continue;
			}

			String tag = wrapper.getTagName();
			wrappersById.put(cellId, wrapper);

			switch (tag) {
				case "State":
					// The states-map KEY is the state id (unique); the `label` is
					// the application it invokes (may repeat across states). When a
					// node carries no explicit stateId, the two coincide.
					String stateId = stateIdOf(wrapper);
					stateNamesById.put(cellId, stateId);
					if (stateId != null && !stateId.isEmpty()) {
						if (realStates.containsKey(stateId)) {
							throw new IllegalArgumentException("Two states share the id '" + stateId
									+ "'. Give one a distinct State ID (the application name may repeat, "
									+ "but each state's id must be unique).");
						}
						realStates.put(stateId, wrapper);
						addPlacement(placements, stateId, mxCell);
					}
					break;
				case "Gateway":
				case "Ingress":   // legacy saved XML, pre-unification
				case "Egress":    // legacy saved XML, pre-unification
					String gwRole = wrapper.getAttribute("role");
					String gwLabel = wrapper.getAttribute("label");
					if ("egress".equals(gwRole)) {
						// An EGRESS exit node: not a state. It is the target of a
						// terminal transition; the edge pass bakes this node's routes
						// onto that transition. The route-modifier is INFERRED from
						// topology, not stored: no out-edge → ROUTE_FINAL; an out-edge
						// back to a state → ROUTE_BACK (resume there on return).
						if (gwLabel == null || gwLabel.isEmpty()) {
							throw new IllegalArgumentException("An egress node has no name. "
									+ "Give it a label and export again.");
						}
						egressNameByCellId.put(cellId, gwLabel);
						ObjectNode egDef = mapper.createObjectNode();
						ArrayNode egRoutes = buildRoutes(wrapper);
						if (egRoutes != null) {
							egDef.set("routes", egRoutes);
						}
						egressDefByName.put(gwLabel, egDef);
						addPlacement(placements, gwLabel, mxCell);
						break;
					}
					// An ingress box WITH a source-match is its own entry state
					// (keyed by its state id, default = label); a matchless box is
					// the default ingress = the "null" state. Legacy Ingress/Egress
					// have no match attribute, so they fall through as default.
					String gwMatch = wrapper.getAttribute("match");
					if (gwMatch != null && !gwMatch.isEmpty()
							&& gwLabel != null && !gwLabel.isEmpty()) {
						String ingressId = stateIdOf(wrapper);
						stateNamesById.put(cellId, ingressId);
						if (realStates.containsKey(ingressId)) {
							throw new IllegalArgumentException("Two states share the id '" + ingressId
									+ "'. Give one a distinct State ID.");
						}
						realStates.put(ingressId, wrapper);
						ingressMatch.put(ingressId, gwMatch);
						addPlacement(placements, ingressId, mxCell);
					} else {
						stateNamesById.put(cellId, "null");
						defaultIngressEls.add(wrapper);
						addPlacement(placements, "null", mxCell);
					}
					break;
				case "FlowModel":
					String def = wrapper.getAttribute("defaultApplication");
					if (def != null && !def.isEmpty()) {
						defaultApplication = def;
					}
					String ex = wrapper.getAttribute("extra");
					if (ex != null && !ex.isEmpty()) {
						rootExtra = ex;
					}
					break;
				default:
					// other element types are ignored
					break;
			}
		}

		// FlowModel may also appear in <root> directly (without an mxCell child)
		NodeList flowModels = doc.getElementsByTagName("FlowModel");
		for (int i = 0; i < flowModels.getLength(); i++) {
			Element fm = (Element) flowModels.item(i);
			String def = fm.getAttribute("defaultApplication");
			if (def != null && !def.isEmpty()) {
				defaultApplication = def;
			}
			String ex = fm.getAttribute("extra");
			if (ex != null && !ex.isEmpty()) {
				rootExtra = ex;
			}
		}

		// Build the FSMAR JSON structure. Root extras (about, logging,
		// session, …) lead so the output diffs cleanly against the original.
		ObjectNode rootNode = mapper.createObjectNode();
		mergeExtra(rootNode, rootExtra);
		if (defaultApplication != null) {
			rootNode.put("defaultApplication", defaultApplication);
		}

		ObjectNode statesNode = rootNode.putObject("states");

		// Seed every real state entry — plain States AND matched ingress
		// states (each ingress carries its own selectors): selectors first
		// (schema order), then triggers, then state-level extras.
		for (Map.Entry<String, Element> entry : realStates.entrySet()) {
			seedState(statesNode, entry.getKey(), entry.getValue());
		}
		// The default ingress box(es) seed the "null" state — its shared
		// selectors (run for all traffic) and extras.
		if (!defaultIngressEls.isEmpty()) {
			seedDefaultIngress(statesNode, defaultIngressEls);
		}

		// Pre-scan for route-back lines: a Transition whose SOURCE is an egress
		// node is not a routing transition — it's the line back to a state that
		// makes the egress a ROUTE_BACK exit. Record each egress's return state so
		// the terminal-into-egress baking below knows to emit next=<return> +
		// ROUTE_BACK. These edges are skipped in the main pass.
		NodeList preScan = doc.getElementsByTagName("Transition");
		for (int i = 0; i < preScan.getLength(); i++) {
			Element tx = (Element) preScan.item(i);
			Element mxCell = firstChildElement(tx, "mxCell");
			if (mxCell == null) {
				continue;
			}
			String sourceId = mxCell.getAttribute("source");
			if (sourceId == null || !egressNameByCellId.containsKey(sourceId)) {
				continue;
			}
			String retState = stateNamesById.get(mxCell.getAttribute("target"));
			if (retState == null) {
				throw new IllegalArgumentException("The route-back line from egress '"
						+ egressNameByCellId.get(sourceId) + "' must end on a State or ingress. "
						+ "Reconnect it (or delete it) and export again.");
			}
			egressReturnByName.put(egressNameByCellId.get(sourceId), retState);
		}

		// Second pass: collect Transition edges grouped by (source state,
		// method), keeping each one's seq for first-match ordering. Edges
		// connect to states directly now — an ingress box IS a state — so
		// there are no gateway attachment maps.
		class TxEntry {
			int seq;
			ObjectNode node;
		}
		Map<String, Map<String, List<TxEntry>>> collected = new java.util.LinkedHashMap<>();

		NodeList transitions = doc.getElementsByTagName("Transition");
		for (int i = 0; i < transitions.getLength(); i++) {
			Element tx = (Element) transitions.item(i);
			Element mxCell = firstChildElement(tx, "mxCell");
			if (mxCell == null) {
				continue;
			}
			String sourceId = mxCell.getAttribute("source");
			String targetId = mxCell.getAttribute("target");
			if (sourceId == null || sourceId.isEmpty() || targetId == null || targetId.isEmpty()) {
				// A dangling edge (no source or target) is unroutable — refuse
				// rather than silently drop the admin's work.
				throw new IllegalArgumentException("A transition edge labeled '"
						+ tx.getAttribute("label") + "' is not connected at both ends. "
						+ "Connect it to a source and target state (or delete it) and export again.");
			}

			// Route-back lines (source = an egress) were absorbed in the pre-scan
			// — they're topology, not transitions. Skip them here.
			if (egressNameByCellId.containsKey(sourceId)) {
				continue;
			}

			String sourceState = stateNamesById.get(sourceId);
			String egressName = egressNameByCellId.get(targetId);
			String targetState = (egressName != null) ? null : stateNamesById.get(targetId);
			if (sourceState == null || (egressName == null && targetState == null)) {
				throw new IllegalArgumentException("A transition edge labeled '"
						+ tx.getAttribute("label") + "' connects to a cell that is not a "
						+ "State/ingress/egress. Reconnect it and export again.");
			}

			String method = tx.getAttribute("label");
			if (method == null || method.isEmpty()) {
				method = "INVITE";
			}

			// Build the transition object in fsmar3 @JsonPropertyOrder:
			// id, when, next, subscriber, region, routes, routeModifier.
			ObjectNode txNode = mapper.createObjectNode();
			addIfPresent(txNode, "id", tx.getAttribute("txId"));
			addIfPresent(txNode, "when", tx.getAttribute("when"));

			if (egressName != null) {
				// Transition into an egress. The route-modifier is inferred from
				// the egress's topology: an out-edge back to a state Y → ROUTE_BACK
				// with next=Y (resume there after the external round-trip); no
				// out-edge → ROUTE_FINAL with no next (the call leaves OCCAS). The
				// egress node's routes are baked on either way — the engine reads
				// them to build the routing decision.
				ObjectNode egDef = egressDefByName.get(egressName);
				// An egress must carry at least one route — it's the only thing
				// that makes the transition route anywhere. Without it the call
				// would just fall downstream and the config wouldn't re-import.
				if (egDef == null || !egDef.has("routes") || egDef.get("routes").size() == 0) {
					throw new IllegalArgumentException("Egress '" + egressName
							+ "' has no route URIs. Add at least one route on the egress node "
							+ "(or delete it) and export again.");
				}
				String returnState = egressReturnByName.get(egressName);
				if (returnState != null) {
					txNode.put("next", returnState);
				}
				addIfPresent(txNode, "subscriber", tx.getAttribute("subscriber"));
				addIfPresent(txNode, "region", tx.getAttribute("region"));
				txNode.set("routes", egDef.get("routes").deepCopy());
				txNode.put("routeModifier", (returnState != null) ? "ROUTE_BACK" : "ROUTE_FINAL");
				mergeExtra(txNode, tx.getAttribute("extra"));
			} else {
				txNode.put("next", targetState);
				addIfPresent(txNode, "subscriber", tx.getAttribute("subscriber"));
				addIfPresent(txNode, "region", tx.getAttribute("region"));

				NodeList routes = tx.getElementsByTagName("route");
				if (routes.getLength() > 0) {
					ArrayNode rArr = txNode.putArray("routes");
					for (int r = 0; r < routes.getLength(); r++) {
						Element route = (Element) routes.item(r);
						String uri = route.getAttribute("uri");
						if (uri != null && !uri.isEmpty()) {
							rArr.add(uri);
						}
					}
				}
				addIfPresent(txNode, "routeModifier", tx.getAttribute("routeModifier"));
				mergeExtra(txNode, tx.getAttribute("extra"));
			}

			TxEntry e = new TxEntry();
			e.seq = parseSeq(tx.getAttribute("seq"));
			e.node = txNode;
			collected.computeIfAbsent(sourceState, k -> new java.util.LinkedHashMap<>())
					.computeIfAbsent(method, k -> new ArrayList<>())
					.add(e);
		}

		// Generated dispatch transitions for the "null" state: one per
		// (ingress, method) classifying source traffic into the ingress entry
		// state. They run BEFORE null's own routing (source-classified traffic
		// is always handled by its ingress), so first-match-wins picks them
		// first. Method set per ingress = the methods its state handles
		// (fallback INVITE if it has none yet).
		Map<String, List<ObjectNode>> dispatchByMethod = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String> ing : ingressMatch.entrySet()) {
			String name = ing.getKey();
			String match = ing.getValue();
			Map<String, List<TxEntry>> ingTriggers = collected.get(name);
			java.util.Set<String> methods = (ingTriggers != null && !ingTriggers.isEmpty())
					? ingTriggers.keySet()
					: java.util.Collections.singleton("INVITE");
			for (String method : methods) {
				ObjectNode d = mapper.createObjectNode();
				d.put("id", "dispatch-" + name);
				d.put("when", match);
				d.put("next", name);
				dispatchByMethod.computeIfAbsent(method, k -> new ArrayList<>()).add(d);
			}
		}

		// Emit collected transitions sorted by seq within each trigger —
		// first-match-wins order is semantic. New edges (no seq) sort last.
		for (Map.Entry<String, Map<String, List<TxEntry>>> stateEntry : collected.entrySet()) {
			String stateName = stateEntry.getKey();
			ObjectNode stateNode = (ObjectNode) statesNode.get(stateName);
			if (stateNode == null) {
				stateNode = statesNode.putObject(stateName);
			}
			ObjectNode triggersNode = (ObjectNode) stateNode.get("triggers");
			if (triggersNode == null) {
				triggersNode = stateNode.putObject("triggers");
			}
			ObjectNode triggerExtras = "null".equals(stateName)
					? mergedDefaultIngressTriggerExtras(defaultIngressEls)
					: parseTriggerExtras(realStates.get(stateName));

			for (Map.Entry<String, List<TxEntry>> trigEntry : stateEntry.getValue().entrySet()) {
				String method = trigEntry.getKey();
				List<TxEntry> list = trigEntry.getValue();
				list.sort(Comparator.comparingInt(t -> t.seq));

				ObjectNode triggerNode = triggersNode.putObject(method);
				ArrayNode txList = triggerNode.putArray("transitions");
				// Dispatch transitions lead the null state's triggers.
				if ("null".equals(stateName) && dispatchByMethod.containsKey(method)) {
					for (ObjectNode d : dispatchByMethod.get(method)) {
						txList.add(d);
					}
				}
				for (TxEntry e : list) {
					txList.add(e.node);
				}
				if (triggerExtras != null && triggerExtras.has(method)) {
					mergeInto(triggerNode, (ObjectNode) triggerExtras.get(method));
				}
			}
		}

		// Dispatch transitions for methods the null state has no routing of its
		// own for (e.g. an ingress handles REGISTER but the default doesn't):
		// ensure those triggers exist on null too.
		if (!dispatchByMethod.isEmpty()) {
			ObjectNode nullNode = (ObjectNode) statesNode.get("null");
			if (nullNode == null) {
				nullNode = statesNode.putObject("null");
				nullNode.putObject("triggers");
			}
			ObjectNode triggersNode = (ObjectNode) nullNode.get("triggers");
			if (triggersNode == null) {
				triggersNode = nullNode.putObject("triggers");
			}
			for (Map.Entry<String, List<ObjectNode>> d : dispatchByMethod.entrySet()) {
				if (!triggersNode.has(d.getKey())) {
					ArrayNode txList = triggersNode.putObject(d.getKey()).putArray("transitions");
					for (ObjectNode dn : d.getValue()) {
						txList.add(dn);
					}
				}
			}
		}

		// Diagram metadata: written explicitly (never via extras — "diagram"
		// is in FsmarImportServlet.ROOT_KNOWN), from current cell geometry and
		// ingress topology. Shape matches the fsmar3 Diagram class:
		// states (positions, incl "null") + ingresses (name -> {match}).
		if (!placements.isEmpty() || !ingressMatch.isEmpty() || !egressDefByName.isEmpty()) {
			ObjectNode diagramNode = rootNode.putObject("diagram");
			if (!placements.isEmpty()) {
				ObjectNode statesPos = diagramNode.putObject("states");
				for (Map.Entry<String, int[]> p : placements.entrySet()) {
					ObjectNode pos = statesPos.putObject(p.getKey());
					pos.put("x", p.getValue()[0]);
					pos.put("y", p.getValue()[1]);
				}
			}
			if (!ingressMatch.isEmpty()) {
				ObjectNode ing = diagramNode.putObject("ingresses");
				for (Map.Entry<String, String> e : ingressMatch.entrySet()) {
					ing.putObject(e.getKey()).put("match", e.getValue());
				}
			}
			// Egress exit nodes: name -> {description, routes, returnState?}.
			// Positions ride the shared `states` map above (keyed by egress
			// name). `returnState` present = a ROUTE_BACK exit (out-edge to that
			// state); absent = ROUTE_FINAL. Import recovers the node — including
			// its route-back line — from this and matches transitions by routes.
			if (!egressDefByName.isEmpty()) {
				ObjectNode eg = diagramNode.putObject("egresses");
				for (Map.Entry<String, ObjectNode> e : egressDefByName.entrySet()) {
					ObjectNode egNode = e.getValue();
					String returnState = egressReturnByName.get(e.getKey());
					if (returnState != null) {
						egNode.put("returnState", returnState);
					}
					eg.set(e.getKey(), egNode);
				}
			}
		}

		return rootNode;
	}

	/// Builds a `routes` array from `<route uri="…">` direct children of an
	/// egress node. Returns null when there are none.
	private ArrayNode buildRoutes(Element wrapper) {
		List<Element> routeEls = childElements(wrapper, "route");
		if (routeEls.isEmpty()) {
			return null;
		}
		ArrayNode arr = mapper.createArrayNode();
		for (Element r : routeEls) {
			String uri = r.getAttribute("uri");
			if (uri != null && !uri.isEmpty()) {
				arr.add(uri);
			}
		}
		return arr.size() > 0 ? arr : null;
	}

	/// Seeds the "null" state (the default ingress) from the matchless ingress
	/// box(es): shared selectors that run for all traffic, plus extras.
	/// Normally a single default box; if several, selectors concatenate.
	private void seedDefaultIngress(ObjectNode statesNode, List<Element> defaults) {
		if (statesNode.has("null")) {
			return;
		}
		ObjectNode stateNode = statesNode.putObject("null");
		ArrayNode allSels = mapper.createArrayNode();
		for (Element g : defaults) {
			ArrayNode sels = buildSelectors(g);
			if (sels != null) {
				allSels.addAll(sels);
			}
		}
		if (allSels.size() > 0) {
			stateNode.set("selectors", allSels);
		}
		stateNode.putObject("triggers");
		for (Element g : defaults) {
			mergeExtra(stateNode, g.getAttribute("extra"));
		}
	}

	/// Merges triggerExtras from the default ingress box(es), first-wins.
	private ObjectNode mergedDefaultIngressTriggerExtras(List<Element> gateways) {
		ObjectNode merged = null;
		for (Element g : gateways) {
			ObjectNode te = parseTriggerExtras(g);
			if (te == null) {
				continue;
			}
			if (merged == null) {
				merged = mapper.createObjectNode();
			}
			Iterator<Map.Entry<String, JsonNode>> it = te.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				if (!merged.has(e.getKey())) {
					merged.set(e.getKey(), e.getValue());
				}
			}
		}
		return merged;
	}

	/// Records a vertex's mxGeometry position. Coordinates may be fractional
	/// in mxGraph (drag snapping off) — rounded; the Placement model and the
	/// editor's grid are integer-pixel.
	private static void addPlacement(Map<String, int[]> placements, String key, Element mxCell) {
		Element geom = firstChildElement(mxCell, "mxGeometry");
		if (geom == null) {
			return;
		}
		try {
			// mxGeometry omits x/y attributes when 0 — empty means origin,
			// not "no position".
			placements.put(key, new int[] { parseCoord(geom.getAttribute("x")),
					parseCoord(geom.getAttribute("y")) });
		} catch (NumberFormatException e) {
			// Unparseable position — leave unplaced; auto-layout on import.
		}
	}

	private static int parseCoord(String value) {
		return (value == null || value.isEmpty()) ? 0 : (int) Math.round(Double.parseDouble(value));
	}

	/// The states-map key for a node: its explicit `stateId` attribute, else its
	/// label. Lets two State nodes share a label (the application name) while
	/// staying distinct states.
	private static String stateIdOf(Element wrapper) {
		String id = wrapper.getAttribute("stateId");
		return (id != null && !id.isEmpty()) ? id : wrapper.getAttribute("label");
	}

	/// Creates the JSON entry for one state from its wrapper element: `app`
	/// (only when it differs from the id), selectors (children), empty triggers
	/// (filled by the edge pass), and state-level extras.
	private void seedState(ObjectNode statesNode, String name, Element wrapper) {
		if (statesNode.has(name)) return;
		ObjectNode stateNode = statesNode.putObject(name);
		// `name` is the state id; the node's label is the application it invokes.
		// Emit `app` only when they differ — the common case has them equal.
		String app = wrapper.getAttribute("label");
		if (app != null && !app.isEmpty() && !app.equals(name)) {
			stateNode.put("app", app);
		}
		ArrayNode selectors = buildSelectors(wrapper);
		if (selectors != null) {
			stateNode.set("selectors", selectors);
		}
		stateNode.putObject("triggers");
		mergeExtra(stateNode, wrapper.getAttribute("extra"));
	}

	/// Rebuilds the selectors array from `<selector>` children. Known fields
	/// come from attributes; each selector's `extra` blob (table, namespaces,
	/// anything future) is merged back. Returns null when there are none.
	private ArrayNode buildSelectors(Element wrapper) {
		List<Element> sels = childElements(wrapper, "selector");
		if (sels.isEmpty()) return null;
		ArrayNode arr = mapper.createArrayNode();
		for (Element sel : sels) {
			ObjectNode selNode = mapper.createObjectNode();
			addIfPresent(selNode, "type", sel.getAttribute("type"));
			addIfPresent(selNode, "id", sel.getAttribute("id"));
			addIfPresent(selNode, "attribute", sel.getAttribute("attribute"));
			addIfPresent(selNode, "pattern", sel.getAttribute("pattern"));
			addIfPresent(selNode, "expression", sel.getAttribute("expression"));
			mergeExtra(selNode, sel.getAttribute("extra"));
			arr.add(selNode);
		}
		return arr;
	}

	private ObjectNode parseTriggerExtras(Element stateEl) {
		if (stateEl == null) return null;
		String raw = stateEl.getAttribute("triggerExtras");
		if (raw == null || raw.isEmpty()) return null;
		try {
			JsonNode parsed = mapper.readTree(raw);
			return parsed.isObject() ? (ObjectNode) parsed : null;
		} catch (IOException e) {
			throw new IllegalArgumentException("Malformed triggerExtras JSON on state '"
					+ stateEl.getAttribute("label") + "': " + e.getMessage());
		}
	}

	/// Merges an `extra` attribute (JSON object string) into `target`,
	/// skipping keys the editor already set — explicit edits win.
	private void mergeExtra(ObjectNode target, String extraJson) {
		if (extraJson == null || extraJson.isEmpty()) return;
		JsonNode parsed;
		try {
			parsed = mapper.readTree(extraJson);
		} catch (IOException e) {
			throw new IllegalArgumentException("Malformed extra JSON ('" + abbreviate(extraJson)
					+ "'): " + e.getMessage());
		}
		if (!parsed.isObject()) {
			throw new IllegalArgumentException("extra attribute must be a JSON object, got: "
					+ abbreviate(extraJson));
		}
		mergeInto(target, (ObjectNode) parsed);
	}

	private static void mergeInto(ObjectNode target, ObjectNode source) {
		Iterator<Map.Entry<String, JsonNode>> it = source.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			if (!target.has(e.getKey())) {
				target.set(e.getKey(), e.getValue());
			}
		}
	}

	private static String abbreviate(String s) {
		return s.length() > 60 ? s.substring(0, 60) + "…" : s;
	}

	private static int parseSeq(String seq) {
		try {
			return (seq == null || seq.isEmpty()) ? Integer.MAX_VALUE : Integer.parseInt(seq);
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	private static void addIfPresent(ObjectNode node, String key, String value) {
		if (value != null && !value.isEmpty()) {
			node.put(key, value);
		}
	}

	private static List<Element> childElements(Element parent, String tagName) {
		List<Element> out = new ArrayList<>();
		NodeList kids = parent.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			Node n = kids.item(i);
			if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
				out.add((Element) n);
			}
		}
		return out;
	}

	private static Element firstChildElement(Element parent, String tagName) {
		List<Element> found = childElements(parent, tagName);
		return found.isEmpty() ? null : found.get(0);
	}

}
