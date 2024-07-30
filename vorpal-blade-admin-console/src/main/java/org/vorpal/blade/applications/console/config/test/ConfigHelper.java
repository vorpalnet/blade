package org.vorpal.blade.applications.console.config.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigHelper {

	private String configType;

	private Path schemaPath;
	private Path domainPath;
	private Path clusterPath;
	private Path serverPath;

	public ConfigHelper(String app, String configType) {

		this.configType = configType;

		schemaPath = Paths.get("config/custom/vorpal/_schemas/" + app + ".jschema");
		domainPath = Paths.get("config/custom/vorpal/" + app + ".json");
		clusterPath = Paths.get("config/custom/vorpal/_clusters/" + app + ".json");
		serverPath = Paths.get("config/custom/vorpal/_servers/" + app + ".json");

	}

	public ConfigHelper(String app) {
		schemaPath = Paths.get("config/custom/vorpal/_schemas/" + app + ".jschema");
		domainPath = Paths.get("config/custom/vorpal/" + app + ".json");
		clusterPath = Paths.get("config/custom/vorpal/_clusters/" + app + ".json");
		serverPath = Paths.get("config/custom/vorpal/_servers/" + app + ".json");

	}

	public String loadJson() throws IOException {
		String value = null;

		switch (configType) {
		case "Domain":
		case "domain":
			if (Files.exists(domainPath)) {
				value = Files.readString(domainPath);
			}
			break;
		case "Cluster":
		case "cluster":
			if (Files.exists(clusterPath)) {
				value = Files.readString(clusterPath);
			}
			break;

		case "Server":
		case "server":
			if (Files.exists(serverPath)) {
				value = Files.readString(domainPath);
			}
			break;
		}

		if (value == null) {
			value = "{}";
		}

		return value;

	}

	public String loadDomainJson() throws IOException {
		if (Files.exists(domainPath)) {
			return Files.readString(domainPath);
		} else {
			return "{}";
		}
	}

	public void saveDomainJson(String json) {
		try {
			Files.writeString(domainPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public String loadClusterJson() throws IOException {

		if (Files.exists(clusterPath)) {
			return Files.readString(clusterPath);
		} else {
			return "{}";
		}

	}

	public void saveClusterJson(String json) {
		try {
			Files.writeString(clusterPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public String loadServerJson() throws IOException {

		if (Files.exists(clusterPath)) {
			return Files.readString(clusterPath);
		} else {
			return "{}";
		}

	}

	public void saveServerJson(String json) {
		try {
			Files.writeString(serverPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
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
