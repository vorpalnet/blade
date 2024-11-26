package org.vorpal.blade.test.config;

import org.vorpal.blade.library.fsmar2.AppRouterConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class FsmarSampleDeloitte extends AppRouterConfiguration {

	public FsmarSampleDeloitte() {
		this.setDefaultApplication("junk");

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
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");

		this.getPrevious("null").getTrigger("INVITE").createTransition("transfer");
		this.getPrevious("transfer").getTrigger("INVITE").createTransition("tpcc");
		
		


	}

	public static void main(String[] args) throws JsonProcessingException {
		AppRouterConfiguration configuration = new FsmarSampleDeloitte();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
		System.out.println(output);
	}

}
