# Channel Model Retirement

V63 removes `channel_models`. Runtime model access comes from service groups, group models,
group suppliers and group credentials, followed by a channel supporting the requested operation.

Active legacy transport options are migrated to `channels.metadata.upstream_models`, keyed by
platform model UUID. Each optional entry preserves `upstream_model`, `config`, `cost_input_price`,
`cost_cached_input_price` and `cost_output_price`. Entries with only defaults are omitted.
These settings do not make a model available or affect route priority, weight or concurrency.
Channel edits preserve metadata. Missing options use the public model name, empty config and zero costs.

Disabled legacy settings are not activated by migration. Duplicate active mappings or a pre-existing
`upstream_models` metadata key abort the transactional migration for review. Model aliases such as
`seedance-2.5` to `bytedance/seedance-2.5` are preserved per channel, without provider-specific code.

Supplier summaries and model details are derived from service-group membership with deduplication.
The removed `/api/v1/admin/channel-models` endpoints stay unavailable. Historical log UUID columns
remain without foreign keys to the retired table; new requests and attempts leave those UUIDs null.
Financial snapshots and existing request logs are retained. Historical Flyway migrations are immutable.

Before production upgrade, back up the database and app image. After V63, reverting only the app image
is insufficient because old code queries the dropped table. During a maintenance window, restore the
archived table and its two foreign keys, reconcile the V63 Flyway history entry and migrated metadata,
then start the old app; otherwise apply a forward fix. Avoid restoring the whole database
after new traffic has been admitted, as that would discard subsequent billing records.

Regression covers the V62-to-V63 upgrade, alias and exact decimal cost preservation, historical logs,
disabled options, conflicting aliases, dynamic routes without options, group isolation, gateway
responses, retries, streaming, video/image operations, supplier summaries, billing and model sync.

## Verification on 2026-09-11

- Full suite: 230 tests; after fixes, clean rebuild and 65-test regression passed with BUILD SUCCESS.
  Consolidated results: `scripts/log/channel-models-verification.json` (zero failures/errors/skips).
- Production V63 deployed. Retired table absent; both Seedance video transport aliases retained.
- Live gpt_tj / ycyapi chat, Responses and streaming Responses: 3/3 HTTP 200 with completed
  responses, settled reservations, null retired mapping IDs and verified temporary key revocation.
  Raw responses: `scripts/log/channel-retirement-production-20260911-200919.json`.
- Production video generation was not invoked; video request bodies were verified with WireMock.
- Upstream latency remains observable: Chat timed out on its first attempt at 120082 ms, then
  succeeded on retry at 13771 ms (135558 ms total). Nonstreaming Responses took 61736 ms;
  streaming Responses took 2421 ms. Three successful client requests involved four upstream attempts.
