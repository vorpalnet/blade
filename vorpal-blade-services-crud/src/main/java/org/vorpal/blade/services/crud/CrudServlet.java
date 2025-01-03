package org.vorpal.blade.services.crud;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.b2bua.Bye;
import org.vorpal.blade.framework.v2.b2bua.Cancel;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;
import org.vorpal.blade.framework.v2.b2bua.Passthru;
import org.vorpal.blade.framework.v2.b2bua.Reinvite;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.Translation;

@WebListener
@SipApplication(
   distributable = true
)
@SipServlet(
   loadOnStartup = 1
)
@SipListener
public class CrudServlet extends B2buaServlet {
   public SettingsManager settingsManager;

   protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
      this.settingsManager = new SettingsManager(event, CrudConfiguration.class, new CrudConfigurationSample());
   }

   protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
      try {
         this.settingsManager.unregister();
      } catch (Exception var3) {
         var3.printStackTrace();
      }

   }

   protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
      Callflow callflow = null;
      CrudConfiguration settings = (CrudConfiguration)this.settingsManager.getCurrent();
      if (inboundRequest.getMethod().equals("INVITE") && inboundRequest.isInitial()) {
         Translation t = settings.findTranslation(inboundRequest);
         sipLogger.severe((SipServletMessage)inboundRequest, "is t null " + t);
         if (t != null) {
            sipLogger.severe((SipServletMessage)inboundRequest, "found t! ");
            String ruleSetId = (String)t.getAttribute("ruleSet");
            sipLogger.severe((SipServletMessage)inboundRequest, "ruleSet.id " + ruleSetId);
            if (ruleSetId != null) {
               RuleSet ruleSet = (RuleSet)settings.ruleSets.get(ruleSetId);
               if (ruleSet != null) {
                  sipLogger.severe((SipServletMessage)inboundRequest, "ruleSet: " + ruleSet);
                  if (ruleSet != null) {
                     sipLogger.severe((SipServletMessage)inboundRequest, "found ruleSet.");
                     ruleSet.process(inboundRequest);
                     sipLogger.severe((SipServletMessage)inboundRequest, "done processing ruleset");
                     sipLogger.severe((SipServletMessage)inboundRequest, "To: " + (String)ruleSet.output.get("To"));
                     sipLogger.severe((SipServletMessage)inboundRequest, "Request-URI: " + (String)ruleSet.output.get("Request-URI"));
                     callflow = new CrudInitialInvite((B2buaListener)null, ruleSet.output);
                  } else {
                     sipLogger.severe((SipServletMessage)inboundRequest, "No ruleSet found.");
                  }
               }
            }
         }
      }

      if (callflow == null) {
         if (inboundRequest.getMethod().equals("INVITE")) {
            if (inboundRequest.isInitial()) {
               callflow = new InitialInvite((B2buaListener)null);
            } else {
               callflow = new Reinvite((B2buaServlet)null);
            }
         } else if (inboundRequest.getMethod().equals("BYE")) {
            callflow = new Bye((B2buaServlet)null);
         } else if (inboundRequest.getMethod().equals("CANCEL")) {
            callflow = new Cancel((B2buaServlet)null);
         } else {
            callflow = new Passthru((B2buaServlet)null);
         }
      }

      return (Callflow)callflow;
   }

   public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
      SettingsManager.sipLogger.warning((SipServletMessage)outboundRequest, "Sending INVITE...\n" + outboundRequest.toString());
   }

   public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
   }

   public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
   }

   public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
   }

   public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
   }

   public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
   }
}
