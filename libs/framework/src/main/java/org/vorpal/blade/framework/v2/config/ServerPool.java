package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonPropertyOrder({ //
		"id", //
		"description", //
		"connectionAttempts", //
		"serverPairs" })
/**
 * A pool of server pairs with random selection for load balancing.
 */
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ServerPool implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description = null;
	private Integer connectionAttempts = null;
	private ArrayList<ServerPair> serverPairs = new ArrayList<>();

	/**
	 * Default constructor for JSON deserialization.
	 */
	public ServerPool() {
	}

	/**
	 * Constructs a ServerPool with the specified ID.
	 *
	 * @param id the unique identifier for this pool
	 */
	public ServerPool(String id) {
		this.id = id;
	}

	/**
	 * Returns the unique identifier for this server pool.
	 *
	 * @return the pool ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the unique identifier for this server pool.
	 *
	 * @param id the pool ID
	 * @return this ServerPool for method chaining
	 */
	public ServerPool setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Creates and adds a new empty ServerPair to this pool.
	 *
	 * @return the newly created ServerPair
	 */
	public ServerPair createServerPair() {
		ServerPair serverPair = new ServerPair();
		this.serverPairs.add(serverPair);
		return serverPair;
	}

	/**
	 * Creates and adds a new ServerPair with primary and secondary servers.
	 *
	 * @param primary the primary server URI
	 * @param secondary the secondary server URI
	 * @return the newly created ServerPair
	 */
	public ServerPair createServerPair(String primary, String secondary) {
		ServerPair serverPair = new ServerPair(primary, secondary);
		this.serverPairs.add(serverPair);
		return serverPair;
	}

	/**
	 * Returns the list of server pairs in this pool.
	 *
	 * @return the list of server pairs
	 */
	public List<ServerPair> getServerPairs() {
		return serverPairs;
	}

	/**
	 * Sets the list of server pairs for this pool.
	 *
	 * @param serverPairs the list of server pairs
	 * @return this ServerPool for method chaining
	 */
	public ServerPool setServerPairs(ArrayList<ServerPair> serverPairs) {
		this.serverPairs = serverPairs;
		return this;
	}

	/**
	 * Returns the human-readable description of this server pool.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Sets the human-readable description of this server pool.
	 *
	 * @param description the description
	 * @return this ServerPool for method chaining
	 */
	public ServerPool setDescription(String description) {
		this.description = description;
		return this;
	}

	/**
	 * Selects a random subset of server pairs for load balancing.
	 * The number of pairs returned is determined by connectionAttempts.
	 *
	 * @return a randomly ordered list of server pairs
	 */
	public ArrayList<ServerPair> selectRandomServerPairs() {
		ArrayList<ServerPair> randomList = new ArrayList<>();
		ArrayList<ServerPair> copyList = new ArrayList<>(serverPairs);

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

	/**
	 * Returns the maximum number of connection attempts.
	 *
	 * @return the connection attempts limit
	 */
	public Integer getConnectionAttempts() {
		return connectionAttempts;
	}

	/**
	 * Sets the maximum number of connection attempts.
	 *
	 * @param connectionAttempts the connection attempts limit
	 * @return this ServerPool for method chaining
	 */
	public ServerPool setConnectionAttempts(Integer connectionAttempts) {
		this.connectionAttempts = connectionAttempts;
		return this;
	}

}
