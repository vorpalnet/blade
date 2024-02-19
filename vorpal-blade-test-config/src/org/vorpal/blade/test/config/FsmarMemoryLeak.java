package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarMemoryLeak extends AppRouterConfiguration {

	public FsmarMemoryLeak() {
		this.setDefaultApplication("proxy-goober");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		
		
		this.getPrevious("null").getTrigger("INVITE").createTransition("test-b2bua");		
		this.getPrevious("test-b2bua").getTrigger("INVITE").createTransition("queue");
		this.getPrevious("queue").getTrigger("INVITE").createTransition("transfer");
		this.getPrevious("transfer").getTrigger("INVITE").createTransition("proxy-registrar");
		
		
//		this.getPrevious("onnet").getTrigger("INVITE").createTransition("transfer");		
//		this.getPrevious("transfer").getTrigger("INVITE").createTransition("test-uas");
		
	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new FsmarMemoryLeak();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
