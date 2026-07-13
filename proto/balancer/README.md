# Balancer Health (admin app)

[Javadocs](https://vorpal.net/javadocs/blade/balancer)

Endpoint-health dashboard for the [proxy-balancer service](../../services/proxy-balancer):
every engine node's independent view of every plan/tier/endpoint — UP, DOWN, or
in 503 Retry-After BACKOFF — read live over federated DomainRuntime JMX from the
per-node `vorpal.blade:Name=proxy-balancer,Type=EndpointHealth` MBeans.

Status: **proto/** incubator — built by the `full` profile, deployed by hand,
not shipped in `blade-admin.ear`. Promote to `admin/` once proven.

- Context root: `blade/balancer` (AdminServer target)
- REST: `GET /blade/balancer/api/health` — every node's health JSON
- Page: `health.html` — plan/tier/endpoint rows × engine-node columns;
  status is shape + text (● UP / ■ DOWN / ◆ BACKOFF), color only reinforces
- Nodes ping and mark independently; a single disagreeing column is a
  network-path diagnostic, not an endpoint outage
