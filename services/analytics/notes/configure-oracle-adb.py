# This script configures an Oracle Autonomous Database JDBC data source for
# writing analytics events (the Oracle counterpart of configure-mysql.py).
#
# ADB connection style is mTLS: download the instance wallet (`oci db
# autonomous-database generate-wallet`), unzip it on every server node, and
# reference it via TNS_ADMIN in the URL. The auto-login wallet (cwallet.sso)
# needs no password at connect time but does need oraclepki.jar on the server
# classpath -- WebLogic 12.2.1.4 (oracle_common/modules/oracle.pki) has it.
#
# The datasource is non-XA with OnePhaseCommit: the consumer MDB receives
# messages on WebLogic's transactional path, which enlists the connection in
# the (single-resource) global transaction. 'None' breaks the MDB with
# "Cannot obtain XAConnection"; full XA buys nothing for one resource.
#
# InitialCapacity 0 + TestConnectionsOnReserve matter operationally: an
# Always Free ADB auto-stops after 7 idle days; a cold pool must not wedge
# server startup, and a woken database must not serve stale connections.
#
# Temporarily, set the following environment variables:

wl_user = os.environ.get('WL_USER')     # weblogic
wl_pass = os.environ.get('WL_PASS')     # welcome1
wl_admin = os.environ.get('WL_ADMIN')   # t3://localhost:7001
db_user = os.environ.get('DB_USER')     # BLADE
db_pass = os.environ.get('DB_PASS')     # the ADB schema password
db_url = os.environ.get('DB_URL')       # jdbc:oracle:thin:@<db>_tp?TNS_ADMIN=<wallet dir>

connect(wl_user, wl_pass, wl_admin)
edit()
startEdit()

cd('/')
cmo.createJDBCSystemResource('BladeAnalytics')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics')
cmo.setName('BladeAnalytics')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDataSourceParams/BladeAnalytics')
set('JNDINames',jarray.array([String('jdbc/BladeAnalytics')], String))
cmo.setGlobalTransactionsProtocol('OnePhaseCommit')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics')
cmo.setDatasourceType('GENERIC')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics')
cmo.setUrl(db_url)
cmo.setDriverName('oracle.jdbc.OracleDriver')
set('Password', db_pass)

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCConnectionPoolParams/BladeAnalytics')
cmo.setTestTableName('SQL SELECT 1 FROM DUAL')
cmo.setTestConnectionsOnReserve(true)
cmo.setInvokeBeginEndRequest(false)
cmo.setInitialCapacity(0)
cmo.setMinCapacity(0)
cmo.setMaxCapacity(15)

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics')
cmo.createProperty('user')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics/Properties/user')
cmo.setValue(db_user)

cd('/JDBCSystemResources/BladeAnalytics')
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

save()
activate()
disconnect()
