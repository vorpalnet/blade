package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.fsmar.Fsmar2Converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Converts a legacy FSMAR 2 configuration to FSMAR 3 for the Flow editor's
/// import path. POST `fsmar2=<json>`; returns
/// `{json, warnings[], states, transitions, selectors, needsReview}` where
/// `json` is the converted FSMAR 3 configuration (the form the editor opens and
/// saves).
///
/// The conversion is the exact framework [Fsmar2Converter] the offline CLI
/// uses — reachable here because the FSMAR config model and the converter live
/// in the framework JAR (which this WAR bundles), not in the engine `approuter/`
/// fat JAR (which an AdminServer WAR must not link). Anything the converter
/// cannot translate faithfully becomes `when:"false"` (fail closed) with a
/// `REVIEW:` warning, so a conversion gap narrows routing rather than silently
/// widening it; the editor surfaces those warnings before the diagram loads.
@WebServlet("/fsmarConvert")
public class FsmarConvertServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// The browser POSTs the config UTF-8-encoded (encodeURIComponent). Without
		// this, getParameter() decodes the body as ISO-8859-1 (the servlet default)
		// and non-ASCII config text arrives mojibaked.
		request.setCharacterEncoding("UTF-8");

		String fsmar2 = request.getParameter("fsmar2");
		if (fsmar2 == null || fsmar2.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing fsmar2 parameter");
			return;
		}

		JsonNode tree;
		try {
			tree = mapper.readTree(fsmar2);
		} catch (IOException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not valid JSON: " + e.getMessage());
			return;
		}

		try {
			Fsmar2Converter.Result result = Fsmar2Converter.convert(tree);
			// Round-trips through the FSMAR 3 classes and compiles every `when`
			// with the engine's Expression parser before returning.
			String json = Fsmar2Converter.toValidatedJson(result);

			ObjectNode out = mapper.createObjectNode();
			out.put("json", json);
			out.put("states", result.states);
			out.put("transitions", result.transitions);
			out.put("selectors", result.selectors);
			out.put("needsReview", result.needsReview());
			ArrayNode warnings = out.putArray("warnings");
			for (String w : result.warnings) {
				warnings.add(w);
			}

			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out2 = response.getWriter();
			out2.write(mapper.writeValueAsString(out));
			out2.flush();
		} catch (Exception e) {
			throw new ServletException("FSMAR 2 conversion failed: " + e.getMessage(), e);
		}
	}
}
