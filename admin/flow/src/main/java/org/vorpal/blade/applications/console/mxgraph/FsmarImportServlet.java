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
	static final Set<String> ROOT_KNOWN = setOf("defaultApplication", "states");
	static final Set<String> STATE_KNOWN = setOf("selectors", "triggers");
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

		// Default layer
		Element layer = doc.createElement("Layer");
		layer.setAttribute("label", "Default Layer");
		Element layerCell = doc.createElement("mxCell");
		layerCell.setAttribute("id", "1");
		layerCell.setAttribute("parent", "0");
		layer.appendChild(layerCell);
		root.appendChild(layer);

		// Reject transitions the diagram cannot represent before building
		// anything: a transition with no `next` has no edge target.
		validateRepresentable(fsmar);

		// Collect real state names (anything other than "null") in insertion order
		LinkedHashMap<String, Integer> stateIds = new LinkedHashMap<>();
		int nextId = 2;

		JsonNode states = fsmar.path("states");
		if (states.isObject()) {
			Iterator<String> stateNames = states.fieldNames();
			while (stateNames.hasNext()) {
				String name = stateNames.next();
				if (!"null".equals(name) && !stateIds.containsKey(name)) {
					stateIds.put(name, nextId++);
				}
			}
		}

		// Also collect any 'next' targets that aren't already in states (skip "null")
		boolean needEgress = false;
		if (states.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> stateIt = states.fields();
			while (stateIt.hasNext()) {
				JsonNode triggers = stateIt.next().getValue().path("triggers");
				if (!triggers.isObject()) continue;
				Iterator<Map.Entry<String, JsonNode>> trigIt = triggers.fields();
				while (trigIt.hasNext()) {
					JsonNode txList = trigIt.next().getValue().path("transitions");
					if (!txList.isArray()) continue;
					for (JsonNode tx : txList) {
						String next = tx.path("next").asText("");
						if ("null".equals(next)) {
							needEgress = true;
						} else if (!next.isEmpty() && !stateIds.containsKey(next)) {
							stateIds.put(next, nextId++);
						}
					}
				}
			}
		}

		// The ingress cloud is the visual home of the "null" state — needed
		// whenever states["null"] exists at all (it can carry selectors for
		// initial requests even without triggers).
		JsonNode nullState = states.path("null");
		boolean needIngress = nullState.isObject();

		// Layout: ingress cloud at far left, then state grid, then egress at far right.
		Map<String, String> stateCellIds = new HashMap<>();

		String ingressCellId = null;
		if (needIngress) {
			ingressCellId = String.valueOf(nextId++);
			Element ingress = doc.createElement("Ingress");
			ingress.setAttribute("label", "ingress");
			// The "null" state's own data lives on the ingress cloud:
			// selectors (run against initial requests), unknown fields,
			// trigger-level extras.
			appendSelectorChildren(doc, ingress, nullState.path("selectors"));
			setExtra(ingress, nullState, STATE_KNOWN);
			setTriggerExtras(ingress, nullState.path("triggers"));
			Element cell = doc.createElement("mxCell");
			cell.setAttribute("id", ingressCellId);
			cell.setAttribute("vertex", "1");
			cell.setAttribute("parent", "1");
			cell.setAttribute("style", "ingressCloud");
			Element geom = doc.createElement("mxGeometry");
			geom.setAttribute("x", String.valueOf(MARGIN_X));
			geom.setAttribute("y", String.valueOf(MARGIN_Y));
			geom.setAttribute("width", "120");
			geom.setAttribute("height", "80");
			geom.setAttribute("as", "geometry");
			cell.appendChild(geom);
			ingress.appendChild(cell);
			root.appendChild(ingress);
			stateCellIds.put("null", ingressCellId);
		}

		// Create State vertices with grid positions, offset to leave room for ingress
		int idx = 0;
		int stateColOffset = MARGIN_X + COL_SPACING; // shift right past ingress
		for (Map.Entry<String, Integer> entry : stateIds.entrySet()) {
			String name = entry.getKey();
			int id = entry.getValue();
			int col = idx % COLS;
			int row = idx / COLS;
			int x = stateColOffset + col * COL_SPACING;
			int y = MARGIN_Y + row * ROW_SPACING;

			Element state = doc.createElement("State");
			state.setAttribute("label", name);

			JsonNode stateJson = states.path(name);
			if (stateJson.isObject()) {
				appendSelectorChildren(doc, state, stateJson.path("selectors"));
				setExtra(state, stateJson, STATE_KNOWN);
				setTriggerExtras(state, stateJson.path("triggers"));
			}

			Element stateCell = doc.createElement("mxCell");
			stateCell.setAttribute("id", String.valueOf(id));
			stateCell.setAttribute("vertex", "1");
			stateCell.setAttribute("parent", "1");
			stateCell.setAttribute("style", "state");

			Element geom = doc.createElement("mxGeometry");
			geom.setAttribute("x", String.valueOf(x));
			geom.setAttribute("y", String.valueOf(y));
			geom.setAttribute("width", String.valueOf(STATE_WIDTH));
			geom.setAttribute("height", String.valueOf(STATE_HEIGHT));
			geom.setAttribute("as", "geometry");
			stateCell.appendChild(geom);

			state.appendChild(stateCell);
			root.appendChild(state);

			stateCellIds.put(name, String.valueOf(id));
			idx++;
		}

		// Egress cloud at far right
		String egressCellId = null;
		if (needEgress) {
			egressCellId = String.valueOf(nextId++);
			Element egress = doc.createElement("Egress");
			egress.setAttribute("label", "egress");
			Element cell = doc.createElement("mxCell");
			cell.setAttribute("id", egressCellId);
			cell.setAttribute("vertex", "1");
			cell.setAttribute("parent", "1");
			cell.setAttribute("style", "egressCloud");
			int egressX = stateColOffset + COLS * COL_SPACING;
			Element geom = doc.createElement("mxGeometry");
			geom.setAttribute("x", String.valueOf(egressX));
			geom.setAttribute("y", String.valueOf(MARGIN_Y));
			geom.setAttribute("width", "120");
			geom.setAttribute("height", "80");
			geom.setAttribute("as", "geometry");
			cell.appendChild(geom);
			egress.appendChild(cell);
			root.appendChild(egress);
			// Note: we deliberately do NOT put "null" in stateCellIds for egress —
			// the edge-creation loop below handles target=="null" by routing to
			// egressCellId explicitly. (If we mapped "null" -> egressCellId here,
			// it would conflict with the ingress mapping above.)
		}

		// Create Transition edges
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

						// Resolve source: "null" -> ingress cloud cell
						String sourceId = stateCellIds.get(sourceName);
						// Resolve target: "null" -> egress cloud cell
						String targetId = "null".equals(targetName) ? egressCellId : stateCellIds.get(targetName);
						if (sourceId == null || targetId == null) {
							// validateRepresentable guarantees next is present,
							// and the collection passes above created vertices
							// for every named target — this is unreachable, but
							// fail loudly rather than drop a transition.
							throw new IllegalArgumentException("Cannot place transition "
									+ sourceName + "/" + method + "[" + seq + "] -> '" + targetName + "'");
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
						setIfPresent(transition, "routeModifier", tx.path("routeModifier").asText(""));
						setExtra(transition, tx, TRANSITION_KNOWN);

						// Routes
						JsonNode routes = tx.path("routes");
						if (routes.isArray()) {
							for (JsonNode route : routes) {
								Element rEl = doc.createElement("route");
								rEl.setAttribute("uri", route.asText(""));
								transition.appendChild(rEl);
							}
						}

						Element edgeCell = doc.createElement("mxCell");
						edgeCell.setAttribute("id", String.valueOf(nextId++));
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
					if (tx.path("next").asText("").isEmpty()) {
						throw new IllegalArgumentException(
								"Transition states['" + stateEntry.getKey() + "'].triggers['"
								+ trigEntry.getKey() + "'].transitions[" + i + "] has no 'next' — "
								+ "the editor (and FSMAR 3 itself) cannot route it. "
								+ "Fix the JSON and re-import.");
					}
					i++;
				}
			}
		}
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
			setIfPresent(selEl, "description", sel.path("description").asText(""));
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
