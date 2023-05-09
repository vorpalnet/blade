package org.vorpal.blade.library.fsmar2;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Config1 extends AppRouterConfiguration {

	private static final long serialVersionUID = 1L;

	public Config1() {

		this.setDefaultApplication("proxy-registrar");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");

		this.getPrevious("null").getTrigger("INVITE").createTransition("keep-alive");
		this.getPrevious("keep-alive").getTrigger("INVITE").createTransition("load-balancer");
		this.getPrevious("load-balancer").getTrigger("INVITE").createTransition("proxy-registrar");

		
		
		
		
	}

	public static void main(String[] args) throws JsonProcessingException {

		AppRouterConfiguration configuration = new Config1();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
