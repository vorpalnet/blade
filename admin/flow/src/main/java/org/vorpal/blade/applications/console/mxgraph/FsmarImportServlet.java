package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Imports FSMAR 3 JSON and produces an mxGraph XML model for the editor.
///
/// Reads a `json` request parameter, parses it against the real FSMAR 3
/// shape (AppRouterConfiguration → states → State{selectors, triggers} →
/// Trigger{transitions} → Transition{id, when, next, subscriber, region,
/// routes, routeModifier}), and generates an mxGraph XML document with
/// auto-positioned State vertices and Transition edges.
///
/// **Round-trip contract — never silently strip.** Every field this servlet
/// does not explicitly map is preserved verbatim in an `extra` attribute
/// (a JSON object string) on the corresponding XML element, and
/// [FsmarExportServlet] merges it back on export. Trigger-level unknown
/// fields ride a `triggerExtras` attribute on the State element (keyed by
/// SIP method). Every terminal transition (no `next`) maps to an egress
/// node: with routes it is a pushed exit (ROUTE_FINAL/ROUTE_BACK); with
/// none it is the downstream exit — application chaining stops and OCCAS
/// routes the request on its Request-URI (`AppRouter`'s no-target break).
///
/// **Transition order matters**: `Trigger.transitions` is evaluated
/// first-match-wins, but mxGraph edges have no inherent order, so each
/// Transition edge carries a `seq` attribute (its index in the trigger's
/// list) and export sorts by it.
@WebServlet("/fsmarImport")
public class FsmarImportServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	private static final int STATE_WIDTH = 120;
	private static final int STATE_HEIGHT = 48;
	private static final int COL_SPACING = 220;
	private static final int ROW_SPACING = 120;
	private static final int MARGIN_X = 60;
	private static final int MARGIN_Y = 60;
	private static final int COLS = 4;

	// Known (explicitly mapped) field names per level. Anything else is
	// carried in the element's `extra` attribute. Keep in sync with the
	// fsmar3 model classes AND with FsmarExportServlet.
	// "diagram" is explicitly mapped (vertex positions), never carried as a
	// root extra — FsmarExportServlet always re-emits it from live geometry.
	static final Set<String> ROOT_KNOWN = setOf("defaultApplication", "states", "diagram");
	static final Set<String> STATE_KNOWN = setOf("app", "selectors", "triggers");
	// For ingress boxes (and the default ingress = the "null" state): like
	// STATE_KNOWN but WITHOUT `app`. An ingress's label IS its state id, so
	// export cannot re-derive a hand-written `app` from the cell — it rides
	// the extra blob instead and merges back verbatim (no silent strip).
	static final Set<String> INGRESS_STATE_KNOWN = setOf("selectors", "triggers");
	static final Set<String> TRIGGER_KNOWN = setOf("transitions");
	static final Set<String> TRANSITION_KNOWN = setOf("id", "when", "next", "subscriber",
			"region", "routes", "routeModifier");
	// `allInstances` (AttributeSelector) and `namespaces` (XmlSelector) are
	// first-class v3 model fields with their own controls in the selector
	// editor — not unknowns. Keep this in sync with the @JsonSubTypes of
	// framework v3 configuration.selectors.Selector.
	static final Set<String> SELECTOR_KNOWN = setOf("id", "type",
			"attribute", "pattern", "expression", "allInstances", "namespaces");
	// Fields of an absorbed null→ingress dispatch transition that export
	// re-derives from the ingress itself. Everything else on that transition
	// (id, subscriber, region, routes, routeModifier, unknowns) is preserved
	// in the ingress cell's `dispatchExtra` and merged back on export —
	// otherwise a hand-written entry transition silently loses the region and
	// subscriber header that matter most on an initial request. The dispatch's
	// POSITION within its trigger is stored alongside as `seq` (first-match-
	// wins order is semantics), so export re-emits it exactly where it was.
	static final Set<String> DISPATCH_REGENERATED = setOf("when", "next");
	// Egress diagram fields handled explicitly: `routes` bake as <route> children,
	// `returnState` is topology (the route-back out-edge). Everything else — e.g.
	// the retired `description` (folded into Configuration.notes) — rides the
	// egress cell's `extra` so the round-trip never silently drops it.
	static final Set<String> EGRESS_KNOWN = setOf("routes", "returnState");

	static Set<String> setOf(String... names) {
		Set<String> s = new HashSet<>();
		for (String n : names) s.add(n);
		return s;
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// The browser POSTs the config UTF-8-encoded (encodeURIComponent). Without
		// this, getParameter() decodes the body as ISO-8859-1 (the servlet default)
		// and every non-ASCII character — em-dashes in descriptions, etc. — arrives
		// mojibaked.
		request.setCharacterEncoding("UTF-8");

		String json = request.getParameter("json");
		if (json == null || json.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing json parameter");
			return;
		}

		try {
			JsonNode fsmar = mapper.readTree(json);
			String xml = buildMxGraphXml(fsmar);

			response.setContentType("text/xml; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(xml);
			out.flush();
		} catch (IllegalArgumentException e) {
			// Config the editor can't represent faithfully — named reason,
			// nothing imported. Better than a lossy import.
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		} catch (Exception e) {
			throw new ServletException("FSMAR import failed: " + e.getMessage(), e);
		}
	}

	String buildMxGraphXml(JsonNode fsmar) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document doc = builder.newDocument();

		Element graphModel = doc.createElement("mxGraphModel");
		doc.appendChild(graphModel);

		Element root = doc.createElement("root");
		graphModel.appendChild(root);

		// FlowModel root cell (id=0). Root-level unknown fields (about,
		// logging, session, …) ride the extra attribute.
		Element flowModel = doc.createElement("FlowModel");
		flowModel.setAttribute("label", "FSMAR Flow");
		flowModel.setAttribute("id", "0");
		String defaultApp = fsmar.path("defaultApplication").asText("");
		flowModel.setAttribute("defaultApplication", defaultApp);
		setExtra(flowModel, fsmar, ROOT_KNOWN);
		root.appendChild(flowModel);

		// Default layer.
		//
		// CELL ID PLACEMENT — load-bearing for the browser decode: for a
		// wrapped cell (<Layer>/<State>/<Transition>… with an inner mxCell),
		// mxCellCodec.beforeDecode reads the id from the WRAPPER element. An
		// id on the inner mxCell is invisible to the codec, so parent/source/
		// target idrefs can't resolve against the registry and getElementById
		// falls back to decoding the inner mxCell as a detached DUPLICATE
		// cell — the diagram renders empty and re-export sees unconnected
		// edges. Ids on wrappers, idrefs (parent/source/target) on the inner
		// mxCell: exactly the shape mxCodec.encode itself produces.
		Element layer = doc.createElement("Layer");
		layer.setAttribute("label", "Default Layer");
		layer.setAttribute("id", "1");
		Element layerCell = doc.createElement("mxCell");
		layerCell.setAttribute("parent", "0");
		layer.appendChild(layerCell);
		root.appendChild(layer);

		JsonNode states = fsmar.path("states");
		JsonNode diagram = fsmar.path("diagram");
		JsonNode statePlacements = diagram.path("states");
		// Which states are ingress entry points (and their source-match). The
		// "null" state is the implicit default ingress and is not listed.
		JsonNode ingresses = diagram.path("ingresses");
		// Egress exit nodes (name -> {description, routes, returnState}). The
		// mirror of ingresses; a terminal transition (no `next`) connects to one.
		// returnState present = ROUTE_BACK (resume there); absent = ROUTE_FINAL.
		JsonNode egresses = diagram.path("egresses");

		// State name (incl "null") -> cell id. An ingress box IS a state, so
		// edges resolve to vertices directly — no gateway attachment maps.
		Map<String, String> stateCellIds = new HashMap<>();
		// Ingress state name -> its <Gateway> wrapper, so the transition pass
		// can hang absorbed dispatch fields on it.
		Map<String, Element> ingressEls = new HashMap<>();
		int nextId = 2;
		int ingressRow = 0;   // left-column stacking for ingress boxes

		// The default ingress = the "null" state, always present as the entry
		// point. Rendered as an ingress cloud labeled "default"; carries the
		// shared selectors that run for all traffic.
		String nullCellId = String.valueOf(nextId++);
		ingressEls.put("null", createIngressBox(doc, root, nullCellId, "null", "default", null,
				states.path("null"), statePlacements, MARGIN_X, MARGIN_Y + ingressRow++ * ROW_SPACING));
		stateCellIds.put("null", nullCellId);

		// Enumerate the named vertices: every non-null state, plus any `next`
		// target not present in states (an undeployed app) so its edge lands
		// somewhere. Value = is-ingress.
		LinkedHashMap<String, Boolean> named = new LinkedHashMap<>();
		if (states.isObject()) {
			Iterator<String> it = states.fieldNames();
			while (it.hasNext()) {
				String name = it.next();
				if (!"null".equals(name)) {
					named.put(name, ingresses.path(name).isObject());
				}
			}
			Iterator<Map.Entry<String, JsonNode>> sIt = states.fields();
			while (sIt.hasNext()) {
				JsonNode triggers = sIt.next().getValue().path("triggers");
				if (!triggers.isObject()) continue;
				Iterator<Map.Entry<String, JsonNode>> tIt = triggers.fields();
				while (tIt.hasNext()) {
					JsonNode txList = tIt.next().getValue().path("transitions");
					if (!txList.isArray()) continue;
					for (JsonNode tx : txList) {
						String next = tx.path("next").asText("");
						if (!next.isEmpty() && !"null".equals(next) && !named.containsKey(next)) {
							named.put(next, false);
						}
					}
				}
			}
		}

		// Create vertices: ingress boxes in the left column, plain States in a
		// grid offset to their right.
		int stateIdx = 0;
		int stateColOffset = MARGIN_X + COL_SPACING;
		for (Map.Entry<String, Boolean> entry : named.entrySet()) {
			String name = entry.getKey();
			String cellId = String.valueOf(nextId++);
			JsonNode stateJson = states.path(name);
			if (entry.getValue()) {
				ingressEls.put(name, createIngressBox(doc, root, cellId, name, name,
						ingresses.path(name).path("match").asText(""),
						stateJson, statePlacements, MARGIN_X, MARGIN_Y + ingressRow++ * ROW_SPACING));
			} else {
				int col = stateIdx % COLS;
				int row = stateIdx / COLS;
				createStateBox(doc, root, cellId, name, stateJson, statePlacements,
						stateColOffset + col * COL_SPACING, MARGIN_Y + row * ROW_SPACING);
				stateIdx++;
			}
			stateCellIds.put(name, cellId);
		}

		// Egress exit nodes (the mirror of ingresses). Create one box per
		// diagram.egresses entry up front so unconnected egresses survive a
		// round-trip; terminal transitions below connect to them (or synthesize
		// one) by matching (routes, routeModifier). Placed in a column to the
		// right of the state grid.
		Map<String, String> egressCellIdByKey = new HashMap<>();
		int egressRow = 0;
		final int egressCol = stateColOffset + COLS * COL_SPACING;
		if (egresses.isObject()) {
			Iterator<String> egIt = egresses.fieldNames();
			while (egIt.hasNext()) {
				String name = egIt.next();
				JsonNode egJson = egresses.path(name);
				// returnState present = a ROUTE_BACK exit (an out-edge back to that
				// state); absent = ROUTE_FINAL. The kind is topology, not a stored
				// modifier — it's the egress's out-edge.
				String returnState = egJson.path("returnState").asText("");
				String key = egressKey(egJson.path("routes"), returnState);
				String cellId = String.valueOf(nextId++);
				createEgressBox(doc, root, cellId, name, egJson.path("routes"), egJson,
						statePlacements, egressCol, MARGIN_Y + egressRow++ * ROW_SPACING);
				egressCellIdByKey.putIfAbsent(key, cellId);
				// Draw the route-back line from the egress back to its return state.
				if (!returnState.isEmpty() && stateCellIds.get(returnState) != null) {
					nextId = createRouteBackEdge(doc, root, cellId, stateCellIds.get(returnState), nextId);
				}
			}
		}

		// Create Transition edges. Generated source-dispatch transitions on
		// "null" (whose `next` is a listed ingress) are classification
		// plumbing the editor re-derives from each ingress's match — absorb
		// them, don't draw them as arrows.
		//
		// Ingresses whose match has been reconciled against an absorbed
		// dispatch's `when` (routing wins over presentation; first dispatch
		// per ingress decides).
		Set<String> matchAdopted = new HashSet<>();
		if (states.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> stateIt = states.fields();
			while (stateIt.hasNext()) {
				Map.Entry<String, JsonNode> stateEntry = stateIt.next();
				String sourceName = stateEntry.getKey();
				JsonNode triggers = stateEntry.getValue().path("triggers");
				if (!triggers.isObject()) continue;

				Iterator<Map.Entry<String, JsonNode>> trigIt = triggers.fields();
				while (trigIt.hasNext()) {
					Map.Entry<String, JsonNode> trigEntry = trigIt.next();
					String method = trigEntry.getKey();
					JsonNode txList = trigEntry.getValue().path("transitions");
					if (!txList.isArray()) continue;

					int seq = 0;
					for (JsonNode tx : txList) {
						String targetName = tx.path("next").asText("");
						boolean hasRoutes = tx.path("routes").isArray() && tx.path("routes").size() > 0;
						boolean routeBack = "ROUTE_BACK".equals(tx.path("routeModifier").asText(""));
						// An egress transition takes the call out of the application
						// chain: ROUTE_FINAL (no next, exits via its routes), ROUTE_BACK
						// (next = the resume state after an external round-trip), or the
						// downstream exit (no next, NO routes — nothing pushed, OCCAS
						// routes on the Request-URI; AppRouter's no-target break). For
						// ROUTE_BACK the `next` is the egress's return state, NOT a
						// normal edge target — it's drawn as the egress's out-edge.
						boolean finalEgress = targetName.isEmpty();
						boolean routeBackEgress = !targetName.isEmpty() && hasRoutes && routeBack;
						boolean egressTx = finalEgress || routeBackEgress;

						// Absorb generated dispatch transitions (null → ingress).
						// Export re-derives `when`/`next` from the ingress, so only
						// those two may be dropped; anything else the transition
						// carried is stashed on the ingress cell and merged back.
						if (!egressTx && !targetName.isEmpty() && "null".equals(sourceName)
								&& ingresses.path(targetName).isObject()) {
							// Routing wins over presentation: if this dispatch's `when`
							// was hand-edited and no longer equals the ingress's match
							// (diagram metadata), adopt the `when` as the match —
							// otherwise the round trip would silently revert a routing
							// condition from presentation data. The first dispatch per
							// ingress decides; a hand-written second dispatch with yet
							// another condition is not representable and regenerates
							// from the adopted match.
							String dispatchWhen = tx.path("when").asText("");
							Element ingressEl = ingressEls.get(targetName);
							if (ingressEl != null && !dispatchWhen.isEmpty()
									&& matchAdopted.add(targetName)
									&& !dispatchWhen.equals(ingressEl.getAttribute("match"))) {
								ingressEl.setAttribute("match", dispatchWhen);
							}
							ObjectNode kept = leftover(tx, DISPATCH_REGENERATED);
							// Position within this trigger. First-match-wins order is
							// semantics, and a hand-authored config may put an override
							// BEFORE the dispatch — export re-emits the dispatch at this
							// seq instead of unconditionally leading the trigger.
							kept.put("seq", seq);
							addDispatchExtra(ingressEls.get(targetName), method, kept);
							seq++;
							continue;
						}

						String sourceId = stateCellIds.get(sourceName);
						String targetId;
						if (egressTx) {
							// Connect to the egress node, matched by (routes, return
							// state). The routes/modifier live on the node + its
							// topology, not the edge. Synthesize the node (and its
							// route-back line) if the JSON carried no diagram.egresses
							// entry — a hand-edited config.
							String returnState = routeBackEgress ? targetName : "";
							String key = egressKey(tx.path("routes"), returnState);
							targetId = egressCellIdByKey.get(key);
							if (targetId == null) {
								targetId = String.valueOf(nextId++);
								String synthName = synthEgressName(routeBackEgress, hasRoutes,
										egressCellIdByKey.size());
								createEgressBox(doc, root, targetId, synthName, tx.path("routes"), null,
										statePlacements, egressCol, MARGIN_Y + egressRow++ * ROW_SPACING);
								egressCellIdByKey.put(key, targetId);
								if (routeBackEgress && stateCellIds.get(returnState) != null) {
									nextId = createRouteBackEdge(doc, root, targetId,
											stateCellIds.get(returnState), nextId);
								}
							}
						} else {
							targetId = stateCellIds.get(targetName);
						}
						if (sourceId == null || targetId == null) {
							// Every named target got a vertex and every terminal
							// transition synthesized an egress — unreachable,
							// but fail loudly rather than drop a transition.
							throw new IllegalArgumentException("Cannot place transition "
									+ sourceName + "/" + method + "[" + seq + "] -> '"
									+ (egressTx ? "(egress)" : targetName) + "'");
						}

						Element transition = doc.createElement("Transition");
						transition.setAttribute("label", method);
						// Evaluation order within this trigger — first match
						// wins at routing time, so the diagram must remember it.
						transition.setAttribute("seq", String.valueOf(seq));
						setIfPresent(transition, "txId", tx.path("id").asText(""));
						setIfPresent(transition, "when", tx.path("when").asText(""));
						setIfPresent(transition, "subscriber", tx.path("subscriber").asText(""));
						setIfPresent(transition, "region", tx.path("region").asText(""));
						// Routes ride the egress node, and its out-edge topology
						// (back to a state or not) determines ROUTE_BACK vs
						// ROUTE_FINAL, for an egress transition; only legacy
						// app-to-app transitions carry routes here.
						if (!egressTx) {
							setIfPresent(transition, "routeModifier", tx.path("routeModifier").asText(""));
						}
						setExtra(transition, tx, TRANSITION_KNOWN);

						// Routes (app-to-app only; egress routes live on the node).
						if (!egressTx) {
							JsonNode routes = tx.path("routes");
							if (routes.isArray()) {
								for (JsonNode route : routes) {
									Element rEl = doc.createElement("route");
									rEl.setAttribute("uri", route.asText(""));
									transition.appendChild(rEl);
								}
							}
						}

						transition.setAttribute("id", String.valueOf(nextId++));
						Element edgeCell = doc.createElement("mxCell");
						edgeCell.setAttribute("edge", "1");
						edgeCell.setAttribute("parent", "1");
						edgeCell.setAttribute("source", sourceId);
						edgeCell.setAttribute("target", targetId);

						Element edgeGeom = doc.createElement("mxGeometry");
						edgeGeom.setAttribute("relative", "1");
						edgeGeom.setAttribute("as", "geometry");
						edgeCell.appendChild(edgeGeom);

						transition.appendChild(edgeCell);
						root.appendChild(transition);
						seq++;
					}
				}
			}
		}

		// Serialize to string
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer t = tf.newTransformer();
		t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		t.setOutputProperty(OutputKeys.INDENT, "yes");
		StringWriter sw = new StringWriter();
		t.transform(new DOMSource(doc), new StreamResult(sw));
		return sw.toString();
	}

	/// Creates an ingress box: a `<Gateway>`-styled vertex for an entry state.
	/// `stateName` keys its stored position and is what edges resolve against;
	/// `label` is the displayed text (the default ingress shows "default" for
	/// the `"null"` state). A non-empty `match` is stored as the `match`
	/// attribute (export reads it to regenerate the null dispatch). The state's
	/// own selectors/extras/trigger-extras ride the box — per-ingress, now real.
	/// Returns the created `<Gateway>` wrapper so the caller can attach
	/// absorbed dispatch fields (`dispatchExtra`) to it once the transition
	/// pass has run.
	private Element createIngressBox(Document doc, Element root, String cellId,
			String stateName, String label, String match, JsonNode stateJson,
			JsonNode placements, int defX, int defY) {
		Element gateway = doc.createElement("Gateway");
		gateway.setAttribute("label", label);
		// No stateId attribute: for an ingress the LABEL is the state id (export
		// keys on it, so a rename renames the state). The default ingress maps to
		// "null" by being matchless, not by an id. `stateName` still keys the
		// stored placement below.
		gateway.setAttribute("id", cellId);
		if (match != null && !match.isEmpty()) {
			gateway.setAttribute("match", match);
		}
		if (stateJson != null && stateJson.isObject()) {
			appendSelectorChildren(doc, gateway, stateJson.path("selectors"));
			setExtra(gateway, stateJson, INGRESS_STATE_KNOWN);
			setTriggerExtras(gateway, stateJson.path("triggers"));
		}
		Element cell = doc.createElement("mxCell");
		cell.setAttribute("vertex", "1");
		cell.setAttribute("parent", "1");
		cell.setAttribute("style", "gateway");
		appendGeometry(doc, cell, placements, stateName, defX, defY, 120, 114);
		gateway.appendChild(cell);
		root.appendChild(gateway);
		return gateway;
	}

	/// Creates an egress box: a `<Gateway role="egress">` exit node. The mirror
	/// of [#createIngressBox]. Unlike a state it carries no selectors/triggers;
	/// it owns the `routes` (as `<route uri="…">` children). Its kind is
	/// topology — a route-back line (its out-edge) makes it ROUTE_BACK, no
	/// out-edge makes it ROUTE_FINAL — so no modifier is stored on the node.
	/// `name` keys its stored position; the routes (+ return state) identify it
	/// for round-trip matching.
	private String createEgressBox(Document doc, Element root, String cellId,
			String name, JsonNode routes, JsonNode egJson,
			JsonNode placements, int defX, int defY) {
		Element gateway = doc.createElement("Gateway");
		gateway.setAttribute("label", name);
		gateway.setAttribute("id", cellId);
		gateway.setAttribute("role", "egress");
		if (routes != null && routes.isArray()) {
			for (JsonNode route : routes) {
				Element rEl = doc.createElement("route");
				rEl.setAttribute("uri", route.asText(""));
				gateway.appendChild(rEl);
			}
		}
		// Preserve unknown egress fields (e.g. the retired `description`) on the
		// cell so the round-trip never silently drops them; FsmarExportServlet
		// merges them back. egJson is null for a synthesized egress (no diagram
		// entry to carry).
		if (egJson != null && egJson.isObject()) {
			setExtra(gateway, egJson, EGRESS_KNOWN);
		}
		Element cell = doc.createElement("mxCell");
		cell.setAttribute("vertex", "1");
		cell.setAttribute("parent", "1");
		cell.setAttribute("style", "egress");
		appendGeometry(doc, cell, placements, name, defX, defY, 120, 114);
		gateway.appendChild(cell);
		root.appendChild(gateway);
		return cellId;
	}

	/// The route-back line: an edge from an egress node back to a state. Its
	/// presence (not a stored attribute) is what makes the egress ROUTE_BACK;
	/// export reads it back as the egress's return state. Returns the next free
	/// cell id.
	private int createRouteBackEdge(Document doc, Element root, String egressCellId,
			String stateCellId, int nextId) {
		Element transition = doc.createElement("Transition");
		transition.setAttribute("label", "route-back");
		transition.setAttribute("id", String.valueOf(nextId++));
		Element edgeCell = doc.createElement("mxCell");
		edgeCell.setAttribute("edge", "1");
		edgeCell.setAttribute("parent", "1");
		edgeCell.setAttribute("source", egressCellId);
		edgeCell.setAttribute("target", stateCellId);
		edgeCell.setAttribute("style", "routeBack");
		Element edgeGeom = doc.createElement("mxGeometry");
		edgeGeom.setAttribute("relative", "1");
		edgeGeom.setAttribute("as", "geometry");
		edgeCell.appendChild(edgeGeom);
		transition.appendChild(edgeCell);
		root.appendChild(transition);
		return nextId;
	}

	/// Content-based identity for an egress: an egress IS its (routes, return
	/// state) — transitions with the same tuple exit the same way, so they share
	/// one egress node. Return state is empty for a ROUTE_FINAL exit.
	private static String egressKey(JsonNode routes, String returnState) {
		StringBuilder sb = new StringBuilder();
		sb.append(returnState == null ? "" : returnState).append('\u0000');
		if (routes != null && routes.isArray()) {
			for (JsonNode r : routes) {
				sb.append(r.asText("")).append('\u0001');
			}
		}
		return sb.toString();
	}

	/// Names an egress synthesized for a transition that had no diagram.egresses
	/// entry (a hand-edited config): by direction, deduped. A terminal transition
	/// with no routes is the downstream exit (nothing pushed).
	private static String synthEgressName(boolean routeBack, boolean hasRoutes, int index) {
		String base = routeBack ? "back-to-origin" : (hasRoutes ? "to-destination" : "downstream");
		return index == 0 ? base : base + "-" + (index + 1);
	}

	/// Creates a plain `<State>` vertex with its selectors/extras/trigger-extras.
	/// `name` is the state id (the map key, carried as the `stateId` attribute);
	/// the displayed `label` is the application it invokes (`app`, defaulting to
	/// the id) — so two states can share an app yet stay distinct.
	private String createStateBox(Document doc, Element root, String cellId,
			String name, JsonNode stateJson, JsonNode placements, int defX, int defY) {
		Element state = doc.createElement("State");
		String app = (stateJson != null) ? stateJson.path("app").asText("") : "";
		state.setAttribute("label", (app != null && !app.isEmpty()) ? app : name);
		state.setAttribute("stateId", name);
		state.setAttribute("id", cellId);
		if (stateJson != null && stateJson.isObject()) {
			appendSelectorChildren(doc, state, stateJson.path("selectors"));
			setExtra(state, stateJson, STATE_KNOWN);
			setTriggerExtras(state, stateJson.path("triggers"));
		}
		Element cell = doc.createElement("mxCell");
		cell.setAttribute("vertex", "1");
		cell.setAttribute("parent", "1");
		cell.setAttribute("style", "state");
		appendGeometry(doc, cell, placements, name, defX, defY, STATE_WIDTH, STATE_HEIGHT);
		state.appendChild(cell);
		root.appendChild(state);
		return cellId;
	}

	/// Appends the vertex's mxGeometry: the stored placement when
	/// `placements` carries one for this key, otherwise the caller's grid
	/// fallback. Keys are state names (the diagram's `states` map).
	private static void appendGeometry(Document doc, Element cell, JsonNode placements,
			String key, int defX, int defY, int width, int height) {
		int x = defX;
		int y = defY;
		JsonNode p = placements.path(key);
		if (p.has("x") && p.has("y") && p.path("x").isNumber() && p.path("y").isNumber()) {
			x = p.path("x").asInt();
			y = p.path("y").asInt();
		}
		Element geom = doc.createElement("mxGeometry");
		geom.setAttribute("x", String.valueOf(x));
		geom.setAttribute("y", String.valueOf(y));
		geom.setAttribute("width", String.valueOf(width));
		geom.setAttribute("height", String.valueOf(height));
		geom.setAttribute("as", "geometry");
		cell.appendChild(geom);
	}

	/// Appends one `<selector>` child per entry of `selectors`. Known fields
	/// become attributes; everything else (table, namespaces, future fields)
	/// is preserved in the selector's own `extra` attribute.
	private void appendSelectorChildren(Document doc, Element parent, JsonNode selectors) {
		if (!selectors.isArray()) return;
		for (JsonNode sel : selectors) {
			Element selEl = doc.createElement("selector");
			setIfPresent(selEl, "id", sel.path("id").asText(""));
			setIfPresent(selEl, "type", sel.path("type").asText(""));
			setIfPresent(selEl, "attribute", sel.path("attribute").asText(""));
			setIfPresent(selEl, "pattern", sel.path("pattern").asText(""));
			setIfPresent(selEl, "expression", sel.path("expression").asText(""));
			// AttributeSelector.allInstances — omitted when false (the model
			// marks it @JsonInclude(NON_DEFAULT)), so only carry it when set.
			if (sel.path("allInstances").asBoolean(false)) {
				selEl.setAttribute("allInstances", "true");
			}
			// XmlSelector.namespaces — a prefix→URI map; rides as a JSON object
			// string on the cell, edited as a key/value grid in the panel.
			if (sel.path("namespaces").isObject() && sel.path("namespaces").size() > 0) {
				selEl.setAttribute("namespaces", sel.path("namespaces").toString());
			}
			setExtra(selEl, sel, SELECTOR_KNOWN);
			parent.appendChild(selEl);
		}
	}

	/// Stores trigger-level unknown fields as a `triggerExtras` attribute:
	/// a JSON object keyed by SIP method. Trigger only defines `transitions`
	/// today, so this is usually absent — cheap future-proofing.
	private void setTriggerExtras(Element stateEl, JsonNode triggers) {
		if (!triggers.isObject()) return;
		ObjectNode extras = mapper.createObjectNode();
		Iterator<Map.Entry<String, JsonNode>> it = triggers.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			ObjectNode leftover = leftover(e.getValue(), TRIGGER_KNOWN);
			if (leftover.size() > 0) {
				extras.set(e.getKey(), leftover);
			}
		}
		if (extras.size() > 0) {
			stateEl.setAttribute("triggerExtras", extras.toString());
		}
	}

	/// Records the fields of an absorbed null→ingress dispatch transition on
	/// the ingress's `<Gateway>` element, as a `dispatchExtra` JSON object
	/// keyed by SIP method — the same shape as `triggerExtras`.
	///
	/// The dispatch transition itself is never drawn (the editor re-derives it
	/// from the ingress's `match`), so without this its `subscriber`, `region`,
	/// `routes`, `routeModifier` and `id` would be lost on the round trip. If
	/// two dispatch transitions for one ingress share a method — possible only
	/// in a hand-written config — the first wins; the second's fields would be
	/// unreachable anyway, since export emits one dispatch per (ingress, method).
	private void addDispatchExtra(Element ingressEl, String method, ObjectNode fields) {
		if (ingressEl == null) {
			return;
		}
		ObjectNode extras;
		String raw = ingressEl.getAttribute("dispatchExtra");
		if (raw == null || raw.isEmpty()) {
			extras = mapper.createObjectNode();
		} else {
			try {
				extras = (ObjectNode) mapper.readTree(raw);
			} catch (IOException | ClassCastException e) {
				extras = mapper.createObjectNode();
			}
		}
		if (!extras.has(method)) {
			extras.set(method, fields);
			ingressEl.setAttribute("dispatchExtra", extras.toString());
		}
	}

	/// Sets the element's `extra` attribute to the JSON object of all fields
	/// of `node` not in `known` — the no-silent-strip passthrough.
	private void setExtra(Element el, JsonNode node, Set<String> known) {
		ObjectNode leftover = leftover(node, known);
		if (leftover.size() > 0) {
			el.setAttribute("extra", leftover.toString());
		}
	}

	private ObjectNode leftover(JsonNode node, Set<String> known) {
		ObjectNode out = mapper.createObjectNode();
		if (node != null && node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = node.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				if (!known.contains(e.getKey())) {
					out.set(e.getKey(), e.getValue());
				}
			}
		}
		return out;
	}

	private static void setIfPresent(Element el, String name, String value) {
		if (value != null && !value.isEmpty()) {
			el.setAttribute(name, value);
		}
	}

}
