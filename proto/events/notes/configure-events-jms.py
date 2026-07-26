# Configures the JMS resources for the BLADE v3 event bus.
#
# This is the pub/sub sibling of services/analytics/notes/configure-jms.py.
# The one structural difference: the destination is a UniformDistributed*Topic*
# (fan-out to every subscribing app), not a Queue (point-to-point, one consumer).
# Everything else — file store, JMS server, system module, subdeployment,
# connection factory — mirrors the analytics script.
#
# JNDI names MUST match org.vorpal.blade.framework.v3.events.EventBus:
#   jms/BladeEventBusConnectionFactory   jms/BladeEventBusTopic
#
# Two improvements over the analytics original:
#   * TARGET CLUSTER is a parameter (BLADE_ENGINE_CLUSTER), not hardcoded — the
#     analytics script bakes in 'BEA_ENGINE_TIER_CLUST', which is wrong on a
#     domain (e.g. ashburn) whose engine cluster has a different name.
#   * IDEMPOTENT — every resource is created only if absent (cmo.lookupX == None),
#     so it is safe to re-run after a partial failure or to add a resource later.
#
# Set these before running:
#   WL_USER   weblogic
#   WL_PASS   welcome1
#   WL_ADMIN  t3://localhost:7001
#   BLADE_ENGINE_CLUSTER   (optional) engine-tier cluster name; defaults to
#                          BEA_ENGINE_TIER_CLUST. Find yours with:
#                          ls('/Clusters') in wlst, or check build-profiles.

import os

wl_user = os.environ.get('WL_USER')
wl_pass = os.environ.get('WL_PASS')
wl_admin = os.environ.get('WL_ADMIN')
cluster = os.environ.get('BLADE_ENGINE_CLUSTER', 'BEA_ENGINE_TIER_CLUST')

connect(wl_user, wl_pass, wl_admin)

target = ObjectName('com.bea:Name=%s,Type=Cluster' % cluster)

# ── File store ──────────────────────────────────────────────────────────────
edit()
startEdit()
cd('/')
if cmo.lookupFileStore('BladeEventBusFileStore') is None:
    cmo.createFileStore('BladeEventBusFileStore')
cd('/FileStores/BladeEventBusFileStore')
cmo.setDirectory('BladeEventBus')
set('Targets', jarray.array([target], ObjectName))
save()
activate()

# ── JMS server (persisted to the file store) ────────────────────────────────
startEdit()
cd('/')
if cmo.lookupJMSServer('BladeEventBusJMSServer') is None:
    cmo.createJMSServer('BladeEventBusJMSServer')
cd('/JMSServers/BladeEventBusJMSServer')
cmo.setPersistentStore(getMBean('/FileStores/BladeEventBusFileStore'))
set('Targets', jarray.array([target], ObjectName))
save()
activate()

# ── JMS system module ───────────────────────────────────────────────────────
startEdit()
cd('/')
if cmo.lookupJMSSystemResource('BladeEventBusSystemModule') is None:
    cmo.createJMSSystemResource('BladeEventBusSystemModule', 'BladeEventBus/BladeEventBus-jms.xml')
cd('/JMSSystemResources/BladeEventBusSystemModule')
set('Targets', jarray.array([target], ObjectName))
save()
activate()

# ── Subdeployment (targets the JMS server) ──────────────────────────────────
jmsServerTarget = ObjectName('com.bea:Name=BladeEventBusJMSServer,Type=JMSServer')
startEdit()
cd('/JMSSystemResources/BladeEventBusSystemModule')
if cmo.lookupSubDeployment('BladeEventBusSubdeployment') is None:
    cmo.createSubDeployment('BladeEventBusSubdeployment')
cd('/JMSSystemResources/BladeEventBusSystemModule/SubDeployments/BladeEventBusSubdeployment')
set('Targets', jarray.array([jmsServerTarget], ObjectName))
save()
activate()

# ── Connection factory ──────────────────────────────────────────────────────
startEdit()
cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule')
if cmo.lookupConnectionFactory('BladeEventBusConnectionFactory') is None:
    cmo.createConnectionFactory('BladeEventBusConnectionFactory')

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/ConnectionFactories/BladeEventBusConnectionFactory')
cmo.setJNDIName('jms/BladeEventBusConnectionFactory')

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/ConnectionFactories/BladeEventBusConnectionFactory/SecurityParams/BladeEventBusConnectionFactory')
cmo.setAttachJMSXUserId(false)

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/ConnectionFactories/BladeEventBusConnectionFactory/ClientParams/BladeEventBusConnectionFactory')
cmo.setClientIdPolicy('Restricted')
cmo.setSubscriptionSharingPolicy('Exclusive')
cmo.setMessagesMaximum(10)

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/ConnectionFactories/BladeEventBusConnectionFactory/TransactionParams/BladeEventBusConnectionFactory')
cmo.setXAConnectionFactoryEnabled(true)

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/ConnectionFactories/BladeEventBusConnectionFactory')
cmo.setSubDeploymentName('BladeEventBusSubdeployment')
save()
activate()

# ── Uniform distributed TOPIC (pub/sub — the fan-out destination) ───────────
startEdit()
cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule')
if cmo.lookupUniformDistributedTopic('BladeEventBusTopic') is None:
    cmo.createUniformDistributedTopic('BladeEventBusTopic')

cd('/JMSSystemResources/BladeEventBusSystemModule/JMSResource/BladeEventBusSystemModule/UniformDistributedTopics/BladeEventBusTopic')
cmo.setJNDIName('jms/BladeEventBusTopic')
cmo.setSubDeploymentName('BladeEventBusSubdeployment')
save()
activate()

disconnect()
