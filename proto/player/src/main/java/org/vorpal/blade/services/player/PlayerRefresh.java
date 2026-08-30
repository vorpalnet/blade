package org.vorpal.blade.services.player;

import javax.ejb.Remote;
import javax.ejb.Stateless;

import org.vorpal.blade.framework.v3.media.MediaRefresh;
import org.vorpal.blade.framework.v3.media.MediaRefreshBean;

/// Exposes the framework's [MediaRefresh] door from this WAR. An EJB is only discovered in a
/// WAR's own classes (not in a jar under `WEB-INF/lib`), so each media application declares
/// this one-line subclass; everything it does is in [MediaRefreshBean].
@Stateless(name = MediaRefresh.BEAN_NAME)
@Remote(MediaRefresh.class)
public class PlayerRefresh extends MediaRefreshBean {
}
