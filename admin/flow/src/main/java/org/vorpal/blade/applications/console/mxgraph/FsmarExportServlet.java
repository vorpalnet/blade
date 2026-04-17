package org.vorpal.blade.applications.console.mxgraph;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Exports the current mxGraph editor model as FSMAR 3 JSON.
///
/// Reads an `xml` request parameter containing the mxGraph XML, walks the
/// graph cells (vertices = States, edges = Transitions), and builds the
/// FSMAR 3 JSON structure: defaultApplication + states (map of state name
/// to triggers (map of SIP method to transitions list)).
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

		// Map cell ID -> wrapper element (State or Transition or FlowModel)
		Map<String, Element> wrappersById = new HashMap<>();
		// Map cell ID -> state name (for resolving edge source/target).
		// Ingress/Egress clouds map to "null" here.
		Map<String, String> stateNamesById = new HashMap<>();
		// Set of "real" state names (from <State> elements only) — used to seed
		// empty state entries in the JSON. Cloud cells do NOT contribute to this
		// set, so a lonely egress cloud doesn't create an empty "null" entry.
		java.util.Set<String> realStateNames = new java.util.HashSet<>();

		String defaultApplication = null;

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
						realStateNames.add(stateName);
					}
					break;
				case "Ingress":
				case "Egress":
					// Both cloud types map to the literal "null" state — the
					// display label on the cloud is for humans only.
					stateNamesById.put(cellId, "null");
					break;
				case "FlowModel":
					String def = wrapper.getAttribute("defaultApplication");
					if (def != null && !def.isEmpty()) {
						defaultApplication = def;
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
		}

		// Build the FSMAR JSON structure
		ObjectNode rootNode = mapper.createObjectNode();
		if (defaultApplication != null) {
			rootNode.put("defaultApplication", defaultApplication);
		}

		ObjectNode statesNode = rootNode.putObject("states");

		// Ensure every real state has an entry (even with no triggers).
		// Cloud-derived "null" entries are NOT pre-seeded — they only appear if
		// transitions actually originate from an ingress cloud.
		for (String stateName : realStateNames) {
			if (!statesNode.has(stateName)) {
				statesNode.putObject(stateName).putObject("triggers");
			}
		}

		// Second pass: walk all Transition elements as edges
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
				continue;
			}

			String sourceState = stateNamesById.get(sourceId);
			String targetState = stateNamesById.get(targetId);
			if (sourceState == null || targetState == null) {
				continue;
			}

			String method = tx.getAttribute("label");
			if (method == null || method.isEmpty()) {
				method = "INVITE";
			}

			// Ensure the state node exists
			ObjectNode stateNode = (ObjectNode) statesNode.get(sourceState);
			if (stateNode == null) {
				stateNode = statesNode.putObject(sourceState);
			}
			ObjectNode triggersNode = (ObjectNode) stateNode.get("triggers");
			if (triggersNode == null) {
				triggersNode = stateNode.putObject("triggers");
			}
			ObjectNode triggerNode = (ObjectNode) triggersNode.get(method);
			if (triggerNode == null) {
				triggerNode = triggersNode.putObject(method);
			}
			ArrayNode txList = (ArrayNode) triggerNode.get("transitions");
			if (txList == null) {
				txList = triggerNode.putArray("transitions");
			}

			// Build the transition object
			ObjectNode txNode = mapper.createObjectNode();
			String txId = tx.getAttribute("txId");
			if (txId != null && !txId.isEmpty()) {
				txNode.put("id", txId);
			}

			// Selector groups (OR logic); each group contains selectors (AND logic)
			NodeList groups = tx.getElementsByTagName("selectorGroup");
			if (groups.getLength() > 0) {
				ArrayNode groupsArr = txNode.putArray("selectorGroups");
				for (int g = 0; g < groups.getLength(); g++) {
					Element group = (Element) groups.item(g);
					ObjectNode groupNode = mapper.createObjectNode();
					ArrayNode selArr = groupNode.putArray("selectors");

					NodeList selectors = group.getElementsByTagName("selector");
					for (int s = 0; s < selectors.getLength(); s++) {
						Element sel = (Element) selectors.item(s);
						ObjectNode selNode = mapper.createObjectNode();
						addIfPresent(selNode, "id", sel.getAttribute("id"));
						addIfPresent(selNode, "type", sel.getAttribute("type"));
						addIfPresent(selNode, "attribute", sel.getAttribute("attribute"));
						addIfPresent(selNode, "pattern", sel.getAttribute("pattern"));
						// Extractions: nested <extract name="..." path="..."/> children
						NodeList extracts = sel.getElementsByTagName("extract");
						if (extracts.getLength() > 0) {
							ObjectNode extractions = selNode.putObject("extractions");
							for (int e = 0; e < extracts.getLength(); e++) {
								Element ex = (Element) extracts.item(e);
								String name = ex.getAttribute("name");
								String path = ex.getAttribute("path");
								if (name != null && !name.isEmpty()) {
									extractions.put(name, path == null ? "" : path);
								}
							}
						}
						addIfPresent(selNode, "expression", sel.getAttribute("expression"));
						selArr.add(selNode);
					}

					groupsArr.add(groupNode);
				}
			}

			// Subscriber
			String subscriber = tx.getAttribute("subscriber");
			if (subscriber != null && !subscriber.isEmpty()) {
				txNode.put("subscriber", subscriber);
			}

			// Routes
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

			// Next
			txNode.put("next", targetState);

			txList.add(txNode);
		}

		return rootNode;
	}

	private static void addIfPresent(ObjectNode node, String key, String value) {
		if (value != null && !value.isEmpty()) {
			node.put(key, value);
		}
	}

	private static Element firstChildElement(Element parent, String tagName) {
		NodeList kids = parent.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			Node n = kids.item(i);
			if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
				return (Element) n;
			}
		}
		return null;
	}

}
