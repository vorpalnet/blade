package org.vorpal.blade.framework.v3.configuration.templates;

import javax.management.MXBean;

/// The engine side of pushing `_templates/` files: what a server holds, and
/// writing one. Registered by [TemplatesControl] in every BLADE application;
/// the Configurator uses one per server, since all of a server's applications
/// read the same `config/custom/vorpal/_templates/`.
@MXBean
public interface TemplatesMXBean {

	/// CRC-32 (hex) of the template at `path` (relative to `_templates/`) on
	/// this server's disk, or null when it has none. Equal to the admin's own
	/// checksum when the disk is shared, so the push skips it.
	String checksum(String path);

	/// Writes `content` as the template at `path` (relative to `_templates/`),
	/// replacing the file atomically, so a call reading it sees the old text
	/// or the new, never a part. Refuses a path outside `_templates/`.
	void write(String path, String content);
}
