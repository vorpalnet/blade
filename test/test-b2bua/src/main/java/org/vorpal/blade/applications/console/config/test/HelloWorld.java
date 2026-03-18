package org.vorpal.blade.applications.console.config.test;

import java.rmi.RemoteException;

import javax.ejb.Remote;

@Remote
public interface HelloWorld {
    String getHelloWorld() throws RemoteException;;
}