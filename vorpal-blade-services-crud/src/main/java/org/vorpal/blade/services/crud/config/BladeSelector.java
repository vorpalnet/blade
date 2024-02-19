package org.vorpal.blade.services.crud.config;

import java.util.regex.Pattern;

public class BladeSelector {

	public String id; // optional for JSON references
	public String description; // optional for human readable descriptions
	public String attribute; // location of the key data, like in the 'To' header
	public String pattern; // regular expression using capturing groups to parse the key data
	public String expression; // replacement pattern, like $1 to format the key data

}
