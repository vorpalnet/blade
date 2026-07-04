package org.vorpal.blade.applications.files;

import java.util.Arrays;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/files.json` on first deployment when no
/// operator-supplied file is present.
///
/// The registry still acts as a whitelist — a path not listed here cannot be
/// read or written — but the sample ships pre-populated with the standard
/// hand-maintained config files of a stock OCCAS domain. An administrator
/// edits this list to add or remove files for their site.
public class FilesSettingsSample extends FilesSettings {
	private static final long serialVersionUID = 1L;

	public FilesSettingsSample() {
		this.files = Arrays.asList(
				new EditableFile()
						.setLabel("Domain Config (config.xml)")
						.setPath("config/config.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ADMIN),
				new EditableFile()
						.setLabel("Coherence Cluster (defaultCoherenceCluster-coherence.xml)")
						.setPath("config/coherence/defaultCoherenceCluster-coherence.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("Coherence Default Cache Config (Coherence-Default.xml)")
						.setPath("config/coherence/Coherence-Default/Coherence-Default.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("Coherence Custom Cache Config (Custom-Default.xml)")
						.setPath("config/coherence/Coherence-Default/Custom-Default.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("Server Debug Config (serverdebug.xml)")
						.setPath("config/custom/serverdebug.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.BOTH),
				new EditableFile()
						.setLabel("Coherence Config (coherence.xml)")
						.setPath("config/custom/coherence.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("Application Router Config (approuter.xml)")
						.setPath("config/custom/approuter.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("SIP Server Config (sipserver.xml)")
						.setPath("config/custom/sipserver.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ENGINE),
				new EditableFile()
						.setLabel("Lifecycle Config (lifecycle-config.xml)")
						.setPath("config/lifecycle-config.xml")
						.setType(FileType.XML)
						.setConsumer(ConsumerTier.ADMIN),
				new EditableFile()
						.setLabel("Logging Properties")
						.setPath("config/custom/logging.properties")
						.setType(FileType.PROPERTIES)
						.setConsumer(ConsumerTier.BOTH));
	}
}
