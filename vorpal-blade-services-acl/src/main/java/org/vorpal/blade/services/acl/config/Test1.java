/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.services.acl.config;

import java.util.Arrays;
import java.util.function.Function;

import inet.ipaddr.Address;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.AddressTrie;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;
import inet.ipaddr.ipv4.IPv4AddressTrie;
import inet.ipaddr.ipv4.IPv4AddressTrie.IPv4TrieNode;
import inet.ipaddr.ipv6.IPv6AddressTrie;

/**
 * @author Jeff McDonald
 *
 */
public class Test1 {

	static <T extends AddressTrie<A>, A extends Address> T populateTree(T trie, String addrStrs[],
			Function<String, A> creator) {
		for (String addrStr : addrStrs) {
			A addr = creator.apply(addrStr);
			trie.add(addr);
		}
		return trie;
	}

	public static void test1() {
		// TODO Auto-generated method stub

		IPAddress addr1 = new IPAddressString("192.168.1.1").getAddress();
		IPAddress net1 = new IPAddressString("192.168.1.0/24").getAddress();

		System.out.println(net1.contains(addr1));

		String ipv6Addresses[] = { "1::ffff:2:3:5", "1::ffff:2:3:4", "1::ffff:2:3:6", "1::ffff:2:3:12", "1::ffff:aa:3:4",
				"1::ff:aa:3:4", "1::ff:aa:3:12", "bb::ffff:2:3:6", "bb::ffff:2:3:12", "bb::ffff:2:3:22", "bb::ffff:2:3:32",
				"bb::ffff:2:3:42", "bb::ffff:2:3:43", };

		IPv6AddressTrie ipv6Trie = Test1.populateTree(new IPv6AddressTrie(), ipv6Addresses,
				str -> new IPAddressString(str).getAddress().toIPv6());
		System.out.println(ipv6Trie);

		String ipv4Addresses[] = { "192.168.1.0/24", "192.168.2.1", "192.168.3.1-3" };

		IPv4AddressTrie ipv4Trie = Test1.populateTree(new IPv4AddressTrie(), ipv4Addresses,
				str -> new IPAddressString(str).getAddress().toIPv4());

		IPv4Address addr2 = new IPAddressString("192.168.1.2").getAddress().toIPv4();

//		IPv4AddressSeqRange range1 = new IPv4AddressSeqRange(new IPAddressString("192.168.2.1").getAddress().toIPv4(),
//				new IPAddressString("192.168.2.3").getAddress().toIPv4());
//		ipv4Trie.add(range1);

		// ipv4Trie.add(addr2);

		System.out.println(ipv4Trie);

		System.out.println(ipv4Trie.contains(addr2));

		IPv4TrieNode containingNode = ipv4Trie.elementsContaining(addr2);
		System.out.println("containing node: " + containingNode.getKey());

		IPv4TrieNode containingNode2 = ipv4Trie
				.elementsContaining(new IPAddressString("192.168.2.2").getAddress().toIPv4());

		if (containingNode2 != null) {
			System.out.println("containing range: " + containingNode.getKey());
		} else {
			System.out.println("Not found!");
		}

//		System.out.println(
//				"For address " + addr2 + " containing block is " + containingNode.toTreeString(false, false) + "\n");

	}

	public static void test2() {

		try {
			IPAddress range = new IPAddressString("192.168.1.1-9").toAddress();
			IPAddress addr1 = new IPAddressString("192.168.1.1-9").toAddress();
			System.out.println("range contains " + addr1 + ": " + range.contains(addr1));

			System.out.println("addr1.isMultiple: " + addr1.isMultiple());
			System.out.println("toAllStringCollection: " + Arrays.toString(addr1.toAllStringCollection().toStrings()));

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void test3() {

		try {
			IPAddress range = new IPAddressString("192.168.1.1-9").toAddress();

			// range.toSequentialRange().contains(range);

			IPAddress addr1 = new IPAddressString("192.168.1.1-9").toAddress();
			System.out.println("range contains " + addr1 + ": " + range.contains(addr1));

			System.out.println("addr1.isMultiple: " + addr1.isMultiple());
			System.out.println("toAllStringCollection: " + Arrays.toString(addr1.toAllStringCollection().toStrings()));

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void test4() {

		try {

			IPv4AddressAssociativeTrie ipv4aat = new IPv4AddressAssociativeTrie();
			AddressTrieMap<Address, String> trieMap = new AddressTrieMap<Address, String>(ipv4aat);

			IPv4Address net1 = new IPAddressString("192.168.1.0/24").getAddress().toIPv4();
			IPv4Address addr1 = new IPAddressString("192.168.1.1").getAddress().toIPv4();
			IPv4Address addr2 = new IPAddressString("192.168.2.1").getAddress().toIPv4();

			trieMap.put(net1, "ALLOW");
			trieMap.put(addr1, "ALLOW");
			trieMap.put(addr2, "ALLOW");
			
			System.out.println(net1 + ": "+trieMap.get(net1));
			System.out.println(addr1 + ": "+trieMap.get(addr1));
			System.out.println(addr2 + ": "+trieMap.get(addr2));
			
			
			

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
//		Test1.test1();
//		Test1.test2();
//		Test1.test3();
		Test1.test4();
	}

}
