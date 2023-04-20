package org.vorpal.blade.test.client;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class MessageResponse implements Serializable{
	private static final long serialVersionUID = 1L;
	public String id;
	public int finalStatus;
	public List<String> responses = new LinkedList<>();
}
