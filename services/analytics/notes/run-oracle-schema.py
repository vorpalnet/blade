# -*- coding: utf-8 -*-
#
# Run sql/Oracle-database-schema.sql, then sql/Oracle-analytics-views.sql, against
# the Autonomous Database.
#
# The schema file is drop-and-recreate: a PL/SQL block at the top drops all seven
# tables with CASCADE CONSTRAINTS, then rebuilds them. So this is the whole reset --
# there is no migration step and nothing to preserve. It is DESTRUCTIVE by design.
#
# Why this exists: the OCCAS boxes have no sqlplus and no SQLcl, so there was no way
# to execute the DDL. WLST runs Jython on a JVM that already has the Oracle thin
# driver on its classpath, so plain JDBC gets the job done with nothing to install.
#
# ── SPLITTING ────────────────────────────────────────────────────────────────
# The file mixes ordinary statements terminated by ';' with a PL/SQL block
# terminated by a lone '/'. JDBC takes one statement at a time and wants no
# trailing ';', so both forms are handled here rather than assuming one.
#
# ── SET THESE ────────────────────────────────────────────────────────────────
#   DB_USER   the ADB schema user (the DDL runs AS this user)
#   DB_PASS   its password
#   DB_URL    jdbc:oracle:thin:@<db>_tp?TNS_ADMIN=<wallet dir>
#   BLADE_CONFIRM_DROP=yes   required; this drops tables, so it will not run by
#                            accident on a database somebody cares about
#   BLADE_VIEWS_ONLY=yes     skip the schema and only (re)create the views. This
#                            is the upgrade step: the views file only replaces
#                            views, keeps every row, and needs no confirmation.
#                            Run it after every BLADE upgrade, because new event
#                            types arrive with new views.
#
#   $MW_HOME/oracle_common/common/bin/wlst.sh run-oracle-schema.py
#
# NOTE ON NAMING: globals are blade_-prefixed because WLST pre-imports the
# weblogic.* Java packages into this namespace; a bare global like 'cluster' or
# 'pwd' is silently shadowed and fails much later with an unrelated error.

import os
import re
from java.sql import DriverManager

blade_user = os.environ.get('DB_USER')
blade_pass = os.environ.get('DB_PASS')
blade_url = os.environ.get('DB_URL')
blade_confirm = os.environ.get('BLADE_CONFIRM_DROP', '')
blade_views_only = os.environ.get('BLADE_VIEWS_ONLY', '') == 'yes'


def blade_find(name, override):
    """WLST does not define __file__, so the script cannot locate itself. Look in
    the usual places relative to the working directory; `override` wins."""
    blade_path = os.environ.get(override)
    if blade_path:
        return blade_path
    for blade_dir in ['services/analytics/sql/', '../sql/', 'sql/']:
        if os.path.exists(blade_dir + name):
            return blade_dir + name
    return None


blade_sql_file = blade_find('Oracle-database-schema.sql', 'BLADE_SQL_FILE')
blade_views_file = blade_find('Oracle-analytics-views.sql', 'BLADE_VIEWS_FILE')
for blade_name, blade_file, blade_needed in [('Oracle-database-schema.sql', blade_sql_file, not blade_views_only),
                                             ('Oracle-analytics-views.sql', blade_views_file, True)]:
    if blade_needed and (not blade_file or not os.path.exists(blade_file)):
        print('FATAL: cannot find %s. Run from the repo root,' % blade_name)
        print('       or set BLADE_SQL_FILE / BLADE_VIEWS_FILE to its full path.')
        raise SystemExit(2)

if not blade_user or not blade_pass or not blade_url:
    print('FATAL: set DB_USER, DB_PASS and DB_URL')
    raise SystemExit(2)

if not blade_views_only and blade_confirm != 'yes':
    print('REFUSING: this DROPS every analytics table and recreates it empty.')
    print('          Re-run with BLADE_CONFIRM_DROP=yes if that is what you want,')
    print('          or with BLADE_VIEWS_ONLY=yes to only (re)create the views.')
    raise SystemExit(2)


def blade_statements(path):
    """Split the DDL into JDBC-executable statements.

    Two terminators appear in this file: ';' for ordinary statements and a lone '/'
    for the PL/SQL block. A ';' inside PL/SQL ends a line of the block, not the
    block, so once a block is open only '/' closes it.
    """
    blade_out = []
    blade_buf = []
    blade_in_block = False
    for blade_raw in open(path).read().split('\n'):
        blade_line = blade_raw.rstrip()
        blade_bare = blade_line.strip()
        if not blade_in_block and (blade_bare.startswith('--') or blade_bare == ''):
            continue
        if re.match(r'^\s*(BEGIN|DECLARE|CREATE\s+(OR\s+REPLACE\s+)?(TRIGGER|FUNCTION|PROCEDURE|PACKAGE))',
                    blade_bare, re.I):
            blade_in_block = True
        if blade_bare == '/':
            blade_out.append('\n'.join(blade_buf))
            blade_buf = []
            blade_in_block = False
            continue
        blade_buf.append(blade_line)
        if not blade_in_block and blade_bare.endswith(';'):
            blade_text = '\n'.join(blade_buf).rstrip()
            blade_out.append(blade_text[:-1])   # JDBC wants no trailing semicolon
            blade_buf = []
    if '\n'.join(blade_buf).strip():
        blade_out.append('\n'.join(blade_buf))
    return [s for s in blade_out if s.strip()]


blade_files = ([] if blade_views_only else [blade_sql_file]) + [blade_views_file]
blade_stmts = []
for blade_file in blade_files:
    blade_these = blade_statements(blade_file)
    print('%s: %d statements' % (blade_file, len(blade_these)))
    blade_stmts.extend(blade_these)

blade_conn = DriverManager.getConnection(blade_url, blade_user, blade_pass)
blade_conn.setAutoCommit(1)
print('connected as %s' % blade_user)

blade_ok = 0
blade_failed = 0
try:
    for blade_sql in blade_stmts:
        blade_first = ' '.join(blade_sql.split())[:70]
        blade_st = blade_conn.createStatement()
        try:
            blade_st.execute(blade_sql)
            blade_ok += 1
            print('  ok   %s' % blade_first)
        except Exception, blade_e:
            blade_failed += 1
            print('  FAIL %s' % blade_first)
            print('       %s' % str(blade_e)[:160])
        finally:
            blade_st.close()

    # Report what actually exists now, rather than trusting the run.
    print('')
    print('tables now present:')
    blade_st = blade_conn.createStatement()
    # The schema's four tables, plus the three it used to have. Listing the
    # retired ones is deliberate: re-running this over an older deployment
    # should show them GONE, and a leftover here means the drop block did not
    # reach them.
    blade_rs = blade_st.executeQuery(
        "SELECT table_name FROM user_tables WHERE table_name IN "
        "('APPLICATIONS','SESSIONS','SESSION_KEYS','EVENTS',"
        "'EVENT_TYPES','ATTRIBUTE_NAMES','ATTRIBUTES') ORDER BY table_name")
    blade_found = []
    while blade_rs.next():
        blade_found.append(blade_rs.getString(1))
    blade_rs.close()
    blade_st.close()
    for blade_t in blade_found:
        print('  %s' % blade_t)
    print('')
    print('%d ok, %d failed, %d of 4 tables present' % (blade_ok, blade_failed, len(blade_found)))

    # The views, and whether each compiled. INVALID means it references a
    # column or table this schema does not have.
    blade_st = blade_conn.createStatement()
    blade_rs = blade_st.executeQuery(
        "SELECT object_name, status FROM user_objects WHERE object_type = 'VIEW' "
        "AND object_name LIKE 'V\\_%' ESCAPE '\\' ORDER BY object_name")
    print('')
    print('views now present:')
    while blade_rs.next():
        print('  %-24s %s' % (blade_rs.getString(1), blade_rs.getString(2)))
    blade_rs.close()
    blade_st.close()
    if len(blade_found) != 4:
        print('WARNING: expected exactly the four tables APPLICATIONS, EVENTS,')
        print('         SESSIONS, SESSION_KEYS. Anything else listed above is a')
        print('         retired table the drop block failed to remove.')
finally:
    blade_conn.close()
