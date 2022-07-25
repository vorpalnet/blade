package org.vorpal.blade.services.transfer;

import org.vorpal.blade.framework.transfer.TransferCondition;

public class TransferSettingsSample extends TransferSettings {

	public TransferSettingsSample() {

		this.setTransferAllRequests(false);
		this.setDefaultTransferStyle(TransferSettings.TransferStyle.blind);

		TransferCondition tc1 = new TransferCondition();
		tc1.setStyle(TransferStyle.blind);
		tc1.getCondition().addComparison("OSM-Features", "includes", "transfer");
		this.getTransferConditions().add(tc1);

		TransferCondition tc2 = new TransferCondition();
		tc2.setStyle(TransferStyle.blind);
		tc2.getCondition().addComparison("Refer-To", "matches", ".*sip:1996.*");
		this.getTransferConditions().add(tc2);

		preserveInviteHeaders.add("Cisco-Gucid");
		preserveInviteHeaders.add("User-to-User");

	}

}
