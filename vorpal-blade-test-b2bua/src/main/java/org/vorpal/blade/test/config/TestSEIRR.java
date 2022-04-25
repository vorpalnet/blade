package org.vorpal.blade.test.config;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestSEIRR {

	public enum MapType {
		PREFIX, TREE, HASH
	};

	public class RouterConfig {
		public List<Selector> selectors = new LinkedList<>();
	}

	public class Selector {
		public String description;
		public String attribute;
		public String pattern;
		public String expression;
		public Map<String, Translation> translations = new HashMap<>();
	}

	public class Translation {
		public String description;
		public String dest;
		public List<Selector> selectors;
	}

	public static void main(String[] args) {

		TestSEIRR test = new TestSEIRR();
		TestSEIRR.RouterConfig config = test.new RouterConfig();

		Selector to_user = test.new Selector();
		to_user.description = "To user";
		to_user.attribute = "To";
		to_user.pattern = "^(sips?):([^@]+)(?:@(.+))?$";
		to_user.expression = "$2";
		config.selectors.add(to_user);

		Selector remoteIp = test.new Selector();
		remoteIp.description = "Remote IP Address";
		remoteIp.attribute = "Remote-IP";
		remoteIp.pattern = "^(.*)$";
		remoteIp.expression = "$1";
		config.selectors.add(remoteIp);

		Translation t1 = test.new Translation();
		t1.description = "STG CL1 ATT IB";
		t1.dest = "10.173.165.70:5060";
		remoteIp.translations.put("10.173.101.120", t1);

//		  	"description" : "STG CL1 VZB IB",
//		  	"uas" : "10.173.101.121",
//		    "pattern" : ".*",
//		    "addresses" : [ "10.173.165.69:5060" ]

		Translation t2 = test.new Translation();
		t2.description = "STG CL1 VZB IB";
		t2.dest = "sip:10.173.165.69:5060";
		remoteIp.translations.put("10.173.101.121", t2);

//	  	"description" : "STG CL2 ATT IB",
//	  	"uas" : "10.173.101.86",
//	    "pattern" : ".*",
//	    "addresses" : [ "10.173.165.128:5060" ]

		Translation t3 = test.new Translation();
		t3.description = "STG CL2 ATT IB";
		t3.dest = "sip:10.173.165.69:5060";
		remoteIp.translations.put("10.173.101.121", t3);

//		  	"description" : "STG CL2 VZB IB",
//		  	"uas" : "10.173.101.87",
//		    "pattern" : ".*",
//		    "addresses" : [ "10.173.165.127:5060" ]

		Translation t4 = test.new Translation();
		t4.description = "STG CL2 VZB IB";
		t4.dest = "sip:10.173.165.69:5060";
		remoteIp.translations.put("10.173.101.87", t4);

//	  	"description" : "On Net TFN Test",
//	  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
//	    "pattern" : "1990.*",
//	    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]

		Translation t5 = test.new Translation();
		t5.selectors = new LinkedList<>();

		Translation t6 = test.new Translation();
		t6.dest = "10.204.67.59:5060";

		Selector t5Selector = test.new Selector();
		t5Selector.attribute = "Remote-IP";
		t5Selector.pattern = "^(.*)$";
		t5Selector.expression = "$1";
		t5Selector.translations.put("10.87.152.172", t6);
		t5Selector.translations.put("10.87.152.173", t6);
		t5Selector.translations.put("10.204.67.59", t6);
		t5Selector.translations.put("10.204.67.60", t6);

		t5.selectors.add(t5Selector);

		to_user.translations.put("1990", t5);

		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.setSerializationInclusion(Include.NON_NULL);
			mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
			String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
			System.out.println(output);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
