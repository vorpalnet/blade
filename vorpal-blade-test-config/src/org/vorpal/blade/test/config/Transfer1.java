package org.vorpal.blade.test.config;

import org.vorpal.blade.framework.transfer.TransferCondition;
import org.vorpal.blade.services.transfer.TransferSettings;
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Transfer1 extends TransferSettings {

	public Transfer1() {
//		this.setTransferAllRequests(false);
//		this.setDefaultTransferStyle(TransferSettings.TransferStyle.blind);
//		this.getPreserveInviteHeaders().add("Cisco-Gucid");
//		this.getPreserveInviteHeaders().add("User-to-User");


//		TransferCondition tc1 = new TransferCondition();
//		tc1.setStyle(TransferStyle.blind);
//		tc1.getCondition().addComparison("OSM-Features", "includes", "transfer");
//		this.getTransferConditions().add(tc1);
//
//		TransferCondition tc2 = new TransferCondition();
//		tc2.setStyle(TransferStyle.blind);
//		tc2.getCondition().addComparison("To", "matches", ".*sip:1990.*");
//		this.getTransferConditions().add(tc2);
//		
//		TransferCondition tc3 = new TransferCondition();
//		tc3.setStyle(TransferStyle.blind);
//		tc3.getCondition().addComparison("To", "matches", ".*sip:1992.*");
//		this.getTransferConditions().add(tc3);		
		
		
		TransferCondition tc1 = new TransferCondition();
		
		
		
		
		
	}

	public static void main(String[] args) throws JsonProcessingException {

		Transfer1 configuration = new Transfer1();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
