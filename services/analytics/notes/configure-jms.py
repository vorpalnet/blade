# This script configures a JMS queue for receiving analytics events.
# Temporarily, set the following environment variables:

wl_user = os.environ.get('WL_USER')   # weblogic
wl_pass = os.environ.get('WL_PASS')   # welcome1
wl_admin = os.environ.get('WL_ADMIN') # t3://localhost:7001

connect(wl_user, wl_pass, wl_admin)

edit()

cd('/')
cmo.createFileStore('BladeAnalyticsFileStore')

cd('/FileStores/BladeAnalyticsFileStore')
cmo.setDirectory('BladeAnalytics')
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

activate()

startEdit()

cd('/')
cmo.createJMSServer('BladeAnalyticsJMSServer')

cd('/JMSServers/BladeAnalyticsJMSServer')
cmo.setPersistentStore(getMBean('/FileStores/BladeAnalyticsFileStore'))
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

activate()

startEdit()

cd('/')
cmo.createJMSSystemResource('BladeAnalyticsSystemModule', 'BladeAnalytics/BladeAnalytics-jms.xml')

cd('/JMSSystemResources/BladeAnalyticsSystemModule')
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

activate()

startEdit()
cmo.createSubDeployment('BladeAnalyticsSubdeployment')

cd('/JMSSystemResources/BladeAnalyticsSystemModule/SubDeployments/BladeAnalyticsSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=BladeAnalyticsJMSServer,Type=JMSServer')], ObjectName))

activate()

startEdit()

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule')
cmo.createConnectionFactory('BladeAnalyticsConnectionFactory')

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/ConnectionFactories/BladeAnalyticsConnectionFactory')
cmo.setJNDIName('jms/BladeAnalyticsConnectionFactory')

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/ConnectionFactories/BladeAnalyticsConnectionFactory/SecurityParams/BladeAnalyticsConnectionFactory')
cmo.setAttachJMSXUserId(false)

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/ConnectionFactories/BladeAnalyticsConnectionFactory/ClientParams/BladeAnalyticsConnectionFactory')
cmo.setClientIdPolicy('Restricted')
cmo.setSubscriptionSharingPolicy('Exclusive')
cmo.setMessagesMaximum(10)

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/ConnectionFactories/BladeAnalyticsConnectionFactory/TransactionParams/BladeAnalyticsConnectionFactory')
cmo.setXAConnectionFactoryEnabled(true)

cd('/JMSSystemResources/BladeAnalyticsSystemModule/SubDeployments/BladeAnalyticsSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=BladeAnalyticsJMSServer,Type=JMSServer')], ObjectName))

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/ConnectionFactories/BladeAnalyticsConnectionFactory')
cmo.setSubDeploymentName('BladeAnalyticsSubdeployment')

activate()

startEdit()

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule')
cmo.createUniformDistributedQueue('BladeAnalyticsDistributedQueue')

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/UniformDistributedQueues/BladeAnalyticsDistributedQueue')
cmo.setJNDIName('jms/BladeAnalyticsDistributedQueue')

cd('/JMSSystemResources/BladeAnalyticsSystemModule/SubDeployments/BladeAnalyticsSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=BladeAnalyticsJMSServer,Type=JMSServer')], ObjectName))

cd('/JMSSystemResources/BladeAnalyticsSystemModule/JMSResource/BladeAnalyticsSystemModule/UniformDistributedQueues/BladeAnalyticsDistributedQueue')
cmo.setSubDeploymentName('BladeAnalyticsSubdeployment')


save()
activate()
disconnect()




