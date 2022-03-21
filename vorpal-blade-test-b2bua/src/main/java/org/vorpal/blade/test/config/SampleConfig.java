/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.test.config;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;

/**
 * @author Jeff McDonald
 *
 */
@Configuration
public class SampleConfig {

	private List<SampleACL> listOfACLs;
	private Map<String, SampleACL> mapOfACLs;
	private String version;
	private SampleACL defaultACL;

	public SampleConfig() {
		mapOfACLs = new HashMap<String, SampleACL>();
		mapOfACLs.put("entry1", new SampleACL("attribute1", "set by domain"));
		mapOfACLs.put("entry2", new SampleACL("attribute2", "set by domain"));
		mapOfACLs.put("entry3", new SampleACL("attribute3", "set by domain"));

		listOfACLs = new LinkedList<SampleACL>();
		listOfACLs.add(new SampleACL("attribute1", "set by domain"));
		listOfACLs.add(new SampleACL("attribute2", "set by domain"));
		listOfACLs.add(new SampleACL("attribute3", "set by domain"));

		version = "1.0";
		defaultACL = new SampleACL("attribute0", "set by domain");
	}

	/**
	 * @return the listOfACLs
	 */
	public List<SampleACL> getListOfACLs() {
		return listOfACLs;
	}

	/**
	 * @param listOfACLs the listOfACLs to set
	 */
	public void setListOfACLs(List<SampleACL> listOfACLs) {
		this.listOfACLs = listOfACLs;
	}

	/**
	 * @return the mapOfACLs
	 */
	public Map<String, SampleACL> getMapOfACLs() {
		return mapOfACLs;
	}

	/**
	 * @param mapOfACLs the mapOfACLs to set
	 */
	public void setMapOfACLs(Map<String, SampleACL> mapOfACLs) {
		this.mapOfACLs = mapOfACLs;
	}

	/**
	 * @return the version
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * @param version the version to set
	 */
	public void setVersion(String version) {
		this.version = version;
	}

	/**
	 * @return the defaultACL
	 */
	public SampleACL getDefaultACL() {
		return defaultACL;
	}

	/**
	 * @param defaultACL the defaultACL to set
	 */
	public void setDefaultACL(SampleACL defaultACL) {
		this.defaultACL = defaultACL;
	}

	/**
	 * @param args Input parameters
	 * @throws Exception Who cares?
	 */
	public static void main(String[] args) throws Exception {

		SampleConfig sampleConfig = new SampleConfig();

		ObjectMapper jacksonObjectMapper = new ObjectMapper();
		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(jacksonObjectMapper);
		JsonSchema schema = schemaGen.generateSchema(SampleConfig.class);
		String schemaString = jacksonObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		System.out.println(schemaString);

//		ObjectMapper mapper = new ObjectMapper();
//
//		mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, sampleConfig);
//
//		JsonSchema schema = mapper.generateJsonSchema(settings.getClass());
//		schema.getSchemaNode().put("title", settings.getClass().getSimpleName());
//
//		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(schemaFilename), schema);

	}

}
