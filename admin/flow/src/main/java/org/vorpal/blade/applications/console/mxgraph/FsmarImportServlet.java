package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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

/// Imports FSMAR 3 JSON and produces an mxGraph XML model for the editor.
///
/// Reads a `json` request parameter, parses it into the FSMAR 3 structure,
/// and generates an mxGraph XML document with auto-positioned State vertices
/// and Transition edges. Returns the XML as `text/xml`.
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

		// FlowModel root cell (id=0)
		Element flowModel = doc.createElement("FlowModel");
		flowModel.setAttribute("label", "FSMAR Flow");
		flowModel.setAttribute("id", "0");
		String defaultApp = fsmar.path("defaultApplication").asText("");
		flowModel.setAttribute("defaultApplication", defaultApp);
		root.appendChild(flowModel);

		// Default layer
		Element layer = doc.createElement("Layer");
		layer.setAttribute("label", "Default Layer");
		Element layerCell = doc.createElement("mxCell");
		layerCell.setAttribute("id", "1");
		layerCell.setAttribute("parent", "0");
		layer.appendChild(layerCell);
		root.appendChild(layer);

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

		// Need an ingress cloud if states["null"] exists with at least one transition
		boolean needIngress = false;
		JsonNode nullState = states.path("null");
		if (nullState.isObject()) {
			JsonNode nullTriggers = nullState.path("triggers");
			if (nullTriggers.isObject() && nullTriggers.size() > 0) {
				needIngress = true;
			}
		}

		// Layout: ingress cloud at far left, then state grid, then egress at far right.
		// Reserve the leftmost column (col 0 of the visual grid) for the ingress cloud
		// and the rightmost column for the egress cloud, regardless of how many states.
		Map<String, String> stateCellIds = new HashMap<>();

		String ingressCellId = null;
		if (needIngress) {
			ingressCellId = String.valueOf(nextId++);
			Element ingress = doc.createElement("Ingress");
			ingress.setAttribute("label", "ingress");
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

					for (JsonNode tx : txList) {
						String targetName = tx.path("next").asText("");
						if (targetName.isEmpty()) continue;

						// Resolve source: "null" -> ingress cloud cell
						String sourceId = stateCellIds.get(sourceName);
						// Resolve target: "null" -> egress cloud cell
						String targetId = "null".equals(targetName) ? egressCellId : stateCellIds.get(targetName);
						if (sourceId == null || targetId == null) continue;

						Element transition = doc.createElement("Transition");
						transition.setAttribute("label", method);
						String txId = tx.path("id").asText("");
						if (!txId.isEmpty()) {
							transition.setAttribute("txId", txId);
						}
						String subscriber = tx.path("subscriber").asText("");
						if (!subscriber.isEmpty()) {
							transition.setAttribute("subscriber", subscriber);
						}

						// Selector groups (OR logic); each group contains selectors (AND logic)
						JsonNode selectorGroups = tx.path("selectorGroups");
						if (selectorGroups.isArray()) {
							for (JsonNode grp : selectorGroups) {
								Element groupEl = doc.createElement("selectorGroup");
								JsonNode selectors = grp.path("selectors");
								if (selectors.isArray()) {
									for (JsonNode sel : selectors) {
										Element selEl = doc.createElement("selector");
										setIfPresent(selEl, "id", sel.path("id").asText(""));
										setIfPresent(selEl, "type", sel.path("type").asText(""));
										setIfPresent(selEl, "attribute", sel.path("attribute").asText(""));
										setIfPresent(selEl, "pattern", sel.path("pattern").asText(""));
										JsonNode extractions = sel.path("extractions");
										if (extractions.isObject()) {
											Iterator<String> names = extractions.fieldNames();
											while (names.hasNext()) {
												String name = names.next();
												String path = extractions.path(name).asText("");
												Element exEl = doc.createElement("extract");
												exEl.setAttribute("name", name);
												exEl.setAttribute("path", path);
												selEl.appendChild(exEl);
											}
										}
										setIfPresent(selEl, "expression", sel.path("expression").asText(""));
										groupEl.appendChild(selEl);
									}
								}
								transition.appendChild(groupEl);
							}
						}

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

	private static void setIfPresent(Element el, String name, String value) {
		if (value != null && !value.isEmpty()) {
			el.setAttribute(name, value);
		}
	}

}
