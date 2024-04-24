package org.vorpal.blade.services.crud;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class Rule implements Serializable {
   private Map map;
   public String id;
   public String description;
   public String method;
   public List create = new LinkedList();
   public List read = new LinkedList();
   public List update = new LinkedList();
   public List delete = new LinkedList();

   public void process(Map map, SipServletMessage msg, Map output) throws UnsupportedEncodingException, IOException, ServletParseException {
      Iterator var4 = this.update.iterator();

      while(var4.hasNext()) {
         Update _update = (Update)var4.next();
         _update.process(map, msg, output);
      }

   }

   public static enum MethodType {
      INVITE,
      ACK,
      BYE,
      CANCEL,
      REGISTER,
      OPTIONS,
      PRACK,
      SUBSCRIBE,
      NOTIFY,
      PUBLISH,
      INFO,
      UPDATE,
      REFER;
   }

   public static enum MessageType {
      REQUEST,
      RESPONSE;
   }
}
