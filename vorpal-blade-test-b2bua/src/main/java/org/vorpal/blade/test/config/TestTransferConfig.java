package org.vorpal.blade.test.config;

import java.util.ArrayList;

import org.vorpal.blade.framework.config.Condition;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestTransferConfig {

	public class Configuration {

		public Boolean transferAllRequests;
		public Condition featureEnable = new Condition();
		public Condition blindTransfer = new Condition();
		public Condition assistedTransfer = new Condition();
		public Condition mediaTransfer = new Condition();

		public ArrayList<String> preserveInviteHeaders;

	}

	public static void main(String[] args) throws JsonProcessingException {

		TestTransferConfig j = new TestTransferConfig();
		Configuration config = j.new Configuration();

		config.transferAllRequests = false;
		config.featureEnable.addComparison("OSM-Features", "includes", "transfer");

		config.blindTransfer.addComparison("Request-URI", "txfer", "blind");
		config.assistedTransfer.addComparison("Request-URI", "txfer", "assisted");
		config.mediaTransfer.addComparison("Request-URI", "txfer", "media");

		config.preserveInviteHeaders = new ArrayList<>();
		config.preserveInviteHeaders.add("Cisco-Gucid");
		config.preserveInviteHeaders.add("User-to-User");

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
		System.out.println(output);

	}

}
