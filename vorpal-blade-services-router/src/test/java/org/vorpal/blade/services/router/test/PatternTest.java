package org.vorpal.blade.services.router.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class PatternTest {

//	@Test
//	public void test1() {
//		String output;
//		String requestUri = "sip:bob@vorpal.org:5060;loc=wonderland";
////		String p = "^(sips?):([^@]+)(?:@(.+))?$";
////		String p = "^(sips?):([^@]+)@([^:;]+)(.*)$"; // sip  bob  vorpal.org  :5060;loc=wonderland
//		String p = "^(sips?):([^@]+)@([^:;]+):([^;]+)(.*)$"; // sip bob vorpal.org :5060;loc=wonderland
//
//		String expression;
//
//		Pattern pattern = Pattern.compile(p);
//		Matcher matcher = pattern.matcher(requestUri);
//
//		System.out.println("Test #1");
//
//		expression = "$0";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$1";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$2";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$3";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$4";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$5";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//	}
//
//	@Test
//	public void test2() {
//		String output;
//		String requestUri = "sip:bob@vorpal.org:5060;loc=wonderland";
////		String p = "^(sips?):([^@]+)(?:@(.+))?$";
////		String p = "^(sips?):([^@]+)@([^:;]+)(.*)$"; // sip  bob  vorpal.org  :5060;loc=wonderland
//		String p = "^(sips?):([^@]+)(@)([^:;]+)(:*)([^;]*)(.*)$"; // sip bob vorpal.org :5060;loc=wonderland
//
//		String expression;
//
//		Pattern pattern = Pattern.compile(p);
//		Matcher matcher = pattern.matcher(requestUri);
//
//		System.out.println("Test #2");
//
//		expression = "$0";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$1";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$2";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$3";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$4";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$5";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$6";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$7";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//	}
//	
//	@Test
//	public void test3() {
//		String output;
//		String requestUri = "sip:bob@vorpal.org;loc=wonderland";
////		String p = "^(sips?):([^@]+)(?:@(.+))?$";
////		String p = "^(sips?):([^@]+)@([^:;]+)(.*)$";
////		String p = "^(sips?):([^@]+)(@)([^:;]+)(:*)([^;]*)(.*)$"; 
//		String p = "^(sips?)(:)([^@]+)(@)([^:;]+)(:*)([^;]*)(.*)$";
//
//		String expression;
//
//		Pattern pattern = Pattern.compile(p);
//		Matcher matcher = pattern.matcher(requestUri);
//
//		System.out.println("Test #3");
//
//		expression = "$0";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$1";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$2";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$3";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$4";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$5";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$6";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//
//		expression = "$7";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//		
//		expression = "$8";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//		
//		expression = "$1$2$3$4$5$6$7$8";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//	}
	
	@Test
	public void test4() {
		String output;
		String requestUri = "sip:bob@vorpal.org;loc=wonderland";
//		String requestUri = "sip:vorpal.org;loc=wonderland";

		
		
		//		String p = "^(sips?):([^@]+)(?:@(.+))?$";
//		String p = "^(sips?):([^@]+)@([^:;]+)(.*)$";
//		String p = "^(sips?):([^@]+)(@)([^:;]+)(:*)([^;]*)(.*)$"; 
//		String p = "^sips?:([^@]*)@*([^:]*):*([^;]*)(.*)$";
		String p = "^sips?:(.+(?=@))*@*([^:;]+).*$";

		
		
		
		
		
		String expression;

		Pattern pattern = Pattern.compile(p);
		Matcher matcher = pattern.matcher(requestUri);

		System.out.println("Test #4");

		System.out.println("matches: "+matcher.matches());

	
		expression = "$1";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

		expression = "$2";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

		expression = "$3";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

		expression = "$4";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

		expression = "$5";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

		expression = "$6";
		output = matcher.replaceAll(expression);
		System.out.println("\t" + expression + " : " + output);

//		expression = "$7";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//		
//		expression = "$8";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
//		
//		expression = "$1$2$3$4$5$6$7$8";
//		output = matcher.replaceAll(expression);
//		System.out.println("\t" + expression + " : " + output);
	}

}
