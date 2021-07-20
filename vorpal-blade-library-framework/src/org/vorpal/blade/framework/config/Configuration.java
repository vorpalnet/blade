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

package org.vorpal.blade.framework.config;

import java.io.IOException;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.schema.JsonSchema;

public class Configuration implements ConfigurationMXBean {
	private ObjectMapper mapper;
	public Object data;
	private Class<?> clazz;

	public Configuration(Class<?> clazz, Object data) {
		this.clazz = clazz;
		this.data = data;
		mapper = new ObjectMapper();
		mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);
	}

	@Override
	public String getData() {
		String strData = null;

		try {
			strData = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return strData;

	}

	@Override
	public void setData(String json) {

		try {
			System.out.println("Updating configuration for " + clazz.getName());
			data = mapper.readValue(json, clazz);
		} catch (IOException e) {
			ConfigurationManager.sipLogger.severe(e);
		}

	}

	@Override
	public String getSchema() {
		String strSchema = null;
		JsonSchema schema;

		try {
			schema = mapper.generateJsonSchema(data.getClass());
			strSchema = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return strSchema;
	}

}
