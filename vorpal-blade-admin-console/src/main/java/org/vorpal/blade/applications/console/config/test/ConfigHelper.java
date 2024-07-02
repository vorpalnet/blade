package org.vorpal.blade.applications.console.config.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigHelper {

	private Path schemaPath;
	private Path domainPath;
	private Path clusterPath;
	private Path serverPath;

//	private String domainJson;
//	private String clusterJson;
//	private String serverJson;
//	private String jsonSchema;

	public ConfigHelper(String app) {
		schemaPath = Paths.get("config/custom/vorpal/_schemas/" + app + ".jschema");
		domainPath = Paths.get("config/custom/vorpal/" + app + ".json");
		clusterPath = Paths.get("config/custom/vorpal/_clusters/" + app + ".json");
		serverPath = Paths.get("config/custom/vorpal/_servers/" + app + ".json");

	}

	public String loadDomainJson() throws IOException {
		return Files.readString(domainPath);
	}

	public void saveDomainJson(String domainJson) {
//		this.domainJson = domainJson;
	}

	public String loadClusterJson() throws IOException {
		return Files.readString(clusterPath);
	}

	public void saveClusterJson(String clusterJson) {
//		this.clusterJson = clusterJson;
	}

	public String loadServerJson() throws IOException {
		return Files.readString(serverPath);
	}

	public void saveServerJson(String serverJson) {
//		this.serverJson = serverJson;
	}

	public String loadJsonSchema() throws IOException {
		return Files.readString(schemaPath);
	}

	public void saveJsonSchema(String jsonSchema) {
//		this.jsonSchema = jsonSchema;
	}

	public Set<String> listFilesUsingFilesList(String dir) throws IOException {
		try (Stream<Path> stream = Files.list(Paths.get(dir))) {
			return stream.filter(file -> !Files.isDirectory(file)).map(Path::getFileName).map(Path::toString)
					.collect(Collectors.toSet());
		}

	}

}
