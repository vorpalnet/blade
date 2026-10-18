# -*- coding: utf-8 -*-
# Sample operations events for the dashboard's Operations panel.
#
# Publishes real CloudEvents onto the BLADE event bus, the way applications do, so
# the panel fills exactly as it would in production. Every name starts with
# "test-" and every address is a documentation address, so nothing here can be
# mistaken for real equipment.
#
#   OPS_ACTION=publish (default)  the story below, spread over the last 40 minutes
#   OPS_ACTION=clear              the events that close each open issue
#
# Run with WLST on the admin host:
#   WLS_USER=... WLS_PASSWORD=... BUS_URL=t3://<engine>:8001 wlst.sh ops-samples.py
#
# BUS_URL names an engine: the topic lives on the engine cluster's JMS server and
# does not bind on the AdminServer. The panel keeps its state in memory, so a
# dashboard redeploy also clears everything.
import os, uuid
from java.util import Hashtable, Date
from java.text import SimpleDateFormat
from java.util import TimeZone
from javax.naming import InitialContext, Context

URL = os.environ.get('BUS_URL', 't3://localhost:8001')
ACTION = os.environ.get('OPS_ACTION', 'publish')
NODE = os.environ.get('OPS_NODE', 'engine0')

env = Hashtable()
env.put(Context.INITIAL_CONTEXT_FACTORY, 'weblogic.jndi.WLInitialContextFactory')
env.put(Context.PROVIDER_URL, URL)
env.put(Context.SECURITY_PRINCIPAL, os.environ['WLS_USER'])
env.put(Context.SECURITY_CREDENTIALS, os.environ['WLS_PASSWORD'])
ctx = InitialContext(env)
factory = ctx.lookup('jms/BladeEventBusConnectionFactory')
topic = ctx.lookup('jms/BladeEventBusTopic')
conn = factory.createConnection()
session = conn.createSession(False, 1)
producer = session.createProducer(topic)

ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
ISO.setTimeZone(TimeZone.getTimeZone('UTC'))
NOW = Date().getTime()

def q(v):
    if v is None:
        return 'null'
    if isinstance(v, bool):
        return v and 'true' or 'false'
    if isinstance(v, (int, long, float)):
        return str(v)
    if isinstance(v, list):
        return '[' + ','.join([q(x) for x in v]) + ']'
    return '"' + str(v).replace('\\', '\\\\').replace('"', '\\"') + '"'

def publish(name, subject, minutesAgo, data):
    etype = 'org.vorpal.blade.' + name
    data['node'] = NODE
    eid = str(uuid.uuid4())
    body = '{"specversion":"1.0","type":%s,"source":"test-ops-samples","id":%s,"time":%s,' \
           '"datacontenttype":"application/json","subject":%s,"data":{%s}}' % (
        q(etype), q(eid), q(ISO.format(Date(NOW - minutesAgo * 60000))), q(subject),
        ','.join([q(k) + ':' + q(v) for (k, v) in data.items()]))
    m = session.createTextMessage(body)
    m.setStringProperty('eventType', etype)
    m.setStringProperty('eventSubject', subject)
    m.setStringProperty('eventId', eid)
    producer.send(m)
    print('SENT %-22s %s' % (name, subject))

if ACTION == 'clear':
    publish('endpoint.up', 'test-sbc-2', 0, {'endpoint': 'test-sbc-2', 'note': 'OPTIONS answered'})
    publish('trunk.registered', 'test-trunk-b', 0, {'gateway': 'test-trunk-b', 'registrar': 'sip.test-carrier.example', 'expires': 300})
else:
    # Oldest first, so the feed ends newest first.
    publish('config.saved', 'irouter.json', 40, {'file': 'irouter.json', 'change': 'updated', 'actor': 'test-admin', 'editor': 'Configurator'})
    publish('config.published', 'irouter.json', 39, {'file': 'irouter.json', 'pushedTo': ['engine0', 'engine1'], 'failed': ['engine2']})
    publish('trunk.registered', 'test-trunk-a', 35, {'gateway': 'test-trunk-a', 'registrar': 'sip.test-carrier.example', 'expires': 300})
    publish('endpoint.down', 'test-media-1', 30, {'endpoint': 'test-media-1', 'note': 'OPTIONS timed out'})
    publish('queue.depth', 'test-sales', 28, {'queue': 'test-sales', 'depth': 4, 'low': 1, 'high': 6})
    publish('remote.down', 'test-crm.example', 26, {'kind': 'rest', 'remote': 'test-crm.example', 'application': 'test-app', 'reason': 'HTTP 503'})
    publish('endpoint.up', 'test-media-1', 24, {'endpoint': 'test-media-1', 'note': 'OPTIONS answered'})
    publish('sip.denied', 'INVITE', 20, {'method': 'INVITE', 'sourceAddress': '198.51.100.23'})
    publish('queue.depth', 'test-sales', 18, {'queue': 'test-sales', 'depth': 9, 'low': 4, 'high': 12})
    publish('remote.up', 'test-crm.example', 15, {'kind': 'rest', 'remote': 'test-crm.example', 'application': 'test-app'})
    publish('trunk.failed', 'test-trunk-b', 12, {'gateway': 'test-trunk-b', 'registrar': 'sip.test-carrier.example', 'status': 403, 'reason': 'Forbidden'})
    publish('endpoint.down', 'test-sbc-2', 6, {'endpoint': 'test-sbc-2', 'note': 'OPTIONS timed out'})
    publish('queue.depth', 'test-sales', 2, {'queue': 'test-sales', 'depth': 0, 'low': 0, 'high': 9})

producer.close()
session.close()
conn.close()
print('DONE ' + ACTION)
