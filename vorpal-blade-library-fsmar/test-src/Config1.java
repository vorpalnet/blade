import org.vorpal.blade.library.fsmar2.Configuration;
import org.vorpal.blade.library.fsmar2.Transition;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Config1 extends Configuration {

	public Config1() {

		this.setDefaultApplication("mediarouter");

		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");

		Transition inv02 = this.getPrevious("null").getTrigger("INVITE").createTransition("siprec");
		inv02.addComparison("From", "matches", ".*sip:acmeSrc.*");
		inv02.addComparison("Contact", "matches", "*.10.173.172.24.*");
		inv02.setTerminating("To");
		
		
		Transition inv03 = this.getPrevious("null").getTrigger("INVITE").createTransition("genrec");
		inv03.addComparison("From", "matches", ".*sip:Genesys.*");
		inv03.addComparison("Contact", "matches", "*.10.173.172.24.*");
		inv03.setTerminating("To");
		
		Transition inv04 = this.getPrevious("null").getTrigger("INVITE").createTransition("mediahub");
		inv04.addComparison("Contact", "matches", "*.10.173.172.6.*");
		inv04.setTerminating("To");
				
	}

	public static void main(String[] args) throws JsonProcessingException {

		Configuration configuration = new Config1();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
