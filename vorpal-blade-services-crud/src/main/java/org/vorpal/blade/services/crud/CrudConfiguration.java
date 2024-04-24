package org.vorpal.blade.services.crud;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import org.vorpal.blade.framework.config.RouterConfig;

@JsonPropertyOrder({"logging", "selectors", "ruleSets", "defaultRoute", "maps", "plan"})
public class CrudConfiguration extends RouterConfig implements Serializable {
   public Map ruleSets = new HashMap();
}
