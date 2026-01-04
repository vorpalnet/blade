

wl_user = os.environ.get('WL_USER')
wl_pass = os.environ.get('WL_PASS')
wl_admin = os.environ.get('WL_ADMIN')

#connect('weblogic', 'welcome1', 't3://localhost:7001')
connect(wl_user, wl_pass, wl_admin)
edit()

startEdit()

cd('/')
cmo.createJMSServer('TestJMSServer')

cd('/JMSServers/TestJMSServer')
set('Targets',jarray.array([ObjectName('com.bea:Name=engine1,Type=Server')], ObjectName))

cd('/')
cmo.createJMSSystemResource('TestJMSModule')

cd('/JMSSystemResources/TestJMSModule')
set('Targets',jarray.array([ObjectName('com.bea:Name=engine1,Type=Server')], ObjectName))

cmo.createSubDeployment('TestSubdeployment')

cd('/JMSSystemResources/TestJMSModule/SubDeployments/TestSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=TestJMSServer,Type=JMSServer')], ObjectName))

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule')
cmo.createConnectionFactory('TestConnectionFactory')

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/ConnectionFactories/TestConnectionFactory')
cmo.setJNDIName('jms/TestConnectionFactory')

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/ConnectionFactories/TestConnectionFactory/SecurityParams/TestConnectionFactory')
cmo.setAttachJMSXUserId(false)

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/ConnectionFactories/TestConnectionFactory/ClientParams/TestConnectionFactory')
cmo.setClientIdPolicy('Restricted')
cmo.setSubscriptionSharingPolicy('Exclusive')
cmo.setMessagesMaximum(10)

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/ConnectionFactories/TestConnectionFactory/TransactionParams/TestConnectionFactory')
cmo.setXAConnectionFactoryEnabled(true)

cd('/JMSSystemResources/TestJMSModule/SubDeployments/TestSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=TestJMSServer,Type=JMSServer')], ObjectName))

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/ConnectionFactories/TestConnectionFactory')
cmo.setSubDeploymentName('TestSubdeployment')

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule')
cmo.createQueue('TestJMSQueue')

cd('/JMSSystemResources/TestJMSModule/JMSResource/TestJMSModule/Queues/TestJMSQueue')
cmo.setJNDIName('jms/TestJMSQueue')
cmo.setSubDeploymentName('TestSubdeployment')

cd('/JMSSystemResources/TestJMSModule/SubDeployments/TestSubdeployment')
set('Targets',jarray.array([ObjectName('com.bea:Name=TestJMSServer,Type=JMSServer')], ObjectName))

save()
activate()
disconnect()




