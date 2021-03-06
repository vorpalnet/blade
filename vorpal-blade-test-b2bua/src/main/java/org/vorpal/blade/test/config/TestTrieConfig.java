package org.vorpal.blade.test.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.commons.collections4.trie.PatriciaTrie;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestTrieConfig {

	public PatriciaTrie<String> trie = new PatriciaTrie<>(new HashMap<String, String>());

	public String lookupPrefix(String user) {
		String value = null;

		Iterator<Entry<String, String>> itr = trie.entrySet().iterator();
		Entry<String, String> entry = null;
		Entry<String, String> previous = null;

		while (itr.hasNext()) {
			previous = entry;
			entry = itr.next();

			if (user.startsWith(entry.getKey())) {
				value = entry.getValue();
			}

		}

		return value;

	}

	public static void main(String[] args) {

		TestTrieConfig config = new TestTrieConfig();

		config.trie.put("2", "Country-2");
		config.trie.put("1", "Country-1");
		config.trie.put("1777", "Area-777");
		config.trie.put("1888", "Area-888");
		config.trie.put("1999", "Area-999");
		config.trie.put("1888444", "NPA-444");
		config.trie.put("1888555", "NPA-555");
		config.trie.put("1888666", "NPA-666");

		config.trie.put("18885551233", "NXX-1233");
		config.trie.put("18885551234", "NXX-1234");
		config.trie.put("18885551235", "NXX-1235");

		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.setSerializationInclusion(Include.NON_NULL);
			mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
			String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
			System.out.println(output);
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("lookupPrefix1 18885551234: " + config.lookupPrefix("18885551234"));
		System.out.println("lookupPrefix1 18885551230: " + config.lookupPrefix("18885551230"));
		System.out.println("lookupPrefix1 18885551200: " + config.lookupPrefix("18885551200"));
		System.out.println("lookupPrefix1 18885551000: " + config.lookupPrefix("18885551000"));
		System.out.println("lookupPrefix1 18885550000: " + config.lookupPrefix("18885550000"));
		System.out.println("lookupPrefix1 18885500000: " + config.lookupPrefix("18885500000"));
		System.out.println("lookupPrefix1 18885000000: " + config.lookupPrefix("18885000000"));
		System.out.println("lookupPrefix1 18880000000: " + config.lookupPrefix("18880000000"));
		System.out.println("lookupPrefix1 18800000000: " + config.lookupPrefix("18800000000"));
		System.out.println("lookupPrefix1 18000000000: " + config.lookupPrefix("18000000000"));
		System.out.println("lookupPrefix1 10000000000: " + config.lookupPrefix("10000000000"));
		System.out.println("lookupPrefix1 00000000000: " + config.lookupPrefix("00000000000"));

	}

}
