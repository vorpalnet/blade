package org.vorpal.blade.services.crud;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import java.io.Serializable;
import java.util.Map;
import javax.servlet.sip.SipServletMessage;

@JsonIdentityInfo(
   generator = ObjectIdGenerators.PropertyGenerator.class,
   property = "id"
)
public class Create implements Serializable {
   public String id;
   public String attribute = null;
   public String value = null;

   public Create() {
   }

   public Create(String attribute, String value) {
      this.attribute = attribute;
      this.value = value;
   }

   public static String resolveVariables(Map map, String inputString) {
      int index;
      String variable;
      String _value;
      String outputString;
      for(outputString = new String(inputString); (index = outputString.indexOf("${")) >= 0; outputString = outputString.replace(variable, _value)) {
         variable = outputString.substring(index, outputString.indexOf("}") + 1);
         String key = variable.substring(2, variable.length() - 1);
         _value = (String)map.get(key);
         _value = _value != null ? _value : "null";
      }

      return outputString;
   }

   public void process(Map map, SipServletMessage msg, Map output) {
      String _value = resolveVariables(map, this.value);
      msg.setHeader(this.attribute, _value);
   }

   public static void main(String[] args) {
   }
}
