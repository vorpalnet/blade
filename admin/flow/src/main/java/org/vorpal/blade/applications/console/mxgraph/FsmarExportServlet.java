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
		// Map cell ID -> state name (for resolving edge source/target).
		// Ingress/Egress clouds map to "null" here.
		Map<String, String> stateNamesById = new HashMap<>();
		// "Real" state names (from <State> elements only) mapped to their
		// wrapper elements — used to seed state entries (selectors + extras)
		// even when no transitions touch them. The Ingress cloud carries the
		// "null" state's selectors/extras; Egress carries nothing.
		Map<String, Element> realStates = new java.util.LinkedHashMap<>();
		Element ingressEl = null;

		String defaultApplication = null;
		String rootExtra = null;

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
					String stateName = wrapper.getAttribute("label");
					stateNamesById.put(cellId, stateName);
					if (stateName != null && !stateName.isEmpty()) {
						realStates.put(stateName, wrapper);
					}
					break;
				case "Ingress":
					stateNamesById.put(cellId, "null");
					ingressEl = wrapper;
					break;
				case "Egress":
					// Maps to the literal "null" state — display label is for
					// humans only, and it contributes no state data.
					stateNamesById.put(cellId, "null");
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

		// Seed every real state entry: selectors first (schema order), then
		// triggers, then state-level extras.
		for (Map.Entry<String, Element> entry : realStates.entrySet()) {
			seedState(statesNode, entry.getKey(), entry.getValue());
		}
		// The "null" state exists when an ingress cloud does. Its selectors
		// (run against initial requests) and extras live on the cloud.
		if (ingressEl != null) {
			seedState(statesNode, "null", ingressEl);
		}

		// Second pass: collect Transition edges grouped by (source state,
		// method), keeping each one's seq for ordering.
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

			String sourceState = stateNamesById.get(sourceId);
			String targetState = stateNamesById.get(targetId);
			if (sourceState == null || targetState == null) {
				throw new IllegalArgumentException("A transition edge labeled '"
						+ tx.getAttribute("label") + "' connects to a cell that is not a "
						+ "State/Ingress/Egress. Reconnect it and export again.");
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

			TxEntry e = new TxEntry();
			e.seq = parseSeq(tx.getAttribute("seq"));
			e.node = txNode;
			collected.computeIfAbsent(sourceState, k -> new java.util.LinkedHashMap<>())
					.computeIfAbsent(method, k -> new ArrayList<>())
					.add(e);
		}

		// Emit collected transitions sorted by seq within each trigger —
		// first-match-wins order is semantic. New edges (no seq) sort last
		// in document order.
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
			// Trigger-level extras stashed on the source state's element
			ObjectNode triggerExtras = parseTriggerExtras(
					"null".equals(stateName) ? ingressEl : realStates.get(stateName));

			for (Map.Entry<String, List<TxEntry>> trigEntry : stateEntry.getValue().entrySet()) {
				String method = trigEntry.getKey();
				List<TxEntry> list = trigEntry.getValue();
				list.sort(Comparator.comparingInt(t -> t.seq));

				ObjectNode triggerNode = triggersNode.putObject(method);
				ArrayNode txList = triggerNode.putArray("transitions");
				for (TxEntry e : list) {
					txList.add(e.node);
				}
				if (triggerExtras != null && triggerExtras.has(method)) {
					mergeInto(triggerNode, (ObjectNode) triggerExtras.get(method));
				}
			}
		}

		return rootNode;
	}

	/// Creates the JSON entry for one state from its wrapper element:
	/// selectors (children), empty triggers (filled by the edge pass), and
	/// state-level extras.
	private void seedState(ObjectNode statesNode, String name, Element wrapper) {
		if (statesNode.has(name)) return;
		ObjectNode stateNode = statesNode.putObject(name);
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
			addIfPresent(selNode, "description", sel.getAttribute("description"));
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
