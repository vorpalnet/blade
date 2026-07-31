# -*- coding: utf-8 -*-
# MOVED — and analytics no longer has a destination of its own.
#
# This script used to stand up a private JMS stack for analytics: its own file
# store, JMS server, system module, subdeployment and connection factory, to
# host one queue carrying Java-serialized JPA entities.
#
# Analytics is now one subscriber on the shared event-bus topic, like every
# other consumer, so there is one destination for the whole domain and one
# script that provisions it:
#
#     services/events/notes/configure-messaging-jms.py
#
# It ADOPTS an existing BladeAnalytics* stack rather than renaming it — WebLogic
# cannot rename a JMS resource, and destroy-and-recreate would orphan the file
# store — so running it on a domain provisioned by the old script is safe. The
# stack keeps its name; only the destination changes.
#
# The old queue is NOT deleted by that script. Anything still sitting in it
# cannot be replayed — the entity classes moved packages, so nothing can
# deserialize them — so removing it is a decision, not a migration step. The
# runbook is in the header of configure-messaging-jms.py.
#
# Prefer the Events console (/blade/events) over WLST; this is the fallback.
