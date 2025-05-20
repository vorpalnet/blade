package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarHold extends AppRouterConfiguration {
	private static final long serialVersionUID = 1L;

	public FsmarHold() {
		// this.setDefaultApplication("none");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("REFER").createTransition("transfer");
		this.getPrevious("null").getTrigger("INVITE").createTransition("transfer");
		this.getPrevious("transfer").getTrigger("INVITE").createTransition("hold");

	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new FsmarHold();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
