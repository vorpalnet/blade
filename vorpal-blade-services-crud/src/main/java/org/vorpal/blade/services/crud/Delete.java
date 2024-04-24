package org.vorpal.blade.services.crud;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class Delete implements Serializable {
   public String id;
   private Pattern pattern;
   public String attribute;
   public String expression;

   public Delete() {
   }

   public Delete(String attribute, String expression) {
      this.attribute = attribute;
      this.expression = expression;
      this.pattern = Pattern.compile(expression);
   }

   public void process(Map map, SipServletMessage msg) throws UnsupportedEncodingException, IOException {
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
                  new String(content);
               }
            }
            break;
         default:
            msg.getHeader(this.attribute);
      }

      Matcher m = this.pattern.matcher(this.expression);
      if (m.find()) {
         switch (this.attribute) {
            case "Request-URI":
               header = ((SipServletRequest)msg).getRequestURI().toString();
               break;
            case "Content":
               msg.setContent((Object)null, (String)null);
               break;
            default:
               msg.removeHeader(this.attribute);
         }
      }

   }
}
