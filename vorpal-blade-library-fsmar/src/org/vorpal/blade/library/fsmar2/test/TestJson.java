package org.vorpal.blade.library.fsmar2.test;

import org.vorpal.blade.library.fsmar2.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestJson {

	public static void main(String[] args) throws JsonProcessingException {

		Configuration configuration = new Configuration();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
