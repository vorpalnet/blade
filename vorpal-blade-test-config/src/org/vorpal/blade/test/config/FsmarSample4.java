package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarSample4 extends AppRouterConfiguration {

	public FsmarSample4() {
		this.setDefaultApplication("proxy-goober");

//		OPT01: null, OPTIONS, NO_ROUTE, options 
//		INV02: null, INVITE|From(.*sip:acmeSrc.*)|Contact(.*10.173.172.24.*), TERMINATING(To), siprec
//		INV03: null, INVITE|From(.*sip:Genesys.*)|Contact(.*10.173.172.24.*), TERMINATING(To), genrec
//		INV04: null, INVITE|Contact(.*10.173.172.6.*), TERMINATING(To), mediahub
//		INV05: siprec, INVITE(sip:nice), TERMINATING(To), nice
//		INV06: siprec, INVITE(sip:qfiniti), TERMINATING(To), qfiniti
//		INV07: siprec, INVITE(sip:hold), TERMINATING(To), hold
//		INV08: null, INVITE|Contact(.*10.173.187.199.*), TERMINATING(To), mediahub
//		INV09: null, INVITE|From(.*sip:acmeSrc.*)|Contact(.*10.173.187.47.*), TERMINATING(To), siprec

//		String header, operator, condition;

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");

		this.getPrevious("null").getTrigger("INVITE").createTransition("siprec2") //
				.condition.addComparison("From", "address", ".*sip:acmeSrc.*");

		this.getPrevious("null").getTrigger("INVITE").createTransition("genrec2") //
				.condition.addComparison("From", "address", ".*sip:Genesys.*");
		
		this.getPrevious("null").getTrigger("INVITE").createTransition("genrec2") //
		.condition.addComparison("Contact", "host", "10.173.172.6");	
		
		this.getPrevious("siprec").getTrigger("INVITE").createTransition("nice") //
		.condition.addComparison("Request-URI", "user", "nice");
		
		this.getPrevious("siprec").getTrigger("INVITE").createTransition("qfiniti") //
		.condition.addComparison("Request-URI", "user", "qfiniti");
		
		this.getPrevious("siprec").getTrigger("INVITE").createTransition("hold") //
		.condition.addComparison("Request-URI", "user", "hold");
		
		this.getPrevious("null").getTrigger("INVITE").createTransition("genrec2") //
		.condition.addComparison("Contact", "host", "10.173.172.6");
		


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
