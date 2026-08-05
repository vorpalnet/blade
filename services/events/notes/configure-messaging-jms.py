# -*- coding: utf-8 -*-
# Provisions the JMS resources for ALL BLADE messaging — the analytics queue and
# the event-bus topic — in ONE stack.
#
# Replaces the two near-identical scripts this merges:
#   services/analytics/notes/configure-jms.py    (now a pointer to this file)
#   services/events/notes/configure-events-jms.py  (deleted)
#
# Those two were the same six steps — file store, JMS server, system module,
# subdeployment, connection factory, destination — differing only on the last
# line (createUniformDistributedQueue vs createUniformDistributedTopic), with
# byte-identical connection-factory settings. That bought two file stores, two
# JMS servers, two modules and two subdeployments to host one queue and one
# topic, all of which fit in one of each.
#
# ── ADOPT, NEVER RENAME ──────────────────────────────────────────────────────
# WebLogic has no rename operation for JMS resources: renaming means destroy and
# re-create, which orphans the file store's persistent messages. So this script
# NEVER touches an existing BladeAnalytics* stack's identity. Instead:
#
#   * A domain that already has BladeAnalyticsSystemModule is ADOPTED — the
#     event topic is added to THAT module. Nothing is renamed, nothing is
#     destroyed, no downtime, no orphaned store.
#   * A fresh domain gets one canonical BladeMessaging* stack hosting both.
#
# Either way you end up with one store, one JMS server, one module and one
# subdeployment. Which names they carry depends only on what was already there.
#
# ── WHAT THIS PROVISIONS ─────────────────────────────────────────────────────
#
#   jms/BladeEventBusConnectionFactory    jms/BladeEventBusTopic
#
# One destination now. Analytics used to have a queue and a connection factory of
# its own; it is a subscriber on the topic like everything else.
#
# ── RETIRING THE ANALYTICS QUEUE ─────────────────────────────────────────────
# This script no longer creates jms/BladeAnalyticsDistributedQueue, and it does
# not delete an existing one either — destroying a destination discards whatever
# is still sitting in it, which is an operator's decision.
#
# On an upgraded domain the queue is dead but not empty. Two things to know
# before removing it:
#
#   1. Anything still in it CANNOT be replayed. The messages are Java-serialized
#      JPA entities from org.vorpal.blade.framework.v2.analytics.*, and those
#      classes moved to org.vorpal.blade.services.analytics.model.* — nothing can
#      deserialize them now. Draining is not an option; the choice is to keep
#      them as an unreadable archive or discard them.
#   2. Check the depth first, so the decision is made with a number:
#
#        Remote Console -> Monitoring -> JMS -> BladeAnalyticsDistributedQueue
#        (or the Destinations page of the BLADE Events console)
#
# Then, when you are ready:
#
#   * delete the UniformDistributedQueue BladeAnalyticsDistributedQueue
#   * delete the ConnectionFactory BladeAnalyticsConnectionFactory
#   * delete the Quota BladeAnalyticsQuota
#
# Leave the file store, JMS server, module and subdeployment alone — the topic
# lives in them. If the stack is named BladeAnalytics* because this script
# adopted it, that is only a name; see ADOPT, NEVER RENAME above.
#
# ── QUOTAS AND TIME-TO-LIVE ──────────────────────────────────────────────────
# The destination gets its own quota rather than drawing on the store's, so one
# destination cannot fill the shared store. Worth watching now that several
# applications subscribe: a durable subscription holds messages for a consumer
# that is down, that backlog counts against the DESTINATION, and one stalled
# subscriber can therefore push the topic to its quota and start failing
# publishes for everybody.
#
# The time-to-live override on the topic is what bounds that in TIME as the
# quota bounds it in bytes: the broker expires backlog older than the TTL
# instead of letting one dead subscriber hold the destination hostage until
# live publishes fail for applications that have nothing to do with it. The
# trade is stated where it is set — see the topic section below.
#
# Idempotent: every resource is created only if absent, so it is safe to re-run
# after a partial failure, or to add the topic to a domain that has only the
# queue.
#
# Set these before running:
#   WL_USER   weblogic
#   WL_PASS   welcome1
#   WL_ADMIN  t3://localhost:7001
#   BLADE_ENGINE_CLUSTER   (optional) engine-tier cluster name; defaults to
#                          BEA_ENGINE_TIER_CLUST. Find yours with ls('/Clusters').
#   BLADE_QUOTA_BYTES      (optional) per-destination byte quota; default 1 GiB.
#   BLADE_EVENT_TTL_MILLIS (optional) time-to-live override stamped on every
#                          message on the topic; default 24 hours. 0 would mean
#                          never expire — see the topic section before choosing it.
#
#   $MW_HOME/oracle_common/common/bin/wlst.sh configure-messaging-jms.py

# NOTE ON NAMING: every global here is blade_-prefixed on purpose. WLST pre-imports
# the weblogic.* Java packages into this namespace, so a bare global named "cluster"
# is silently shadowed by the weblogic.cluster PACKAGE -- the assignment appears to
# work, and the failure surfaces much later as
#   javax.management.InvalidAttributeValueException: Array has at least one null element
# from set('Targets', ...), because the ObjectName was built as
#   com.bea:Name=<java package weblogic.cluster>,Type=Cluster
# and resolves to null. "pwd" is shadowed the same way and breaks cd(). Prefix, or
# print the value before you trust it.

import os

wl_user = os.environ.get('WL_USER')
wl_pass = os.environ.get('WL_PASS')
wl_admin = os.environ.get('WL_ADMIN')
blade_cluster = os.environ.get('BLADE_ENGINE_CLUSTER', 'BEA_ENGINE_TIER_CLUST')
blade_quota_bytes = long(os.environ.get('BLADE_QUOTA_BYTES', '1073741824'))
blade_event_ttl_millis = long(os.environ.get('BLADE_EVENT_TTL_MILLIS', '86400000'))

connect(wl_user, wl_pass, wl_admin)

blade_target = ObjectName('com.bea:Name=%s,Type=Cluster' % blade_cluster)

# ── Decide which stack we are working in ────────────────────────────────────
# Preference order: an existing BladeMessaging stack, then an existing
# BladeAnalytics stack to adopt, then create BladeMessaging fresh.
edit()

# Discard an edit session left open by an earlier failed run. Without this a
# failure part-way through poisons every subsequent attempt, because startEdit()
# silently continues in the stale session instead of starting a clean one.
try:
    stopEdit('y')
    print('NOTE: discarded a pre-existing edit session')
except:
    pass

cd('/')

ADOPTED = cmo.lookupJMSSystemResource('BladeMessagingSystemModule') is None \
          and cmo.lookupJMSSystemResource('BladeAnalyticsSystemModule') is not None

if ADOPTED:
    STORE = 'BladeAnalyticsFileStore'
    STORE_DIR = 'BladeAnalytics'
    SERVER = 'BladeAnalyticsJMSServer'
    MODULE = 'BladeAnalyticsSystemModule'
    DESCRIPTOR = 'BladeAnalytics/BladeAnalytics-jms.xml'
    SUBDEPLOYMENT = 'BladeAnalyticsSubdeployment'
    print('ADOPTING the existing BladeAnalytics stack; the event topic will be added to it.')
    print('Nothing is renamed or destroyed. This is deliberate: WebLogic cannot rename a')
    print('JMS resource, and destroy-and-recreate would orphan the file store.')
else:
    STORE = 'BladeMessagingFileStore'
    STORE_DIR = 'BladeMessaging'
    SERVER = 'BladeMessagingJMSServer'
    MODULE = 'BladeMessagingSystemModule'
    DESCRIPTOR = 'BladeMessaging/BladeMessaging-jms.xml'
    SUBDEPLOYMENT = 'BladeMessagingSubdeployment'
    print('Using the canonical BladeMessaging stack.')

RESOURCE = '/JMSSystemResources/%s/JMSResource/%s' % (MODULE, MODULE)

# ── File store ──────────────────────────────────────────────────────────────
startEdit()
cd('/')
if cmo.lookupFileStore(STORE) is None:
    cmo.createFileStore(STORE)
cd('/FileStores/%s' % STORE)
cmo.setDirectory(STORE_DIR)
set('Targets', jarray.array([blade_target], ObjectName))
save()
activate()

# ── JMS server (persisted to the file store) ────────────────────────────────
startEdit()
cd('/')
if cmo.lookupJMSServer(SERVER) is None:
    cmo.createJMSServer(SERVER)
cd('/JMSServers/%s' % SERVER)
cmo.setPersistentStore(getMBean('/FileStores/%s' % STORE))
set('Targets', jarray.array([blade_target], ObjectName))
save()
activate()

# ── JMS system module ───────────────────────────────────────────────────────
startEdit()
cd('/')
if cmo.lookupJMSSystemResource(MODULE) is None:
    cmo.createJMSSystemResource(MODULE, DESCRIPTOR)
cd('/JMSSystemResources/%s' % MODULE)
set('Targets', jarray.array([blade_target], ObjectName))
save()
activate()

# ── Subdeployment (targets the JMS server) ──────────────────────────────────
blade_jms_server_target = ObjectName('com.bea:Name=%s,Type=JMSServer' % SERVER)
startEdit()
cd('/JMSSystemResources/%s' % MODULE)
if cmo.lookupSubDeployment(SUBDEPLOYMENT) is None:
    cmo.createSubDeployment(SUBDEPLOYMENT)
cd('/JMSSystemResources/%s/SubDeployments/%s' % (MODULE, SUBDEPLOYMENT))
set('Targets', jarray.array([blade_jms_server_target], ObjectName))
save()
activate()


def ensure_connection_factory(name, jndi):
    """Create a connection factory if absent and apply the standard settings.

    These values are identical to what both original scripts set, which is
    precisely why one module can host both: there was never anything to
    reconcile.
    """
    startEdit()
    cd(RESOURCE)
    if cmo.lookupConnectionFactory(name) is None:
        cmo.createConnectionFactory(name)

    cd('%s/ConnectionFactories/%s' % (RESOURCE, name))
    cmo.setJNDIName(jndi)

    cd('%s/ConnectionFactories/%s/SecurityParams/%s' % (RESOURCE, name, name))
    cmo.setAttachJMSXUserId(false)

    # Restricted/Exclusive are WebLogic's defaults, and they stay.
    #
    # These looked like a problem for a bus several applications subscribe to.
    # Oracle's own text for ClientIdPolicy, shipped in
    # wlserver/modules/com.bea.core.descriptor.wl.jar, says Restricted means
    # "only one connection that uses this policy can exist in a blade_cluster at any
    # given time for a particular Client ID ... attempts to create new
    # connections using this policy with the same Client ID fail with an
    # exception" — and every member of a blade_cluster attaches with the same client
    # id under distributedDestinationConnection=EveryMember. On that reading it
    # would fail with ONE subscribing application, never mind two.
    #
    # It does not, because the MDB container does not use these values.
    # weblogic.ejb.container.internal.JMSConnectionPoller checks
    # supportsMultipleConnections(destination) — true for a distributed topic
    # whose topicMessagesDistributionMode is One-Copy-Per-Server (1) or
    # One-Copy-Per-Application (2) — and on that branch calls
    #
    #     WLConnection.setClientID(id, CLIENT_ID_POLICY_UNRESTRICTED)
    #     WLConnection.setSubscriptionSharingPolicy(SUBSCRIPTION_SHARABLE)
    #
    # overriding the factory per connection. That is the documented override
    # path ("applications can override the Client ID Policy ... by casting a
    # javax.jms.Connection to WLConnection and calling setClientID(clientID,
    # clientIDPolicy)"), used by the container on our behalf. Every consumer
    # BLADE generates is One-Copy-Per-Application on a UniformDistributedTopic,
    # so every one of them takes that branch.
    #
    # So relaxing these here would buy nothing and cost something: it is a
    # domain-wide setting, and it would also stop protecting any non-MDB code
    # that sets a client id. BLADE's publisher never does — EventPublisher does
    # not call setClientID at all — but a future one might, and the default is
    # the safer place for it to land.
    #
    # ONE CONSEQUENCE TO CARRY: because the container uses an Unrestricted
    # client id, a durable subscription it creates cannot be removed with
    # javax.jms.Session.unsubscribe(name). Oracle: "Durable subscriptions
    # created using an Unrestricted Client Id must be unsubscribed using
    # weblogic.jms.extensions.WLSession.unsubscribe(Topic topic, String name)".
    # That is the call an orphan cleanup has to make.
    cd('%s/ConnectionFactories/%s/ClientParams/%s' % (RESOURCE, name, name))
    cmo.setClientIdPolicy('Restricted')
    cmo.setSubscriptionSharingPolicy('Exclusive')

    # Per consumer, so aggregate prefetch grows with the number of subscribers.
    cmo.setMessagesMaximum(10)

    cd('%s/ConnectionFactories/%s/TransactionParams/%s' % (RESOURCE, name, name))
    cmo.setXAConnectionFactoryEnabled(true)

    cd('%s/ConnectionFactories/%s' % (RESOURCE, name))
    cmo.setSubDeploymentName(SUBDEPLOYMENT)
    save()
    activate()


def ensure_quota(name):
    """A per-destination quota, so one destination cannot fill the shared store."""
    startEdit()
    cd(RESOURCE)
    if cmo.lookupQuota(name) is None:
        cmo.createQuota(name)
    cd('%s/Quotas/%s' % (RESOURCE, name))
    cmo.setBytesMaximum(blade_quota_bytes)
    cmo.setPolicy('FIFO')
    cmo.setShared(false)
    save()
    activate()
    return '%s/Quotas/%s' % (RESOURCE, name)


# ── Connection factory ──────────────────────────────────────────────────────
ensure_connection_factory('BladeEventBusConnectionFactory', 'jms/BladeEventBusConnectionFactory')

# ── Quota ───────────────────────────────────────────────────────────────────
eventbus_quota = ensure_quota('BladeEventBusQuota')

# ── The analytics QUEUE is no longer provisioned ────────────────────────────
# Analytics is a subscriber on the topic below, like everything else. It used to
# have a queue and a connection factory of its own, carrying Java-serialized JPA
# entities — which meant a consumer had to be on BLADE's classpath to read a
# message at all. Nothing publishes to that queue and nothing consumes from it.
#
# This script does NOT delete an existing one, deliberately: destroying a
# destination discards whatever is still in it, and that is an operator's call
# rather than a provisioning script's. See RETIRING THE ANALYTICS QUEUE at the
# top of this file for what to check and how to remove it.

# ── The event-bus TOPIC (pub/sub; every subscribing app gets its own copy) ───
startEdit()
cd(RESOURCE)
if cmo.lookupUniformDistributedTopic('BladeEventBusTopic') is None:
    cmo.createUniformDistributedTopic('BladeEventBusTopic')
cd('%s/UniformDistributedTopics/BladeEventBusTopic' % RESOURCE)
# Partitioned, not the Replicated default. Two reasons, and the first is not
# optional:
#
#   1. WebLogic refuses the combination outright -- a cluster-targeted JMS server
#      cannot host a Replicated Distributed Topic:
#        [JMSExceptions:045116] The Replicated Distributed Topic (RDT) cannot have
#        its members hosted by a cluster targeted JMSServer.
#      and it fails at save(), after every other resource has been created, so a
#      run that gets this wrong leaves a half-built stack behind.
#   2. Partitioned is the documented pairing for the delivery BLADE wants. Every
#      consumer the framework generates is One-Copy-Per-Application, and Oracle
#      pairs that mode with a PDT so each subscribing application receives each
#      event exactly once across the cluster rather than once per member.
cmo.setForwardingPolicy('Partitioned')
cmo.setJNDIName('jms/BladeEventBusTopic')
cmo.setSubDeploymentName(SUBDEPLOYMENT)
cmo.setQuota(getMBean(eventbus_quota))

# Time-to-live override: the broker expires anything on this destination older
# than the TTL, whatever the producer asked for (BLADE's publisher asks for
# nothing, i.e. live forever). Oracle's attribute doc (DeliveryParamsOverridesBean
# in com.bea.core.descriptor.wl.jar): "The time-to-live assigned to all messages
# that arrive at this destination, regardless of the TimeToLive value specified
# by the message producer... only incoming messages are impacted; stored
# messages are not." So re-running this on a domain that already has backlog
# does NOT expire the backlog — only what arrives from then on.
#
# This is the TIME bound on the quota problem described at the top of the file.
# The quota is shared by every durable subscription on the topic, so without a
# TTL a subscriber that stays down accrues backlog until PUBLISHES start
# failing — for every application on the bus, on the SIP container thread,
# silently (Analytics swallows the exception; the events.publish.failures
# counter and one warning line are the only trace).
#
# THE TRADE: a subscriber down longer than the TTL loses the events older than
# it. That is deliberately the lesser evil — the analytics sink already
# discards while its database is unreachable rather than queue forever, and an
# ACTOR that wakes up to day-old facts should not act on them anyway (its
# subscriptions are advised non-durable for exactly that reason). Losing
# yesterday's analytics beats dropping today's live events for everyone.
cd('%s/UniformDistributedTopics/BladeEventBusTopic/DeliveryParamsOverrides/BladeEventBusTopic' % RESOURCE)
cmo.setTimeToLive(blade_event_ttl_millis)
save()
activate()

print('')
print('BLADE messaging provisioned in one stack:')
print('  file store    %s' % STORE)
print('  JMS server    %s' % SERVER)
print('  module        %s' % MODULE)
print('  subdeployment %s' % SUBDEPLOYMENT)
print('  destination   jms/BladeEventBusTopic (topic, quota %d bytes, ttl %d ms)'
      % (blade_quota_bytes, blade_event_ttl_millis))
if ADOPTED:
    print('')
    print('The event topic was adopted into the existing analytics module. If a separate')
    print('BladeEventBus* stack exists from an earlier run of configure-events-jms.py,')
    print('it is now redundant and can be removed once nothing is bound to it — check')
    print('the Events console before deleting anything.')

disconnect()
