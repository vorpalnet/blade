
startEdit()

cd('/')
cmo.createJDBCSystemResource('BladeCDR')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR')
cmo.setName('BladeCDR')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCDataSourceParams/BladeCDR')
set('JNDINames',jarray.array([String('jdbc/BladeCDR')], String))

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR')
cmo.setDatasourceType('GENERIC')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCDriverParams/BladeCDR')
cmo.setUrl('jdbc:mysql://localhost:3306/vorpal')
cmo.setDriverName('com.mysql.cj.jdbc.Driver')
setEncrypted('Password', 'Password_1754412461451', '/Users/jeff/Oracle/occas-8.1/user_projects/domains/gamera/Script1754412054124Config', '/Users/jeff/Oracle/occas-8.1/user_projects/domains/gamera/Script1754412054124Secret')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCConnectionPoolParams/BladeCDR')
cmo.setTestTableName('SQL SELECT 1\r\n\r\n')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCDriverParams/BladeCDR/Properties/BladeCDR')
cmo.createProperty('user')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCDriverParams/BladeCDR/Properties/BladeCDR/Properties/user')
cmo.setValue('blade')

cd('/JDBCSystemResources/BladeCDR/JDBCResource/BladeCDR/JDBCDataSourceParams/BladeCDR')
cmo.setGlobalTransactionsProtocol('OnePhaseCommit')

cd('/JDBCSystemResources/BladeCDR')
set('Targets',jarray.array([ObjectName('com.bea:Name=BEA_ENGINE_TIER_CLUST,Type=Cluster')], ObjectName))

activate()

startEdit()
