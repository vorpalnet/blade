package org.vorpal.blade.services.crud;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class Read implements Serializable {
   public String id;
   private Pattern pattern;
   public String attribute;
   public String expression;

   public Read() {
   }

   public Read(String attribute, String expression) {
      this.attribute = attribute;
      this.expression = expression;
      this.pattern = Pattern.compile(expression);
   }

   public void process(Map map, SipServletMessage msg, Map output) throws UnsupportedEncodingException, IOException {
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

      LinkedList groups = new LinkedList();
      Matcher m = this.pattern.matcher(this.expression);

      while(m.find()) {
         groups.add(m.group("name"));
      }

      Matcher matcher = this.pattern.matcher(header);
      boolean matchFound = matcher.find();
      if (matchFound) {
         Iterator itr = groups.iterator();

         while(itr.hasNext()) {
            String name = (String)itr.next();
            String value = matcher.group(name);
            map.put(name, value);
         }
      }

   }

   public static void main(String[] args) {
      String z = "\\<(?<name>[a-zA-Z0-9]+)\\>";
      String strPattern = "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)";
      String[] urls = new String[]{"sip:alice@vorpal.org", "sip:alice@vorpal.org:5060", "sip:alice@vorpal.org:5060;transport=tcp", "<sip:alice@vorpal.org>", "\"Alice\" <sip:alice@vorpal.org>", "\"Alice\" <sip:alice@vorpal.org:5060>", "\"Alice\" <sip:alice@vorpal.org:5060;transport=tcp>", "\"Alice\" <sip:alice@vorpal.org:5060;transport=tcp>;param=one"};
      LinkedList groups = new LinkedList();
      Pattern p = Pattern.compile(z);
      Matcher m = p.matcher(strPattern);

      while(m.find()) {
         groups.add(m.group("name"));
      }

      Pattern pattern = Pattern.compile(strPattern);
      String[] var9 = urls;
      int var10 = urls.length;

      for(int var11 = 0; var11 < var10; ++var11) {
         String url = var9[var11];
         System.out.println("\n" + url);
         Matcher matcher = pattern.matcher(url);
         boolean matchFound = matcher.find();
         if (matchFound) {
            Iterator itr = groups.iterator();

            while(itr.hasNext()) {
               String name = (String)itr.next();
               String value = matcher.group(name);
               System.out.println("name=" + name + ", value='" + value + "'");
            }
         }
      }

   }
}
