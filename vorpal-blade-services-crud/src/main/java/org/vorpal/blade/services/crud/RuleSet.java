package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class RuleSet implements Serializable {
   public String id;
   public Map<String, String > map = new HashMap<>();
   public List<Rule> rules = new LinkedList<>();
   public Map output = new HashMap();

   public RuleSet() {
      this.map.put("port", "5060");
   }

   public void process(SipServletMessage msg) throws UnsupportedEncodingException, ServletParseException, IOException {
      Iterator var2 = this.rules.iterator();

      while(var2.hasNext()) {
         Rule rule = (Rule)var2.next();
         rule.process(this.map, msg, this.output);
      }

   }
}
