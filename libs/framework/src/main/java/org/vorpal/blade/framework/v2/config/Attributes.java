package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.List;

/**
 * Container for a value and its associated list of attributes.
 */
public class Attributes implements Serializable {
	private static final long serialVersionUID = 1L;

	String value;
	List<Attribute> attributes;

}
