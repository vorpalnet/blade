package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarGenrec2 extends AppRouterConfiguration {

	private static final long serialVersionUID = 1L;

	public FsmarGenrec2() {
		this.setDefaultApplication("proxy-goober");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getPrevious("null").getTrigger("REFER").createTransition("transfer");

//		this.getPrevious("null").getTrigger("INVITE").createTransition("genrec2");
//		this.getPrevious("genrec2").getTrigger("INVITE").createTransition("proxy-registrar");
		
		this.getPrevious("null").getTrigger("INVITE").createTransition("genrec2").addComparison("From", "user", "01983217113");
		
		

	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new FsmarGenrec2();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
