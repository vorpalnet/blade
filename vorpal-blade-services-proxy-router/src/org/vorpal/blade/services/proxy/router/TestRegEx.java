package org.vorpal.blade.services.proxy.router;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRegEx {

	public static void main(String[] args) {

		String key = null;

// https://www.regextester.com/97589		
// https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html		
// https://realtimecommunication.wordpress.com/2018/05/09/sip-uri-overview/		
// Example SIP URI		
//		sip:user:password@host:port;uri-parameters?headers
// Example SIP Address		
//		"display-name" <sip:user:password@host:port;uri-parameters?uri-headers>;address-parameters?address-headers
// Do not support password		
//		"display-name" <sip:user@host:port;uri-parameters?uri-headers>;address-parameters?address-headers

//		String sipAddress = "(sips?):([^@]+)(?:@(.+))?"; //original
//		String sipAddress = "(?<protocol>sips?):(?<username>[^@]+)(?:@(.+))?";
//		String sipAddress = "(?<proto>sips?):(?<user>[^@]+)?(?:@(?<host>.+))?";
//		String sipAddress = "(?<proto>sips?):(?<user>.*(?=[^@]))(?<host>(?<=[^@]).+[^:])";
//		String sipAddress = "(\"([^\"]*)\")";
//		String sipAddress = "(\"([^\"]*)\"\\s)([\\<]*(.*)[\\>]*)";
		

		// Best Phone Number:
		// (?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:[+]*(?<phone>[\d]+)@)+(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)
        // "Alice" <sip:+18164388687@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;q=2

		// Best SIP Address
		// (?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?![+\d])(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)
		// "Alice" <sip:alice@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;q=2
        // "Alice" <sip:me.alice22_in-wonderland@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;q=2
		
		
		
		String sipAddress = "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)";
		
		

		Pattern pattern = Pattern.compile(sipAddress);

//		Matcher matcher = pattern.matcher("\"Alice\" <sip:alice@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;p=2");
//		Matcher matcher = pattern.matcher("<sip:alice@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;p=2");
//		Matcher matcher = pattern.matcher("<sip:alice@vorpal.net:5060;transport=udp;p=1>");
//		Matcher matcher = pattern.matcher("sip:alice@vorpal.net:5060;transport=udp;p=1");
//		Matcher matcher = pattern.matcher("sip:alice@vorpal.net;transport=udp;p=1");
//		Matcher matcher = pattern.matcher("sip:alice@vorpal.net:5060");
//		Matcher matcher = pattern.matcher("sip:alice@vorpal.net");
//		Matcher matcher = pattern.matcher("sip:vorpal.net");

		Matcher matcher = pattern.matcher("\"Alice\" <sip:alice@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;p=2");
//		Matcher matcher = pattern.matcher("\"Alice\" <sip:+18164388687@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;p=2");
//		Matcher matcher = pattern.matcher("\"Alice\" <sip:18164388687@vorpal.net:5060;transport=udp;p=1>;tag=9087411e;p=2");

		
		
		
		
	if (matcher.matches()) {
		System.out.println("$1: " + matcher.replaceAll("$1 ${name}"));
		System.out.println("$2: " + matcher.replaceAll("$2 ${proto}"));
		System.out.println("$3: " + matcher.replaceAll("$3 ${user}"));
		System.out.println("$4: " + matcher.replaceAll("$4 ${host}"));
		System.out.println("$5: " + matcher.replaceAll("$5 ${port}"));
		System.out.println("$6: " + matcher.replaceAll("$6 ${uriparams}"));
		System.out.println("$7: " + matcher.replaceAll("$7 ${addrparams}"));
	} else {
		System.out.println("no match");
	}
		

//		if (matcher.matches()) {
//			System.out.println("$1: " + matcher.replaceAll("$1"));
////			System.out.println("$2: " + matcher.replaceAll("$2") );
//		} else {
//			System.out.println("no match");
//		}

//		if (matcher1.find()) {
//			System.out.println("matcher1:" + //
//					" proto=" + matcher1.group("proto") + //
//					", user=" + matcher1.group("user") + //
//					", host=" + matcher1.group("host"));
//		} else {
//			System.out.println("matcher1: no match");
//		}
//		
//		if (matcher2.find()) {
//			System.out.println("matcher2:" + //
//					" proto=" + matcher1.group("proto") + //
//					", user=" + matcher1.group("user") + //
//					", host=" + matcher1.group("host"));
//		} else {
//			System.out.println("matcher2: no match");
//		}

	}

}
