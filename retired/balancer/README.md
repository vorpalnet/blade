# Balancer Health (admin app)

Javadocs: `/blade/javadoc/balancer/` on the Admin Portal

Endpoint-health dashboard for the [proxy-balancer service](../../services/proxy-balancer):
every engine node's independent view of every plan/tier/endpoint — UP, DOWN, or
in 503 Retry-After BACKOFF — read live over federated DomainRuntime JMX from the
per-node `vorpal.blade:Name=proxy-balancer,Type=EndpointHealth` MBeans.

Status: **retired** (2026-10-17). The dashboard's Traffic map shows the same MBeans: each
endpoint's state across the engines, a 503 back-off, the calls it was offered, and the endpoints
no site claims. Not built; kept for reference.

- Context root: `blade/balancer` (AdminServer target)
- REST: `GET /blade/balancer/api/health` — every node's health JSON
- Page: `health.html` — plan/tier/endpoint rows × engine-node columns;
  status is shape + text (● UP / ■ DOWN / ◆ BACKOFF), color only reinforces
- Nodes ping and mark independently; a single disagreeing column is a
  network-path diagnostic, not an endpoint outage
