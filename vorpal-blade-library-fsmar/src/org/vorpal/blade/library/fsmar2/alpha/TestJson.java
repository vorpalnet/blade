package org.vorpal.blade.library.fsmar2.alpha;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;

import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class TestJson {

	public class Action {
		public String originating;
		public String terminating;
		public String[] route;
		public String[] route_back;
		public String[] route_final;
	}

	public class Comparison extends HashMap<String, String> implements Serializable {
		public Comparison() {

		}

		public Comparison(String operator, String expression) {
			put(operator, expression);
		}

	}

	public class ComparisonList extends LinkedList<Comparison> implements Serializable {
	}

	public class Condition extends HashMap<String, ComparisonList> {
		public SipApplicationRoutingDirective directive;
	}

	public class Transition {
		public String next;
		public Condition condition;
		public Action action;

		public void addComparison(String header, String operator, String expression) {
			Comparison comparison;
			ComparisonList list;
			if (condition == null) {
				condition = new Condition();
			}
			list = condition.get(header);
			if (list == null) {
				list = new ComparisonList();
				condition.put(header, list);
			}

			comparison = new Comparison(operator, expression);
			list.add(comparison);
		}

		public void setOriginating(String header) {
			if (action == null) {
				action = new Action();
				action.originating = header;
			}
		}

		public void setTerminating(String header) {
			if (action == null) {
				action = new Action();
				action.terminating = header;
			}
		}

		public void setRoute(String[] routes) {
			if (action == null) {
				action = new Action();
				action.route = routes;
			}
		}

		public void setRouteBack(String[] routes) {
			if (action == null) {
				action = new Action();
				action.route_back = routes;
			}
		}

		public void setRouteFinal(String[] routes) {
			if (action == null) {
				action = new Action();
				action.route_final = routes;
			}
		}

	}

	public class Trigger {
		public ArrayList<Transition> transitions = new ArrayList<>();

		public Transition createTransition() {
			Transition transition = new Transition();
			transitions.add(transition);
			return transition;
		}
	}

	public class State {
		public HashMap<String, Trigger> triggers = new HashMap<>();

		public Trigger getTrigger(String name) {
			Trigger trigger = triggers.get(name);
			if (trigger == null) {
				trigger = new Trigger();
				triggers.put(name, trigger);
			}
			return trigger;
		}
	}

	public class Configuration {
		public HashMap<String, State> previous = new HashMap<>();

		public State getPrevious(String name) {
			State previousState = previous.get(name);
			if (previousState == null) {
				previousState = new State();
				previous.put(name, previousState);
			}
			return previousState;
		}

	}

	public static void main(String[] args) throws JsonProcessingException {

		TestJson j = new TestJson();
		Configuration config = j.new Configuration();

		Transition t1 = config.getPrevious("null").getTrigger("INVITE").createTransition();
		t1.next = "b2bua";
		t1.addComparison("To", "user", "bob");
		t1.condition.directive = SipApplicationRoutingDirective.NEW;
		t1.setOriginating("From");

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);

		System.out.println(output);

	}

}
