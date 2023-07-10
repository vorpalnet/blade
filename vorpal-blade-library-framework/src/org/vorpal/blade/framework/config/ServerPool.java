package org.vorpal.blade.framework.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonPropertyOrder({ //
		"id", //
		"description", //
		"connectionAttempts", //
		"serverPairs" })
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ServerPool {
	private String id;
	private String description = null;
	private Integer connectionAttempts = null;
	private ArrayList<ServerPair> serverPairs = new ArrayList<>();

	public ServerPool() {
	}

	public ServerPool(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public ServerPool setId(String id) {
		this.id = id;
		return this;
	}

	public ServerPair createServerPair() {
		ServerPair serverPair = new ServerPair();
		this.serverPairs.add(serverPair);
		return serverPair;
	}

//	public ServerPair createServerPair(String id) {
//		ServerPair serverPair = new ServerPair(id);
//		this.serverPairs.add(serverPair);
//		return serverPair;
//	}
//
//	public ServerPair createServerPair(String id, String primary, String secondary) {
//		ServerPair serverPair = new ServerPair(id, primary, secondary);
//		this.serverPairs.add(serverPair);
//		return serverPair;
//	}
	
	
	public ServerPair createServerPair(String primary, String secondary) {
		ServerPair serverPair = new ServerPair(primary, secondary);
		this.serverPairs.add(serverPair);
		return serverPair;
	}

	public List<ServerPair> getServerPairs() {
		return serverPairs;
	}

	public ServerPool setServerPairs(ArrayList<ServerPair> serverPairs) {
		this.serverPairs = serverPairs;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public ServerPool setDescription(String description) {
		this.description = description;
		return this;
	}

	public ArrayList<ServerPair> selectRandomServerPairs() {
		ArrayList<ServerPair> randomList = new ArrayList<>();
		ArrayList<ServerPair> copyList = new ArrayList<ServerPair>(serverPairs);

		int index;
		int amount = 1;

		if (this.connectionAttempts != null) {
			amount = (this.connectionAttempts <= serverPairs.size()) ? this.connectionAttempts : serverPairs.size();
		}

		for (int i = 0; i < amount; i++) {
			index = ThreadLocalRandom.current().nextInt(0, copyList.size());
			randomList.add(copyList.remove(index));
		}

		return randomList;
	}

	public Integer getConnectionAttempts() {
		return connectionAttempts;
	}

	public ServerPool setConnectionAttempts(Integer connectionAttempts) {
		this.connectionAttempts = connectionAttempts;
		return this;
	}

}
