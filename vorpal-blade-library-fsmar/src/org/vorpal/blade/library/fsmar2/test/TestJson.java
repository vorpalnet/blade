package org.vorpal.blade.library.fsmar2.test;

import org.vorpal.blade.library.fsmar2.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/*
{
  "b2bua" : {
    "INVITE" : {
      "proxy-registrar" : [ ]
    }
  },
  "app13" : {
    "INVITE" : {
      "app14" : [ {
        "action" : {
          "route_back" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  },
  "app12" : {
    "INVITE" : {
      "app13" : [ {
        "action" : {
          "route_back" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  },
  "app11" : {
    "INVITE" : {
      "app12" : [ {
        "action" : {
          "route" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  },
  "app10" : {
    "INVITE" : {
      "app11" : [ {
        "action" : {
          "terminating" : "To"
        }
      } ]
    }
  },
  "null" : {
    "INVITE" : {
      "b2bua" : [ ]
    }
  },
  "app9" : {
    "INVITE" : {
      "app10" : [ {
        "action" : {
          "originating" : "From"
        }
      } ]
    }
  },
  "app6" : {
    "INVITE" : {
      "app6" : [ {
        "condition" : {
          "Allow" : [ {
            "contains" : "UPDATE"
          } ]
        }
      } ]
    }
  },
  "app5" : {
    "INVITE" : {
      "app6" : [ {
        "condition" : {
          "X-Version-Number" : [ {
            "matches" : "^2\\.\\1.*$"
          } ]
        }
      } ]
    }
  },
  "app8" : {
    "INVITE" : {
      "app9" : [ {
        "condition" : {
          "Session-Expires" : [ {
            "value" : "3600"
          }, {
            "refresher" : "uac"
          } ]
        }
      } ]
    }
  },
  "app7" : {
    "INVITE" : {
      "app8" : [ {
        "condition" : {
          "Allow" : [ {
            "includes" : "PRACK"
          } ]
        }
      } ]
    }
  },
  "app2" : {
    "INVITE" : {
      "app3" : [ {
        "condition" : {
          "To" : [ {
            "address" : "^.*<sip[s]:alice@vorpal.org>.*$"
          } ]
        }
      } ]
    }
  },
  "app1" : {
    "INVITE" : {
      "app2" : [ {
        "condition" : {
          "Request-URI" : [ {
            "uri" : "^.*sip[s]:alice@vorpal.org.*$"
          } ]
        }
      } ]
    }
  },
  "app4" : {
    "INVITE" : {
      "app5" : [ {
        "condition" : {
          "X-Version-Number" : [ {
            "equals" : "2.1.3"
          } ]
        }
      } ]
    }
  },
  "app3" : {
    "INVITE" : {
      "app4" : [ {
        "condition" : {
          "To" : [ {
            "user" : "bob"
          }, {
            "host" : "vorpal.org"
          }, {
            "loc" : "wonderland"
          } ]
        }
      } ]
    }
  }
}


 */

public class TestJson {

	public static void main(String[] args) throws JsonProcessingException {

		Configuration configuration = new Configuration();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		
		
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
