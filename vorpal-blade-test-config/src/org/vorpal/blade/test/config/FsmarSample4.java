package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarSample4 extends AppRouterConfiguration {

	public FsmarSample4() {
		this.setDefaultApplication("proxy-goober");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getPrevious("null").getTrigger("INVITE").createTransition("proxy-registrar");

//		this.getPrevious("test-uac").getTrigger("INVITE").createTransition("mediahub2");
//		this.getPrevious("mediahub2").getTrigger("INVITE").createTransition("test-uas");
		
		this.getPrevious("test-uac").getTrigger("INVITE").createTransition("genrec2");
		this.getPrevious("genrec2").getTrigger("INVITE").createTransition("test-uas");
		
		this.getPrevious("proxy-registrar").getTrigger("INVITE").createTransition("failover");
		
		
//		this.getPrevious("onnet").getTrigger("INVITE").createTransition("transfer");		
//		this.getPrevious("transfer").getTrigger("INVITE").createTransition("test-uas");
		
	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new FsmarSample4();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
