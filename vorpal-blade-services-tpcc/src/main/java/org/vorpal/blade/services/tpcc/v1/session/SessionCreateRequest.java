package org.vorpal.blade.services.tpcc.v1.session;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

public class SessionCreateRequest {
	
	@Schema(description="expiration after inactivity, in minutes", defaultValue="3", nullable=true)
	public Integer expires;
	
	@Schema(description="destroy the session automatically", defaultValue="true", nullable=true)
	public Boolean invalidateWhenReady;
	
	@Schema(description="group session by name", defaultValue="[\"myGroup1\", \"myGroup2\"]", nullable=true)
	public List<String> groups = new LinkedList<>();

	@Schema(description="session attributes", defaultValue="{\"name\": \"Jeff\", \"class\": \"of95\"}", nullable=true)
	public Map<String, String> attributes = new HashMap<>();
}