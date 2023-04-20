package org.vorpal.blade.test.config;

import java.util.HashMap;
import java.util.LinkedList;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestSEIRR4 {

	public enum MapType {
		PREFIX, TREE, HASH
	};

	@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
	public class Selector {
		public String id;
		public String description;
		public String attribute;
		public String pattern;
		public String expression;
	}

	public class TranslationsMap {
		public String description;
		public Selector selector;
		public HashMap<String, String> translations;
	}

	public class RouterConfig {
//		public HashMap<String, Selector> selectors = new HashMap<>();
		public LinkedList<Selector> selectors = new LinkedList<>();
//		public HashMap<String, Translation> translations = new HashMap<>();
		public LinkedList<Translation> translations = new LinkedList<>();
		public LinkedList<TranslationsMap> maps = new LinkedList<>();
	}

	@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
	public class Translation {
		public String id;
		public LinkedList<TranslationsMap> list;
		public String dest;
	}

	public static void main(String[] args) {

//	  	"description" : "On Net TFN Test",
//	  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
//	    "pattern" : "1990.*",
//	    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]

		TestSEIRR4 test = new TestSEIRR4();
		TestSEIRR4.RouterConfig config = test.new RouterConfig();

		TranslationsMap map1 = test.new TranslationsMap();
		map1.description = "dialed number prefix plan";

		Selector toUser = test.new Selector();
		toUser.id = "to-user";
		toUser.attribute = "To";
		toUser.pattern = "^(sips?):([^@]+)(?:@(.+))?$";
		toUser.expression = "$2";
		config.selectors.add(toUser);

		Selector remoteIp = test.new Selector();
		remoteIp.id = "remote-ip";
		remoteIp.attribute = "Remote-IP";
		remoteIp.pattern = "^(.*)$";
		remoteIp.expression = "$1";
		config.selectors.add(remoteIp);

		Translation t1 = test.new Translation();
		t1.id = "t1";
		TranslationsMap map2 = test.new TranslationsMap();
		map2.description = "remote ip address plan";

		t1.list = new LinkedList<>();
		t1.list.add(map2);
//		config.translations.put("t1", t1);
		config.translations.add(t1);

		Translation t2 = test.new Translation();
		t2.id = "t2";
		t2.dest = "sip:10.204.67.59:5060";
//		config.translations.put("t2", t2);
		config.translations.add(t2);

		map1.translations = new HashMap<String, String>();
//		map1.selector = "to-user";
		map1.selector = toUser;
		map1.translations.put("1990", "t1");

		map2.translations = new HashMap<>();
//		map2.selector = "remote-ip";
		map2.selector = remoteIp;

		map2.translations = new HashMap<>();

		map2.translations.put("10.87.152.172", "t2");
		map2.translations.put("10.87.152.173", "t2");
		map2.translations.put("10.204.67.59", "t2");
		map2.translations.put("10.204.67.60", "t2");

		config.maps.add(map1);

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
