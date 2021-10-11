/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.services.acl;

import java.util.LinkedList;

import org.vorpal.blade.services.acl.AclRule.Permission;

import inet.ipaddr.Address;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

/**
 * @author Jeff McDonald
 *
 */
public class AclConfig {

	private AclRule.Permission defaultPermission = AclRule.Permission.deny;

	private LinkedList<AclRule> remoteAddresses = new LinkedList<>();

	private AddressTrieMap<Address, AclRule.Permission> trieMap;

	public AclConfig() {
		remoteAddresses.add(new AclRule("192.168.1.0/24", Permission.allow));
		remoteAddresses.add(new AclRule("192.168.2.136", Permission.deny));
	}

	public void initialize() {

		trieMap = new AddressTrieMap<Address, AclRule.Permission>(new IPv4AddressAssociativeTrie());

		for (AclRule aclRule : remoteAddresses) {
			trieMap.put(new IPAddressString(aclRule.getAddress()).getAddress(), aclRule.getPermission());
		}

	}

	/**
	 * @return the defaultPermission
	 */
	public Permission getDefaultPermission() {
		return defaultPermission;
	}

	/**
	 * @param defaultPermission the defaultPermission to set
	 */
	public void setDefaultPermission(Permission defaultPermission) {
		this.defaultPermission = defaultPermission;
	}

	/**
	 * @return the remoteAddresses
	 */
	public LinkedList<AclRule> getRemoteAddresses() {
		return remoteAddresses;
	}

	/**
	 * @param remoteAddresses the remoteAddresses to set
	 */
	public void setremoteAddresses(LinkedList<AclRule> remoteAddresses) {
		this.remoteAddresses = remoteAddresses;
	}

	AclRule.Permission evaulate(String address) {
		IPv4Address addr = new IPAddressString(address).getAddress().toIPv4();
		Permission tmpPermission = trieMap.get(addr);
		return (tmpPermission != null) ? tmpPermission : defaultPermission;
	}

}
