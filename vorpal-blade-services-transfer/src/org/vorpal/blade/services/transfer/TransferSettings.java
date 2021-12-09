package org.vorpal.blade.services.transfer;

import java.io.Serializable;
import java.util.HashMap;

public class TransferSettings implements Serializable {

	private Boolean transferIfUserNotDefined;
	private HashMap<String, String> userMap = new HashMap<>();

	public TransferSettings() {
		transferIfUserNotDefined = true;
		userMap.put("18165551234", "19135554321");
		userMap.put("target1", "target2");
	}

	public Boolean getTransferIfUserNotDefined() {
		return transferIfUserNotDefined;
	}

	public void setTransferIfUserNotDefined(Boolean transferIfUserNotDefined) {
		this.transferIfUserNotDefined = transferIfUserNotDefined;
	}

	public HashMap<String, String> getUserMap() {
		return userMap;
	}

	public void setUserMap(HashMap<String, String> userMap) {
		this.userMap = userMap;
	}

	
	
}
