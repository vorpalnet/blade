package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class SXMFsmar extends AppRouterConfiguration {

	public SXMFsmar() {
//		this.setDefaultApplication("goober");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("REFER").createTransition("transfer");
		this.getPrevious("null").getTrigger("INVITE").createTransition("transfer");
		this.getPrevious("transfer").getTrigger("INVITE").createTransition("proxy-router");

	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new SXMFsmar();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
