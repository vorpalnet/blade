package org.vorpal.blade.framework.config;

import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters {

	@JsonPropertyDescription("Sets Application Session expiration in minutes.")
	protected String expiration = null;

	public String getExpiration() {
		return expiration;
	}

	public SessionParameters setExpiration(String expiration) {
		this.expiration = expiration;
		return this;
	}

	public int resolveExpiration() throws ParseException {
		return Configuration.parseHRDurationAsSeconds(expiration);
	}

	public static void main(String[] args) throws ParseException {
		SessionParameters sp = new SessionParameters();

		System.out.println(sp.setExpiration("30").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30s").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30m").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30h").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30d").getExpiration() + " = " + sp.resolveExpiration());

		System.out.println(sp.setExpiration("30S").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30M").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30H").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30D").getExpiration() + " = " + sp.resolveExpiration());

		System.out.println(sp.setExpiration("30Seconds").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30Minutes").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30HOURS").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30DAYS").getExpiration() + " = " + sp.resolveExpiration());

		System.out.println(sp.setExpiration("30 seconds").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30 Minutes").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30 HOURS").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("30 DAYS").getExpiration() + " = " + sp.resolveExpiration());

		System.out.println(sp.setExpiration("0.5m").getExpiration() + " = " + sp.resolveExpiration());
		System.out.println(sp.setExpiration("1.5 Minutes").getExpiration() + " = " + sp.resolveExpiration());

	}

}
