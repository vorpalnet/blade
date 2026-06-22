package org.vorpal.blade.library.fsmar3;

import javax.servlet.sip.ar.SipApplicationRouter;
import javax.servlet.sip.ar.spi.SipApplicationRouterProvider;

/// SPI provider for the FSMAR v3 Application Router.
public class AppRouterProvider extends SipApplicationRouterProvider {
	private static final AppRouter appRouter = new AppRouter();

	@Override
	public SipApplicationRouter getSipApplicationRouter() {
		return appRouter;
	}

}
