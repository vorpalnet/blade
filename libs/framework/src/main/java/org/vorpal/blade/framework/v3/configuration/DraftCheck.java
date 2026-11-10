package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The checks Save (and restore) runs on a draft of one configuration file
/// before writing it, shared by every editor so they cannot drift apart:
///
/// 1. The draft as it runs: the domain file on its own, a cluster or server
///    file merged with the files below it ([ConfigFiles#applied]). A
///    cluster or server file need not load alone, since it may change one
///    field of an inherited entry.
/// 2. No file above the draft is left changing a rule or rule set the draft
///    renames or removes ([ConfigFiles#orphans]).
/// 3. Every file above the draft still loads and passes, merged over it: a
///    server file naming a rule set this draft removes, say.
///
/// What a configuration must satisfy is the editor's [Check].
public final class DraftCheck {

	private DraftCheck() {
	}

	/// What is wrong with `doc`, a whole configuration as a server would run
	/// it; throws when the application could not load it at all.
	/// `domainFile` says whether `doc` is the domain file alone.
	@FunctionalInterface
	public interface Check {
		List<String> problems(JsonNode doc, boolean domainFile) throws Exception;
	}

	/// Every problem with saving `draft` as file `file` of `app`, empty when
	/// there is none. `section` names where the rule sets live for the
	/// rename check (`crud` in an application's file), null for the top level.
	public static List<String> problems(String app, String file, JsonNode draft, String section,
			Map<String, String> membership, ObjectMapper mapper, Check check) throws IOException {
		List<String> out = new ArrayList<>();
		boolean domain = ConfigFiles.isDomain(file);
		try {
			out.addAll(check.problems(domain ? draft : ConfigFiles.applied(app, file, draft, membership, mapper), domain));
		} catch (Exception e) {
			out.add((domain ? "" : "merged with the files below it, ") + app + " could not load it: " + e.getMessage());
			return out;
		}
		if (!out.isEmpty()) return out;
		out.addAll(ConfigFiles.orphans(app, file, draft, section, membership, mapper));
		for (ConfigFiles.Entry overlay : ConfigFiles.above(app, file, membership)) {
			try {
				for (String p : check.problems(ConfigFiles.applied(app, overlay.id, file, draft, membership, mapper), false)) {
					out.add("with " + overlay.path + ": " + p);
				}
			} catch (Exception e) {
				out.add("with " + overlay.path + ": could not be loaded merged over this draft: " + e.getMessage());
			}
		}
		return out;
	}
}
