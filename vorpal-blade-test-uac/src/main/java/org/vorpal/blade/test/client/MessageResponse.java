package org.vorpal.blade.test.client;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class MessageResponse implements Serializable{
	public String id;
	public int status;
	public List<Header> headers = new LinkedList<>();
	public String body;

}
