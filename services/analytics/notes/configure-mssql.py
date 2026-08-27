# -*- coding: utf-8 -*-
# Provision the BladeAnalytics JDBC data source against Microsoft SQL Server.
#
#   $MW_HOME/oracle_common/common/bin/wlst.sh configure-mssql.py
#
# Environment:
#   WL_USER, WL_PASS, WL_ADMIN   the AdminServer connection
#   DB_USER, DB_PASS             the SQL Server login
#   DB_URL                       optional; see the default below
#   BLADE_ENGINE_CLUSTER         optional; defaults to BEA_ENGINE_TIER_CLUST
#
# Credentials are read from the environment and never passed on a command line.
#
# ── The driver ──────────────────────────────────────────────────────────────
#
# WebLogic ships a DataDirect SQL Server driver
# (weblogic.jdbcx.sqlserver.SQLServerDataSource) and it is present without
# installing anything. This script uses it FOR THAT REASON — it is the choice
# that works on a fresh domain.
#
# Microsoft's own driver (com.microsoft.sqlserver.jdbc.SQLServerXADataSource,
# from mssql-jdbc) is the alternative, and is what to reach for if the
# deployment needs something DataDirect does not carry: Azure AD / Entra
# authentication, Always Encrypted, or a TLS posture their DBAs mandate. It has
# to be dropped into the domain's classpath first, which is a server change
# rather than a configuration one. Set DB_DRIVER to switch.
#
# ── One-phase, not two ──────────────────────────────────────────────────────
#
# GlobalTransactionsProtocol is OnePhaseCommit, matching the Oracle ADB
# provisioner and differing from the MySQL one. The consumer enlists exactly
# one resource — this database — inside its own transaction; there is no second
# resource to coordinate with, and XA would buy nothing for the cost of a
# two-phase protocol on every write. The JMS side is acknowledged separately by
# the subscriber, deliberately: see EventSubscriber.

import os
from java.lang import String
from javax.management import ObjectName
import jarray

wl_user = os.environ.get('WL_USER')
wl_pass = os.environ.get('WL_PASS')
wl_admin = os.environ.get('WL_ADMIN')
db_user = os.environ.get('DB_USER')
db_pass = os.environ.get('DB_PASS')

blade_cluster = os.environ.get('BLADE_ENGINE_CLUSTER', 'BEA_ENGINE_TIER_CLUST')

# encrypt=true is the default in recent drivers and increasingly mandatory on a
# corporate SQL Server. trustServerCertificate=false is correct; set it true
# only against a development instance with a self-signed certificate, and say so
# out loud when you do.
blade_url = os.environ.get(
    'DB_URL',
    'jdbc:weblogic:sqlserver://sqlserver.example_co.internal:1433;databaseName=vorpal;'
    'encryptionMethod=SSL;validateServerCertificate=true')
blade_driver = os.environ.get('DB_DRIVER', 'weblogic.jdbcx.sqlserver.SQLServerDataSource')

if not db_user or not db_pass:
    raise Exception('set DB_USER and DB_PASS')

print('db user   : %s' % db_user)
print('db url    : %s' % blade_url)
print('db driver : %s' % blade_driver)
print('cluster   : %s' % blade_cluster)

connect(wl_user, wl_pass, wl_admin)
edit()
startEdit()

blade_base = '/JDBCSystemResources/BladeAnalytics/JDBCResource/BladeAnalytics'

cd('/')
if cmo.lookupJDBCSystemResource('BladeAnalytics') is None:
    cmo.createJDBCSystemResource('BladeAnalytics')

cd(blade_base)
cmo.setName('BladeAnalytics')
cmo.setDatasourceType('GENERIC')

cd(blade_base + '/JDBCDataSourceParams/BladeAnalytics')
set('JNDINames', jarray.array([String('jdbc/BladeAnalytics')], String))
cmo.setGlobalTransactionsProtocol('OnePhaseCommit')

cd(blade_base + '/JDBCDriverParams/BladeAnalytics')
cmo.setUrl(blade_url)
cmo.setDriverName(blade_driver)
set('Password', db_pass)

cd(blade_base + '/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics')
if cmo.lookupProperty('user') is None:
    cmo.createProperty('user')
cd(blade_base + '/JDBCDriverParams/BladeAnalytics/Properties/BladeAnalytics/Properties/user')
cmo.setValue(db_user)

cd(blade_base + '/JDBCConnectionPoolParams/BladeAnalytics')
# A cheap statement the pool can run to prove a borrowed connection is alive.
# The leading 'SQL ' is WebLogic's marker for "this is a query, not a table
# name" — without it the server treats the rest as a table to select from.
cmo.setTestTableName('SQL SELECT 1')
cmo.setTestConnectionsOnReserve(true)
# A connection that has been idle across a firewall's timeout is dead but still
# looks open. Test-on-reserve above catches it; this bounds how long a dead one
# is kept before that happens.
cmo.setSecondsToTrustAnIdlePoolConnection(0)

cd('/JDBCSystemResources/BladeAnalytics')
set('Targets', jarray.array([ObjectName('com.bea:Name=%s,Type=Cluster' % blade_cluster)], ObjectName))

save()
activate()

print('')
print('BladeAnalytics data source provisioned against SQL Server.')
print('  Next: run sql/MSSQL-database-schema.sql as the schema owner, then')
print('        restart or redeploy the analytics application and check its')
print('        event-bus MBean (vorpal.blade:Type=EventBus,Name=analytics)')
print('        for a subscription with consumers=1.')
disconnect()
