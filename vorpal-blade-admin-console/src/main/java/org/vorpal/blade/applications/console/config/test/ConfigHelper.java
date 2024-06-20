package org.vorpal.blade.applications.console.config.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigHelper {

	private String domainJson;
	private String clusterJson;
	private String serverJson;
	private String jsonSchema;

	public ConfigHelper(String app) {

		Object path;
		Object encoding;

		Path domainPath = Paths.get("config/custom/vorpal/" + app + ".json");
		Path serverPath = Paths.get("config/custom/vorpal/_servers/" + app + ".json");
		Path clusterPath = Paths.get("config/custom/vorpal/_clusters/" + app + ".json");
		Path schemaPath = Paths.get("config/custom/vorpal/_schemas/" + app + ".jschema");

		try {
			String domainJson = Files.readString(domainPath);
//			domainJson = domainJson.replace("\"", "\\\"");
			
			
//			System.out.println(domainJson);

			String jsonSchema = Files.readString(schemaPath);
//			jsonSchema = jsonSchema.replace("\"", "\\\"");
			
//			System.out.println(jsonSchema);

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public String getDomainJson() {
		return domainJson;
	}

	public void setDomainJson(String domainJson) {
		this.domainJson = domainJson;
	}

	public String getClusterJson() {
		return clusterJson;
	}

	public void setClusterJson(String clusterJson) {
		this.clusterJson = clusterJson;
	}

	public String getServerJson() {
		return serverJson;
	}

	public void setServerJson(String serverJson) {
		this.serverJson = serverJson;
	}

	public String getJsonSchema() {
		return jsonSchema;
	}

	public void setJsonSchema(String jsonSchema) {
		this.jsonSchema = jsonSchema;
	}
	
	public Set<String> listFilesUsingFilesList(String dir) throws IOException {
	    try (Stream<Path> stream = Files.list(Paths.get(dir))) {
	        return stream
	          .filter(file -> !Files.isDirectory(file))
	          .map(Path::getFileName)
	          .map(Path::toString)
	          .collect(Collectors.toSet());
	    }
	    
	}

}
