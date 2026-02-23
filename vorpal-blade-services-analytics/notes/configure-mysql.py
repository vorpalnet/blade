# This script configures a MySQL JDBC data source for writing analytics events.
# Temporarily, set the following environment variables:

wl_user = os.environ.get('WL_USER')   # weblogic
wl_pass = os.environ.get('WL_PASS')   # welcome1
wl_admin = os.environ.get('WL_ADMIN') # t3://localhost:7001
db_user = os.environ.get('DB_USER')   # blade
db_pass = os.environ.get('DB_PASS')   # blade123

connect(wl_user, wl_pass, wl_admin)
edit()


#---
cd('/')
cmo.createJDBCSystemResource('BladeAnalytics')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics')
cmo.setName('BladeAnalytics')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDataSourceParams/BladeAnalytics')
set('JNDINames',jarray.array([String('jdbc/BladeAnalytics')], String))

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics')
cmo.setDatasourceType('GENERIC')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics')
cmo.setUrl('jdbc:mysql://localhost:3306/vorpal')
cmo.setDriverName('com.mysql.cj.jdbc.MysqlXADataSource')
set('Password', db_pass)

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCConnectionPoolParams/BladeAnalytics')
cmo.setTestTableName('SQL SELECT 1\r\n\r\n')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics')
cmo.createProperty('user')

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics/Properties/user')
cmo.setValue(db_user)

cd('/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics/JDBCDataSourceParams/BladeAnalytics')
cmo.setGlobalTransactionsProtocol('TwoPhaseCommit')

cd('/JDBCSystemResources/BladeAnalytics')
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

save()
activate()
disconnect()


