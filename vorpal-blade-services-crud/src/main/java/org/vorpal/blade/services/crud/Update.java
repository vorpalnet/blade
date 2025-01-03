package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class Update implements Serializable {
   private Pattern _pattern;
   private Pattern _p;
   public String id;
   public String attribute;
   public String replacement;
   public String pattern;

   public Update() {
   }

   public Update(String attribute, String pattern, String replacement) {
      this.attribute = attribute;
      this.pattern = pattern;
      this.replacement = replacement;
      System.out.println("pattern=" + pattern);
   }

   public void process(Map map, SipServletMessage msg, Map output) throws UnsupportedEncodingException, IOException, ServletParseException {
      this._pattern = Pattern.compile(this.pattern);
      this._p = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");
      SettingsManager.sipLogger.warning(msg, "Update.process...");
      String header = null;
      switch (this.attribute) {
         case "Request-URI":
            header = ((SipServletRequest)msg).getRequestURI().toString();
            break;
         case "Content":
            if (msg.getContent() != null) {
               if (msg.getContent() instanceof String) {
                  header = (String)msg.getContent();
               } else {
                  byte[] content = (byte[])msg.getContent();
                  header = new String(content);
               }
            }
            break;
         default:
            header = msg.getHeader(this.attribute);
      }

      SettingsManager.sipLogger.warning(msg, "header=" + header);
      LinkedList groups = new LinkedList();
      Matcher m = this._p.matcher(this.pattern);

      while(m.find()) {
         String __name = m.group("name");
         if (__name != null) {
            SettingsManager.sipLogger.warning(msg, "adding group=" + __name);
            groups.add(__name);
         } else {
            SettingsManager.sipLogger.severe(msg, "group name is NULL!");
         }
      }

      SettingsManager.sipLogger.fine(msg, "port was: '" + (String)map.get("port") + "'");
      if (null != map.get("port") && ((String)map.get("port")).length() != 0) {
         SettingsManager.sipLogger.fine(msg, "port is now: " + (String)map.get("port"));
      } else {
         map.put("port", "5060");
      }

      Matcher matcher = this._pattern.matcher(header);
      boolean matchFound = matcher.find();
      String name;
      if (matchFound) {
         Iterator itr = groups.iterator();

         while(itr.hasNext()) {
            name = (String)itr.next();
            SettingsManager.sipLogger.warning(msg, "matching on group=" + name);
            String value = matcher.group(name);
            map.put(name, value);
         }
      } else {
         SettingsManager.sipLogger.severe(msg, "No match found for header value: " + header);
      }

      name = Configuration.resolveVariables(map, this.replacement);
      SettingsManager.sipLogger.warning(msg, "RequestURI: " + name);
      output.put(this.attribute, name);
   }
}
