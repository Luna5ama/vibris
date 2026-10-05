# Vibris capture and experiment control

Vibris exposes a request-scoped engineering-validation service. The wire protocol is major 3, minor 1.
Workspace identity, delivery receipts, and delivery transaction manifests retain schema version 2;
durable job checkpoints use schema version 3. Generated protocol classes retain their `v2` package name.
The canonical `server.json` format is schema 4.

## Clean cutover

Build and run matching copies of Iris, Vibris Core, and Vibris MCP. A client must send protocol major 3 and the exact
workspace ID on every request. Missing or different protocol versions fail with `UNSUPPORTED_VERSION` before any job
is submitted.

Persisted workspace identity, build receipts, and delivery transaction manifests whose `schema_version` is not 2 are
rejected and left byte-for-byte unchanged. `server.json` accepts legacy schema 2 without restart support and schema 3
with separate pending/artifact roots for compatibility; schema 4 replaces both roots with `vibris_root`. Move
unsupported files aside manually if their contents must be retained, then let the service create new state. Vibris
never deletes or converts old user data.

The authoritative workspace identity document has this shape:

```json
{
  "schema_version": 2,
  "workspace_id": "11111111-1111-4111-8111-111111111111"
}
```

Configuration additionally contains the loopback listen address, capacity/TTL policy, and deployment-specific paths.
Schema 4 derives `pending`, `artifacts`, and ephemeral `replay_capture` beneath one `vibris_root`:

```json
{
  "schema_version": 4,
  "listen_address": "127.0.0.1:50051",
  "vibris_root": "R:\\vibris",
  "artifact_quota_bytes": 3221225472,
  "artifact_ttl_hours": 168,
  "shaderpack_root": "I:\\code\\mcshaders\\vibris",
  "max_source_bytes": 536870912,
  "max_source_files": 100000,
  "max_global_queue": 32,
  "max_actions_per_job": 64,
  "restart_executable": "I:\\PCL\\启动 1.21.11-Vibris.bat"
}
```

An empty `restart_executable` disables the restart tool; a configured value must resolve to an ordinary file. Relative
paths resolve from the selected game directory exactly as written.

## MCP surface

The MCP publishes twelve tools:

- `vibris_get_status`
- `vibris_restart`
- `vibris_list_presets`
- `vibris_list_resources`
- `mcp_vibiris_nsight_analyze`
- `vibris_run_actions`
- `vibris_run_matrix`
- `vibris_run_recipe`
- `vibris_job`
- `vibris_artifacts`
- `vibris_compare`
- `vibris_preview_presets`

Every call includes `worktree_root`. Calls that start work also include `preset_id`. Recipe requests include the typed
`recipe` discriminator. Job query, result, cancel, and resume operations exist only on `vibris_job`.

`vibris_get_status` reports the current runtime lease, pending recovery, queue, transitions, bounded job summaries,
last error and recovery action. While Core and Minecraft remain connected, MCP deliberately hides internal server
state, shader-load phases, foreign runtime leases, immediate-start diagnostics, and stale errors from agents. Long shader
compilation is allowed up to five minutes for unary metadata calls instead of being mislabeled as server failure.
`can_accept_job` is the admission gate: submit immediately when it is true, even if
another workspace owns the runtime. Core uses round-robin workspace turns. Consecutive child jobs from one durable
workflow share a turn for at most four jobs or two minutes, then the next waiting workspace runs. Immediate-start
readiness remains internal and must not be used as a preflight gate. Status waits are
event-driven for `can_accept_job` or one job's terminal state and report whether the condition was satisfied or timed
out.

Never poll or sleep for a global idle lease. Core accepts multiple jobs from the same workspace
and from different workspaces whenever `can_accept_job=true`, then schedules them by workspace round-robin. A
`DURABLE_WORKFLOW_BUSY` response is narrower: one durable workflow worker is already active in that MCP process for
the worktree. It is not a Core queue rejection. Wait for the returned job with one
`vibris_job(operation=wait, timeout_ms=300000)` call, or combine related captures into one `vibris_run_actions`
sequence or one `vibris_run_matrix` request.

If a control stream reports `RST_STREAM` after submission, the MCP reconnects and resumes the accepted request by
request ID. Do not resubmit blindly and do not wait for the current lease to become idle.

`vibris_restart` is a top-level lifecycle control, never a run action. Core immediately closes new admission, broadcasts
`ServerShuttingDown` to every connected MCP, drains every already accepted queued or active job without cancelling it,
then invokes `restart_executable`. MCP clients retain the planned-restart state, reconnect until the replacement runtime
sends `ServerHello`, and continue job-starting calls after that handshake instead of reporting the expected disconnect
as a runtime failure. Status and durable-job control remain available while the old instance drains.

## Atomic Nsight GPU Trace

Raw replay-capture actions (`capture_pass` and `capture_multi`) are not part of the public MCP or wire action surface.
They remain an Iris/Vibris runtime implementation detail used only by the atomic `nsight_gpu_trace` run action. Its
`capture` selector is either one exact named pass (`mode: "single"`, `pass_id`) or one stage group
(`mode: "multi"`, `capture_type`: `prepare`, `begin`, `deferred`, or `composite`).

One Core job owns the complete sequence: wait for the internal capture service, capture into
`<vibris_root>/replay_capture`, run the selected Vibris GL/VK replayer under Nsight GPU Trace, require the complete
auto-export BASE bundle, publish the descriptor/TSV/log files through the normal managed artifact transaction, and
delete the raw replay capture and `.ngfx-gputrace` tree. Core's single active-job scheduler therefore serializes the
entire Nsight sequence across MCP processes and agents; there is no separately acquirable Nsight lock or partial trace
phase.

Example action:

```json
{
  "worktree_root": "I:/code/mcshaders/example",
  "preset_id": "720p",
  "actions": [{
    "type": "nsight_gpu_trace",
    "capture": {"mode": "single", "pass_id": "composite/composite1"},
    "artifact_name": "composite1",
    "replay_backend": "gl",
    "architecture": "Ada",
    "metric_set_name": "Throughput Metrics",
    "replay_frames": 300,
    "start_after_ms": 1000,
    "max_duration_ms": 1000,
    "timeout_seconds": 300,
    "time_every_action": true,
    "gpu_clocks": "base"
  }]
}
```

Deploy the optimized replayers together with the mod as `<game_directory>/vibris/replayer-gl.jar` and/or
`<game_directory>/vibris/replayer-vk.jar`; this game-side directory is independent of `vibris_root` and
`replay_capture`. Java is resolved from `<vibris_root>/runtime/java/bin/java.exe`, `VIBRIS_REPLAY_JAVA`, `JAVA_HOME`,
then `PATH`. Nsight is resolved from
`VIBRIS_NSIGHT_NGFX` or the newest installed Nsight Graphics. Multi-pass metrics are intentionally unavailable because
they are incompatible with the required auto-export workflow.

Pass the returned `*.nsight.bundle.json` managed artifact path to `mcp_vibiris_nsight_analyze`. This top-level tool runs
entirely inside MCP and does not contact Minecraft or Core. Its JSON `query.operation` supports `summary`, `stages`,
`actions`, `metric`, `stalls`, `bandwidth`, `shader_bound`, `texture_cache`, `overdraw`, `geometry`, and `draws`.
`GPUTRACE_REGIMES.xls` is streamed with metric-column projection. By default only durations and metrics inside the
outer `Replay` marker are shader evidence; whole-trace/frame-budget, CPU submission, `Copy`, sleep/yield, and unmarked
tail values are context or excluded.

## Sources and settings

Omitting `source` selects the caller's workspace source. If `source` is supplied, `kind` is mandatory. Workspace and
commit sources are frozen before execution. Results include source identity, shader-content identity, effective shader
settings, origins, defaults, stable settings hash, and completion-time staleness facts.

A config without `values`, or an omitted recipe `config`, selects shaderpack defaults. It never preserves settings from
an earlier request. `vibris_run_matrix` applies that rule independently to every generated source/config case.

State-changing validation snapshots the source, effective settings, scene, and temporal state. Terminal results carry
restoration receipts. If restoration cannot be proved, the job fails closed and status exposes the required recovery
action.

## Durable jobs

Long-running work is stored below `.vibris/jobs/<job_id>` with immutable request/source inputs, atomic state,
append-only events, immutable per-step receipts, and an immutable terminal result. Async submission returns a
`next_action` containing `vibris_job(operation=wait, timeout_ms=300000)`. Invoke that blocking wait once. If it reaches
its five-minute bound first, invoke `wait` again. Never combine `query` with `Start-Sleep`, shell sleep, or repeated
tool calls; `query` is only a one-time diagnostic snapshot. `wait` returns directly when the job completes, fails,
pauses for resume, or is cancelled, and includes the terminal result when one exists. Use `cancel` to cancel a job and
`resume` only when its recorded phase is safe. Completed steps are never repeated; uncertain side effects are not
replayed. A retryable terminal child result pauses before checkpointing that step and is resubmitted on resume. A
non-retryable child result terminalizes the durable job as `failed`; later steps are never executed.

While an accepted child request is quiet, the MCP worker performs a low-frequency five-second Core status probe. An
unreachable server is treated as a transient restart window, not proof that the job disappeared. If a reachable Core
instance explicitly has no matching job, MCP classifies the old request as `SERVER_RESTARTED`, clears that child
request, and safely resubmits the same durable step once. A second consecutive loss pauses the workflow and returns a
`next_action` for explicit resume instead of waiting indefinitely. A restarted Core also answers an unknown
`ResumeJob` with the same retryable terminal error; it never leaves the resume request unanswered.

If the process stops, start the matching v2 service, inspect status and the job record, then request resume. A job that
cannot prove a safe continuation returns a terminal failure with recovery guidance.

## Compile validation and benchmarks

Run `vibris_run_recipe` with `recipe: "compile_validate"` to enumerate the complete compile catalog and compare stable
diagnostic fingerprints with an optional baseline. Compile validation does not require GPU warmup.

Matrix and A/B benchmark jobs use typed target, sibling, and sentinel cases. Decisions require measured noise,
confidence, order/reversal/drift checks, compile and visual gates, immutable provenance, and successful restoration.
The result explains every failed guardrail instead of producing an unqualified winner.

## Resources and action examples

Use `vibris_list_resources` to obtain logical texture/buffer names, exact pass IDs, available views, dimensions, formats,
and the catalog mapping hash. Physical texture suffixes are result metadata, not valid selectors.

Immediate texture and buffer capture:

```json
{
  "worktree_root": "I:/code/mcshaders/example",
  "preset_id": "720p",
  "actions": [
    {
      "dump_texture": {
        "resource": {"logical_name": "colortex0", "view": "TEXTURE_VIEW_CURRENT"},
        "format": "ARTIFACT_FORMAT_PNG",
        "artifact_name": "color"
      }
    },
    {
      "dump_buffer": {
        "resource": {"logical_name": "scene_ssbo"},
        "artifact_name": "scene"
      }
    }
  ]
}
```

Exact named-pass texture and buffer capture:

```json
{
  "worktree_root": "I:/code/mcshaders/example",
  "preset_id": "720p",
  "actions": [
    {
      "dump_texture_after_pass": {
        "pass_id": "deferred",
        "resource": {"logical_name": "colortex0", "view": "TEXTURE_VIEW_CURRENT"},
        "format": "ARTIFACT_FORMAT_PNG",
        "artifact_name": "deferred-color"
      }
    },
    {
      "dump_buffer_after_pass": {
        "pass_id": "deferred",
        "resource": {"logical_name": "scene_ssbo"},
        "artifact_name": "deferred-scene"
      }
    }
  ]
}
```

After-pass requests register before the next matching boundary and capture after the named pass completes. Receipts
include the exact pass, frame/occurrence, logical and physical resource identity, view, GL metadata, artifact paths,
hashes, manifest ID, and manifest hash. PNG output has the documented image orientation; BIN output preserves native
readback bytes.

## Agent result projection

Agent responses use one MCP boundary projection. Complete job results and receipts on disk are unchanged;
Synchronous and durable terminal results include `full_result_path`. Terminal `vibris_job` responses include events only when
`event_cursor` was explicitly supplied. Effective settings retain the settings SHA, total setting count and only
non-default, request-override or preset settings. Origins use short enum names. Subsequent occurrences of the same
SHA refer to the first settings object using a JSON pointer in `settings_ref`. Repeated artifact records retain
only artifact ID, path and SHA. Repeated resource descriptors may use `resource_ref` plus their frame ID.

Resource catalogs omit redundant per-pass readable resource lists. Resource rows carry logical name, frame ID and
`layout_ref`; the shared descriptors are in `catalog.resource_layouts`. Texture depth/layers/mip-level defaults are
declared once in `texture_layout_defaults`; kind, scalar type and view enums omit their fixed protocol prefixes.
Buffer layouts retain kind and byte size. Source dimensions equal to the layout dimensions are omitted. A frame ID
shared by every resource is moved into `catalog_context`. Pass stage/program ID are derived from `pass_id`.
In each pass's programs, omitted program_id is the final segment of pass_id, omitted stage is compute,
and omitted entry_file is `/{program_id}.{extension}` using `program_stage_extensions`, unless
program_defaults supplies entry_file. Shared main_source paths may also be supplied by program_defaults.
All paths retain their full spelling. texture_layout_defaults also supplies common available_views.
`catalog_context` identifies the active source,
settings SHA and shader generation. Empty source/settings fields indicate a generation not observed by Core;
they must not be treated as proof that a prior configuration is still loaded. Pass programs list their stage,
entry file and full main-source path, associated through actual program objects.

Public tool schemas are flattened closed objects. The server validates the original strict branch schema and reports
field paths, applicable branches and allowed discriminator values. Unknown names include up to three suggestions;
invalid texture views include available views. Missing profile metrics return `missing` entries instead of failing
the job. Benchmark target/sibling/sentinel metrics remain mandatory.

## Experiments

`profile_matrix` accepts `presets` (up to 32) or `preset_id`, `rounds` (1-10), and `order` (`palindrome`,
`sequential` or seeded `randomized`). Palindrome executes the forward order followed by its reverse, including
the two central occurrences (ABC becomes ABCCBA). Preset is the outer axis, then round, then case.
The expanded experiment must not exceed 512 steps. `vibris_run_matrix` also accepts a presets axis.

Profile summaries provide one row per preset/source/config/metric: run count, median run-level p50/p95/avg,
minimum/maximum p50 and main source. Optional `baseline: {source_id, config_id}` produces paired per-round
relative deltas using `delta_statistic` (`p50` by default, or `avg`). Median/minimum/maximum describe the deltas;
with at least three rounds, the 95% Student t interval estimates their mean. It does not estimate the median.
`consistency` reports source/config/preset/scene/pass-mapping/environment changes within a case without failing it.
Raw measurements are stored in a CSV artifact and the full result; only `result_detail: "full"` inlines them.
Within each run, identical aggregate/program records for the same metric are counted once, preferring program
metadata. Conflicting records are excluded from that run's statistics and listed in `metric_conflicts`.
The full result and CSV retain all original records.

Commit sources accept `patch`, relative to the worktree or absolute. The frozen patch and SHA accompany the job.
A private Git index applies the patch to the selected commit and archives the resulting shader tree without
changing the worktree or its index. Every changed path must stay under `shader_directory`.
`git stash create` can provide a revision containing uncommitted tracked changes (not untracked files).
Binary changes require a patch generated with `git diff --binary`.

## Texture analysis

`dump_texture` and `dump_buffer` accept optional `pass_id`; absent pass means the current final output.
Texture dumps accept `region: {x,y,width,height}` in texels at the selected mip, using BIN row order.
Bounds errors report the mip dimensions. Metadata includes region and original source dimensions.

`texture_stats` accepts the same texture selector, optional pass and region. It creates no artifact and returns
per-channel min/max/mean/population std, exact interpolated p1/p50/p99 and separate NaN/+Inf/-Inf/zero counts.
Nonfinite values are excluded from finite statistics; all-nonfinite statistics are null.

`accumulate_texture` samples 2-65535 frames on the GPU at one named pass or frame end. Default batches are 16;
batches must be 2-64 and frames must be at least twice batches. It supports single-layer 2D textures, mip and
region, with optional RGB luma weights. 3D, array and MSAA textures are rejected. State exceeding 1 GiB is rejected.
Outputs select mean/variance/stderr/flicker/nonfinite, with mean/variance/stderr as defaults. Variance is unbiased
sample variance. Flicker averages absolute differences between consecutive finite pairs.
Normalized texture accumulation uses GPU sampling units (`units: gpu_normalized`), while integer
and floating-point textures use native numeric values. Source numeric class is recorded explicitly. BIN dumps and
CPU texture_stats keep raw integer-code units for normalized textures; SNORM raw codes cannot be reconstructed from
GPU samples. Nonfinite samples do
not enter Welford moments or batch means. Each frame is sampled at most once even if its pass repeats.

Files use `<name>.<output>.bin` and same-name JSON metadata; luma files use `<name>.luma.<output>.bin` (R32F).
Additional valid_count, valid_batches and incomplete_batches files preserve per-pixel effective counts.
Metadata records actual batch lengths, incomplete batch indices, screen batch means, valid pixel counts and
per-batch nonfinite counts. SE uses the unweighted valid batch means. It is marked as standard batch-means SE
only when all batches have equal lengths and all samples are valid. Other SE values are explicitly qualified.

`vibris_compare` decodes local PNG (8/16 bit normalized) and typed BIN sidecars under worktree `.vibris`.
Topology mismatches fail. Difference mode reports overall/per-channel MAE/RMSE/max/exact p95, mean values,
relative MSE, threshold pixel ratio and nonfinite mismatches. Relative MSE divides squared error by A's energy.
PSNR and luminance SSIM apply only to PNG/normalized formats; other values are null.
SSIM uses an 11x11 Gaussian window, sigma 1.5, with reflected boundaries (not the former global SSIM).
Statistical mode accepts accumulation `.mean.bin` files with variance/stderr siblings, assumes independent
runs and propagates batch qualification. Its screen mean ratios always refer to the full capture, even when pixel
metrics select a region; `screen_mean_scope` marks this explicitly. Analysis JSON and optional heatmap are written under `.vibris/analysis`.
Without thresholds, the verdict is `not_evaluated`. A/B workflows compare every requested capture pair locally.
The Core CompareCaptures action and enum number 5 are reserved and no longer executable.

## Preset previews

`vibris_preview_presets` selects `preset_ids` or `tags`, supports refresh and sync/async execution, and renders
cache misses through the durable multi-preset workflow using frozen workspace source and default settings.
Both success and error restore runtime state. Thumbnails are keyed by preset SHA, tile width and Vibris version;
their metadata records render-time source/settings SHAs. Contact sheets contain up to 24 scenes in 6x4 tiles,
with preset IDs and tags in an embedded ASCII bitmap font. `list_presets` includes a cached thumbnail when available.

## Artifacts

All generated output is owned by the worktree-local `.vibris/artifact` tree. `vibris_artifacts` lists, describes, and
deletes managed v2 manifests subject to workspace ownership. Manifests include request/job grouping, TTL, size, hashes,
and every output path. Capacity is reserved before capture; a request that would exceed policy fails before partial
output is published.

`vibris_artifacts operation=prune` composes list and SHA-checked deletes. At least one of older_than_ms,
job_ids, recipe or keep_latest is required; dry_run previews selection without deleting anything.
Time-only pruning also removes old local analysis directories, with keep_latest applied separately.
The response lists selected/deleted manifests, byte totals and failures. Linked analysis trees are not followed.

## Build and delivery

Use the repository Gradle/CMake builds, then run `tools/build-delivery.ps1` and `tools/package-delivery.ps1` when a
delivery is explicitly requested. The delivery contains `vibris-mcp.exe` and an independent `vibris-fabric-*.jar`.
Iris is a compile-time composite dependency, not the container for Vibris. The package command uses `-VibrisModJar`.
Both scripts require schema-2 receipts/manifests and publish transactionally. Building
or packaging does not authorize deployment, Minecraft restart, launcher restart, or user-data cleanup.
