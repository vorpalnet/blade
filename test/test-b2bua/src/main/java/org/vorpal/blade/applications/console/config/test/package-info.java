/// Remote EJB interfaces for testing the BLADE console's configuration
/// management capabilities. These interfaces define contracts for invoking
/// Enterprise JavaBeans remotely, enabling integration testing between the
/// console administration module and deployed SIP services.
///
/// ## Key Components
///
/// - [HelloBeanRemote] - remote EJB interface with a {@code sayHelloFromServiceBean()} method for testing service bean invocation
/// - [HelloWorld] - remote EJB interface with a {@code getHelloWorld()} method for basic remote connectivity verification
///
/// ## Remote EJB Testing
///
/// ### HelloBeanRemote Interface
/// Provides a fire-and-forget style remote method ({@code sayHelloFromServiceBean()})
/// used to verify that EJB invocations from the console reach the target service
/// bean successfully.
///
/// ### HelloWorld Interface
/// Provides a request-response style remote method ({@code getHelloWorld()}) that
/// returns a String value, used to verify round-trip EJB communication. This method
/// declares {@link java.rmi.RemoteException} for RMI transport error handling.
///
/// @see javax.ejb.Remote
package org.vorpal.blade.applications.console.config.test;
