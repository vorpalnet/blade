# rolling-restart.py — drain-aware rolling restart of BLADE engine servers.
#
# For each named engine, in order:
#   1. DRAIN  — set Drained=true on its options-app Drain MBean
#               (vorpal.blade:Name=options,Type=Drain,...,Location=<engine>).
#               The node answers OPTIONS pings "503 Draining"; the load
#               balancer stops offering NEW calls within one ping interval.
#   2. WAIT   — sleep one LB ping interval, then poll OCCAS's own
#               SipServerRuntime PeriodCountSipThroughput until the node is
#               QUIET (no SIP work for two consecutive polls) or the timeout
#               passes. Active sessions do NOT need to reach zero — dialogs
#               fail over via the replicated state tier; throughput is the
#               safe-to-bounce signal.
#   3. BOUNCE — WLST shutdown(force)/start via Node Manager (the engine must
#               be NM-managed, as blade.sh sets up). The drain flag is
#               runtime state and dies with the JVM: the engine rejoins the
#               pool on its first successful ping after the app deploys.
#
# An engine with no Drain MBean (options app not deployed there) is SKIPPED —
# restarting an undrained node is not graceful, and this script never does it
# silently. Use the Tuning console's Restart button if you mean it.
#
# Usage (Jython 2.x under WLST):
#   $MW_HOME/oracle_common/common/bin/wlst.sh rolling-restart.py \
#       t3://adminhost:7001 <user> <password> engine1,engine2 [pingWaitSecs] [quietTimeoutSecs]
#
# Defaults: pingWaitSecs=75 (one 60s ping interval + margin), quietTimeoutSecs=120.

import sys
import time

from javax.management import ObjectName, Attribute
from java.lang import Boolean

if len(sys.argv) < 5:
    print 'usage: wlst.sh rolling-restart.py <adminurl> <user> <password> <engine1,engine2,...> [pingWaitSecs] [quietTimeoutSecs]'
    sys.exit(2)

adminUrl = sys.argv[1]
adminUser = sys.argv[2]
adminPassword = sys.argv[3]
engines = [e.strip() for e in sys.argv[4].split(',') if e.strip()]
pingWaitSecs = (len(sys.argv) > 5) and int(sys.argv[5]) or 75
quietTimeoutSecs = (len(sys.argv) > 6) and int(sys.argv[6]) or 120

DRAIN_PATTERN = ObjectName('vorpal.blade:Name=*,Type=Drain,*')
SIP_RUNTIME_PATTERN = ObjectName('com.bea:Type=SipServerRuntime,*')

connect(adminUser, adminPassword, adminUrl)
domainRuntime()
# `mbs` is WLST's connection to the Domain Runtime MBean Server, which
# federates every managed server's MBeans tagged Location=<server>.


def find_by_location(pattern, server):
    for on in mbs.queryNames(pattern, None):
        if on.getKeyProperty('Location') == server:
            return on
    return None


def throughput(server):
    """PeriodCountSipThroughput for the engine, or -1 when unreadable."""
    on = find_by_location(SIP_RUNTIME_PATTERN, server)
    if on is None:
        return -1
    try:
        return mbs.getAttribute(on, 'PeriodCountSipThroughput')
    except:
        return -1


def wait_quiet(server):
    """Two consecutive quiet polls, or proceed anyway at the timeout."""
    quiet = 0
    waited = 0
    while waited < quietTimeoutSecs:
        t = throughput(server)
        if t == 0:
            quiet = quiet + 1
            if quiet >= 2:
                print '  %s is quiet (no SIP work) — safe to bounce' % server
                return
        elif t < 0:
            print '  %s: throughput unreadable (SipServerRuntime absent?) — relying on the drain wait only' % server
            return
        else:
            quiet = 0
            print '  %s still working: throughput=%s' % (server, t)
        time.sleep(10)
        waited = waited + 10
    print '  WARNING: %s not quiet after %ss — proceeding anyway (calls fail over)' % (server, quietTimeoutSecs)


for engine in engines:
    print '=== %s ===' % engine

    drain = find_by_location(DRAIN_PATTERN, engine)
    if drain is None:
        print '  SKIPPED: no Drain MBean on %s (options app not deployed there?) —' % engine
        print '  refusing to restart an undrained engine. Restart it from the Tuning console if you mean it.'
        continue

    mbs.setAttribute(drain, Attribute('Drained', Boolean(1)))
    print '  drained: OPTIONS now answers 503 Draining; waiting %ss for the load balancer to react' % pingWaitSecs
    time.sleep(pingWaitSecs)

    wait_quiet(engine)

    print '  restarting %s (force shutdown, then start via Node Manager)...' % engine
    shutdown(engine, 'Server', force='true', block='true')
    start(engine, 'Server', block='true')
    print '  %s is back; it rejoins the pool on its next successful OPTIONS ping' % engine
    # The drain flag died with the old JVM — nothing to resume.

print 'Rolling restart complete: %s' % ', '.join(engines)
disconnect()
