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
/// SIP method). A config the editor can't faithfully represent at all —
/// e.g. a transition with no `next` — is rejected with 400 and a named
/// reason rather than imported lossily.
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
	static final Set<String> TRIGGER_KNOWN = setOf("transitions");
	static final Set<String> TRANSITION_KNOWN = setOf("id", "when", "next", "subscriber",
			"region", "routes", "routeModifier");
	static final Set<String> SELECTOR_KNOWN = setOf("id", "type", "description",
			"attribute", "pattern", "expression");

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

		// Reject transitions the diagram cannot represent before building
		// anything: a transition with no `next` has no edge target.
		validateRepresentable(fsmar);

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
		int nextId = 2;
		int ingressRow = 0;   // left-column stacking for ingress boxes

		// The default ingress = the "null" state, always present as the entry
		// point. Rendered as an ingress cloud labeled "default"; carries the
		// shared selectors that run for all traffic.
		String nullCellId = String.valueOf(nextId++);
		createIngressBox(doc, root, nullCellId, "null", "default", null,
				states.path("null"), statePlacements, MARGIN_X, MARGIN_Y + ingressRow++ * ROW_SPACING);
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
				createIngressBox(doc, root, cellId, name, name,
						ingresses.path(name).path("match").asText(""),
						stateJson, statePlacements, MARGIN_X, MARGIN_Y + ingressRow++ * ROW_SPACING);
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
				createEgressBox(doc, root, cellId, name, egJson.path("routes"),
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
						// An egress transition routes the call out of OCCAS via its
						// routes: ROUTE_FINAL (no next, the call leaves) or ROUTE_BACK
						// (next = the resume state after an external round-trip). For
						// ROUTE_BACK the `next` is the egress's return state, NOT a
						// normal edge target — it's drawn as the egress's out-edge.
						boolean finalEgress = targetName.isEmpty() && hasRoutes;
						boolean routeBackEgress = !targetName.isEmpty() && hasRoutes && routeBack;
						boolean egressTx = finalEgress || routeBackEgress;

						// Absorb generated dispatch transitions (null → ingress).
						if (!egressTx && !targetName.isEmpty() && "null".equals(sourceName)
								&& ingresses.path(targetName).isObject()) {
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
								String synthName = synthEgressName(routeBackEgress, egressCellIdByKey.size());
								createEgressBox(doc, root, targetId, synthName, tx.path("routes"),
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
							// validateRepresentable guarantees a routable transition
							// and every named target got a vertex — unreachable,
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

	/// Rejects configs the diagram cannot represent without loss. Currently:
	/// a transition with no `next` (an edge needs a target; fsmar3 cannot
	/// route it either). Throws IllegalArgumentException with the exact
	/// location so the admin can fix the JSON.
	private static void validateRepresentable(JsonNode fsmar) {
		JsonNode states = fsmar.path("states");
		if (!states.isObject()) return;
		Iterator<Map.Entry<String, JsonNode>> stateIt = states.fields();
		while (stateIt.hasNext()) {
			Map.Entry<String, JsonNode> stateEntry = stateIt.next();
			JsonNode triggers = stateEntry.getValue().path("triggers");
			if (!triggers.isObject()) continue;
			Iterator<Map.Entry<String, JsonNode>> trigIt = triggers.fields();
			while (trigIt.hasNext()) {
				Map.Entry<String, JsonNode> trigEntry = trigIt.next();
				JsonNode txList = trigEntry.getValue().path("transitions");
				if (!txList.isArray()) continue;
				int i = 0;
				for (JsonNode tx : txList) {
					boolean hasNext = !tx.path("next").asText("").isEmpty();
					boolean hasRoutes = tx.path("routes").isArray() && tx.path("routes").size() > 0;
					// A transition needs SOMETHING to do: route to a next
					// application, or carry routes (a terminal egress that sends
					// the call out of OCCAS). Neither = unroutable.
					if (!hasNext && !hasRoutes) {
						throw new IllegalArgumentException(
								"Transition states['" + stateEntry.getKey() + "'].triggers['"
								+ trigEntry.getKey() + "'].transitions[" + i + "] has neither 'next' "
								+ "nor 'routes' — the editor (and FSMAR 3 itself) cannot route it. "
								+ "Fix the JSON and re-import.");
					}
					i++;
				}
			}
		}
	}

	/// Creates an ingress box: a `<Gateway>`-styled vertex for an entry state.
	/// `stateName` keys its stored position and is what edges resolve against;
	/// `label` is the displayed text (the default ingress shows "default" for
	/// the `"null"` state). A non-empty `match` is stored as the `match`
	/// attribute (export reads it to regenerate the null dispatch). The state's
	/// own selectors/extras/trigger-extras ride the box — per-ingress, now real.
	private String createIngressBox(Document doc, Element root, String cellId,
			String stateName, String label, String match, JsonNode stateJson,
			JsonNode placements, int defX, int defY) {
		Element gateway = doc.createElement("Gateway");
		gateway.setAttribute("label", label);
		// The state id (map key). For the default ingress this is "null" while the
		// label shows "default"; for a named ingress id and label coincide.
		gateway.setAttribute("stateId", stateName);
		gateway.setAttribute("id", cellId);
		if (match != null && !match.isEmpty()) {
			gateway.setAttribute("match", match);
		}
		if (stateJson != null && stateJson.isObject()) {
			appendSelectorChildren(doc, gateway, stateJson.path("selectors"));
			setExtra(gateway, stateJson, STATE_KNOWN);
			setTriggerExtras(gateway, stateJson.path("triggers"));
		}
		Element cell = doc.createElement("mxCell");
		cell.setAttribute("vertex", "1");
		cell.setAttribute("parent", "1");
		cell.setAttribute("style", "gateway");
		appendGeometry(doc, cell, placements, stateName, defX, defY, 120, 114);
		gateway.appendChild(cell);
		root.appendChild(gateway);
		return cellId;
	}

	/// Creates an egress box: a `<Gateway role="egress">` exit node. The mirror
	/// of [#createIngressBox]. Unlike a state it carries no selectors/triggers;
	/// it owns the `routes` (as `<route uri="…">` children). Its kind is
	/// topology — a route-back line (its out-edge) makes it ROUTE_BACK, no
	/// out-edge makes it ROUTE_FINAL — so no modifier is stored on the node.
	/// `name` keys its stored position; the routes (+ return state) identify it
	/// for round-trip matching.
	private String createEgressBox(Document doc, Element root, String cellId,
			String name, JsonNode routes,
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
	/// entry (a hand-edited config): by direction, deduped.
	private static String synthEgressName(boolean routeBack, int index) {
		String base = routeBack ? "back-to-origin" : "to-destination";
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
