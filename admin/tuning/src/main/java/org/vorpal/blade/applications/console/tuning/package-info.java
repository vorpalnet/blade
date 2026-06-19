/// REST API for tuning WebLogic and OCCAS server performance settings via JMX MBeans.
///
/// Provides endpoints for managing JVM heap and garbage collection, SIP protocol timers,
/// work manager thread constraints, server thread pools and socket readers, cluster topology
/// and Coherence cache configuration.
///
/// ## REST Endpoints
///
/// ### JVM Settings
/// [JvmSettings] exposes `/api/v1/jvm` for reading and updating JVM arguments on managed
/// servers including heap sizes (`-Xms`, `-Xmx`), metaspace limits, garbage collector
/// selection (G1GC, ZGC, ShenandoahGC, ParallelGC), GC tuning flags, and additional
/// JVM arguments. Parses `ServerStartMBean.Arguments` into structured JSON and reconstructs
/// the arguments string on update.
///
/// ### SIP Timers
/// [SipTimerSettings] exposes `/api/v1/sip-timers` for reading and updating SIP protocol
/// timer values (T1, T2, T4, Timer B through Timer M, Timer N) and protocol behaviors
/// stored in `sipserver.xml`. Supports tuning retransmission intervals, transaction
/// timeouts, and SIP-specific protocol settings.
///
/// ### Work Managers
/// [WorkManagerSettings] exposes `/api/v1/work-managers` for configuring OCCAS self-tuning
/// work managers and their min/max thread constraints. Covers nine WLSS work managers:
/// transport, timer, replica (RMI, blocking, geo), tracing (domain, local), connect,
/// and cleanup.
///
/// ### Server Tuning
/// [ServerTuningSettings] exposes `/api/v1/server-tuning` for per-server thread pool,
/// socket reader, maximum message size, complete message timeout, and idle connection
/// timeout settings.
///
/// ### Cluster
/// [ClusterSettings] exposes `/api/v1/cluster` for reading cluster topology, member
/// servers, migratable targets, and Coherence cache configuration.
///
/// ### Application Root
/// [RestApplication] is the JAX-RS `@ApplicationPath("/api/v1")` entry point that
/// registers all resource classes. Each resource declares a short `@Path` (e.g.
/// `@Path("/jvm")`) and the application path is prepended automatically — externally
/// URLs are `/blade/tuning/api/v1/jvm`, `/blade/tuning/api/v1/cluster`, and so on.
///
/// @see JvmSettings
/// @see SipTimerSettings
/// @see WorkManagerSettings
/// @see ServerTuningSettings
/// @see ClusterSettings
/// @see RestApplication
package org.vorpal.blade.applications.console.tuning;
