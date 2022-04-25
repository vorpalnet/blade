package org.vorpal.blade.services.router.junk;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import inet.ipaddr.Address;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

public class TestRouterSettings1 {

	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<Translation> translations = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();

//	public enum MapType {
//		PREFIX, TREE, HASH
//	};

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
		public Map<String, Translation> translations;

		public String lookup(SipServletRequest request) {
			String value = null;

			String header = null;
			String key = null;
			Translation translation = null;

			switch (selector.attribute) {
			case "Request-URI":
				header = request.getRequestURI().toString();
				break;
			case "Remote-IP":
				header = request.getRemoteAddr();
				break;
			default:
				header = request.getHeader(selector.attribute);
			}

			if (header != null) {
				Pattern p = Pattern.compile(selector.pattern);
				Matcher matcher = p.matcher(header);
				key = matcher.replaceAll(selector.expression);

				if (key != null) {
					translation = translations.get(key);

					if (translation != null) {
						// found it!

						if (translation.dest != null) {
							// found the SIP URI, done!
							value = translation.dest;
						} else {

							for (TranslationsMap map : translation.list) {

								value = map.lookup(request);
								if (value != null) {
									break;
								}

							}

						}

					}
				}
			}

			return value;
		}

	}

	@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
	public class Translation {
		public String id;
		public LinkedList<TranslationsMap> list;
		public String dest;
	}

	public class PrefixMap extends PatriciaTrie<Translation> implements Map<String, Translation> {

	}

	public class StringMap extends TreeMap<String, Translation> implements Map<String, Translation> {

	}

	public class IPv4Map implements Map<String, Translation> {

		AddressTrieMap<Address, Translation> map = new AddressTrieMap<>(new IPv4AddressAssociativeTrie());

		@Override
		public int size() {
			return map.size();
		}

		@Override
		public boolean isEmpty() {
			return map.isEmpty();
		}

		@Override
		public boolean containsKey(Object key) {
			return map.containsKey(new IPAddressString((String) key).getAddress().toIPv4());
		}

		@Override
		public boolean containsValue(Object value) {
			return map.containsValue(value);
		}

		@Override
		public Translation get(Object key) {
			return map.get(new IPAddressString((String) key).getAddress().toIPv4());
		}

		@Override
		public Translation put(String key, Translation value) {
			return map.put(new IPAddressString((String) key).getAddress().toIPv4(), value);
		}

		@Override
		public Translation remove(Object key) {
			return map.remove(new IPAddressString((String) key).getAddress().toIPv4());
		}

		@Override
		public void putAll(Map<? extends String, ? extends Translation> m) {

			try {
				throw new Exception("Unimplemented method");
			} catch (Exception e) {
				e.printStackTrace();
			}

		}

		@Override
		public void clear() {
			map.clear();
		}

		@Override
		public Set<String> keySet() {

			try {
				throw new Exception("Unimplemented method");
			} catch (Exception e) {
				e.printStackTrace();
			}

			return null;
		}

		@Override
		public Collection<Translation> values() {
			return map.values();
		}

		@Override
		public Set<Entry<String, Translation>> entrySet() {

			try {
				throw new Exception("Unimplemented method");
			} catch (Exception e) {
				e.printStackTrace();
			}

			return null;
		}

	}

//	public class IPv6Map extends AddressTrieMap<Address, Translation> implements TranslationsLookup{
//		public IPv6Map() {
//			super(new IPv6AddressAssociativeTrie());
//		}
//
//		@Override
//		public String lookup(SipServletRequest request) {
//			// TODO Auto-generated method stub
//			return null;
//		}
//	}

	public String lookup(SipServletRequest request) {
		String value = null;

		Selector selector;
		for (TranslationsMap map : maps) {
			Translation translation = null;
			String header = null;
			String key = null;

			selector = map.selector;
			switch (selector.attribute) {
			case "Request-URI":
				header = request.getRequestURI().toString();
				break;
			case "Remote-IP":
				header = request.getRemoteAddr();
				break;
			default:
				header = request.getHeader(selector.attribute);
			}

			if (header != null) {
				Pattern p = Pattern.compile(selector.pattern);
				Matcher matcher = p.matcher(header);
				key = matcher.replaceAll(selector.expression);

				if (key != null) {
					translation = map.translations.get(key);

					if (value != null) {
						// found it!

						if (translation.dest != null) {
							value = translation.dest;
						} else {
							// have to lookup even further

						}

						break;
					}
				}
			}
		}

		return value;
	}

	public TestRouterSettings1() {

//	  	"description" : "On Net TFN Test",
//	  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
//	    "pattern" : "1990.*",
//	    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]

//		TestRouterSettings1 config = new TestRouterSettings1();
//		TestRouterSettings1.RouterConfig config = test.new RouterConfig();

		TranslationsMap map1 = new TranslationsMap();
		map1.description = "dialed number prefix plan";

		Selector toUser = new Selector();
		toUser.id = "to-user";
		toUser.attribute = "To";
		toUser.pattern = "^(sips?):([^@]+)(?:@(.+))?$";
		toUser.expression = "$2";
		this.selectors.add(toUser);

		Selector remoteIp = new Selector();
		remoteIp.id = "remote-ip";
		remoteIp.attribute = "Remote-IP";
		remoteIp.pattern = "^(.*)$";
		remoteIp.expression = "$1";
		this.selectors.add(remoteIp);

		Translation t1 = new Translation();
		t1.id = "t1";
		TranslationsMap map2 = new TranslationsMap();
		map2.description = "remote ip address plan";

		t1.list = new LinkedList<>();
		t1.list.add(map2);
//		config.translations.put("t1", t1);
		this.translations.add(t1);

		Translation t2 = new Translation();
		t2.id = "t2";
		t2.dest = "sip:10.204.67.59:5060";
//		config.translations.put("t2", t2);
		this.translations.add(t2);

//		map1.translations = new HashMap<String, String>();
		map1.translations = new PrefixMap();

//		map1.selector = "to-user";
		map1.selector = toUser;
//		map1.translations.put("1990", "t1");
		map1.translations.put("1990", t1);

//		map2.translations = new HashMap<>();
		map2.translations = new IPv4Map();
//		map2.selector = "remote-ip";
		map2.selector = remoteIp;

		map2.translations = new HashMap<>();

		map2.translations.put("10.87.152.172", t2);
		map2.translations.put("10.87.152.173", t2);
		map2.translations.put("10.204.67.59", t2);
		map2.translations.put("10.204.67.60", t2);

		this.maps.add(map1);

	}

	public void test1() {

		String header = "sip:bob@vorpal.net";
		String pattern = "^(sips?):([^@]+)(?:@(.+))?$";
		String expression = "$1-$2";

		Pattern p = Pattern.compile(pattern);
		Matcher matcher = p.matcher(header);
		String result = matcher.replaceAll(expression);

		System.out.println("Result: " + result);

	}

	public static void main(String[] args) {

//		TestRouterSettings1 test = new TestRouterSettings1();
//		test.test1();

		IPv4AddressAssociativeTrie ipv4aat = new IPv4AddressAssociativeTrie();
		AddressTrieMap<Address, String> trieMap = new AddressTrieMap<Address, String>(ipv4aat);

		IPv4Address net1 = new IPAddressString("192.168.1.0/24").getAddress().toIPv4();
		IPv4Address addr1 = new IPAddressString("192.168.1.1").getAddress().toIPv4();
		IPv4Address addr2 = new IPAddressString("192.168.2.1").getAddress().toIPv4();

		trieMap.put(net1, "ALLOW");
		trieMap.put(addr1, "ALLOW");
		trieMap.put(addr2, "ALLOW");

		TestRouterSettings1 config = new TestRouterSettings1();

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
