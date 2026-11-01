#include "tool_registry.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <optional>
#include <set>
#include <string>
#include <utility>

#include "tool_argument_policy.hpp"
#include "result_mapper.hpp"

namespace vibris::mcp {
namespace {

Json closed_object(Json properties, std::initializer_list<const char*> required = {}) {
    Json schema{{"type", "object"}, {"properties", std::move(properties)}, {"additionalProperties", false}};
    if (required.size() != 0) {
        schema["required"] = required;
    }
    return schema;
}

Json enum_string(std::initializer_list<const char*> values) { return Json{{"type", "string"}, {"enum", values}}; }
Json bounded_integer(std::uint64_t minimum, std::uint64_t maximum) {
    return Json{{"type", "integer"}, {"minimum", minimum}, {"maximum", maximum}};
}
Json string_array(std::size_t maximum) {
    return Json{{"type", "array"}, {"items", {{"type", "string"}, {"minLength", 1}}}, {"maxItems", maximum}};
}
Json enum_array(std::initializer_list<const char*> values, std::size_t maximum) {
    return Json{{"type", "array"}, {"items", enum_string(values)}, {"maxItems", maximum}, {"uniqueItems", true}};
}
Json one_of(std::initializer_list<Json> schemas) { return Json{{"type", "object"}, {"oneOf", schemas}}; }
Json scoped(Json schema, bool scene_required) {
    schema["properties"]["worktree_root"] = {{"type", "string"}, {"minLength", 1}};
    if (!schema.contains("required")) schema["required"] = Json::array();
    schema["required"].push_back("worktree_root");
    if (scene_required) {
        schema["properties"]["preset_id"] = {{"type", "string"}, {"minLength", 1}};
        schema["required"].push_back("preset_id");
    }
    return schema;
}
Json source_schema() {
    return one_of({
        closed_object({{"kind", enum_string({"workspace"})}}, {"kind"}),
        closed_object({{"kind", enum_string({"commit"})}, {"revision", {{"type", "string"}, {"minLength", 1}}},
            {"patch", {{"type", "string"}, {"minLength", 1}, {"description",
                "Patch path relative to worktree_root or absolute. Use git diff --binary for binary changes. "
                "git stash create produces a revision for tracked uncommitted changes, excluding untracked files."}}}},
                      {"kind", "revision"}),
    });
}
Json named_source_schema() {
    return one_of({
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"kind", enum_string({"workspace"})}}, {"id", "kind"}),
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"kind", enum_string({"commit"})},
                       {"revision", {{"type", "string"}, {"minLength", 1}}},
                       {"patch", {{"type", "string"}, {"minLength", 1}}}},
                      {"id", "kind", "revision"}),
    });
}
Json named_config_schema() {
    return one_of({
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"values", {{"type", "object"}}}}, {"id", "values"}),
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"mode", enum_string({"preserve"})}}, {"id", "mode"}),
    });
}
Json named_sources_schema() {
    return Json{{"type", "array"}, {"items", named_source_schema()}, {"minItems", 1}, {"maxItems", 16}};
}
Json named_configs_schema() {
    return Json{{"type", "array"}, {"items", named_config_schema()}, {"minItems", 1}, {"maxItems", 64}};
}
Json matrix_axes_schema() {
    return closed_object({{"sources", string_array(16)}, {"configs", string_array(64)}},
                         {"sources", "configs"});
}
Json capture_schema() {
    return one_of({
        closed_object({{"type", enum_string({"screenshot"})},
                       {"format", enum_string({"png"})}},
                      {"type"}),
        closed_object({{"type", enum_string({"texture"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}},
                       {"format", enum_string({"bin", "png"})}},
                      {"type", "name"}),
        closed_object({{"type", enum_string({"buffer"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}}},
                      {"type", "name"}),
    });
}

Json visual_thresholds_schema() {
    const Json ratio{{"type", "number"}, {"minimum", 0.0}, {"maximum", 1.0}};
    const Json ssim{{"type", "number"}, {"minimum", -1.0}, {"maximum", 1.0}};
    return closed_object({
        {"pixel_error_threshold", ratio},
        {"max_mean_absolute_error", ratio},
        {"max_root_mean_square_error", ratio},
        {"max_p95_absolute_error", ratio},
        {"max_absolute_error", ratio},
        {"max_threshold_pixel_ratio", ratio},
        {"min_ssim", ssim},
    });
}

Json compare_schema() {
    const auto path = closed_object({{"path", {{"type", "string"}, {"minLength", 1}}}}, {"path"});
    auto thresholds = visual_thresholds_schema();
    for (auto& [key, field] : thresholds["properties"].items()) if (key != "min_ssim") field.erase("maximum");
    thresholds["properties"]["max_relative_mse"] = {{"type", "number"}, {"minimum", 0}};
    return scoped(closed_object({{"a", path}, {"b", path}, {"mode", enum_string({"difference", "statistical"})},
        {"heatmap", {{"type", "boolean"}}}, {"thresholds", thresholds},
        {"channels", {{"type", "array"}, {"items", bounded_integer(0, 3)}, {"minItems", 1}, {"maxItems", 4}, {"uniqueItems", true}}},
        {"region", closed_object({{"x", bounded_integer(0, INT32_MAX)}, {"y", bounded_integer(0, INT32_MAX)},
            {"width", bounded_integer(1, INT32_MAX)}, {"height", bounded_integer(1, INT32_MAX)}}, {"x", "y", "width", "height"})}},
        {"a", "b"}), false);
}

Json nsight_query_schema() {
    const auto top = bounded_integer(1, 500);
    const Json filter{{"type", "string"}, {"maxLength", 512}};
    const Json marker{{"type", "string"}, {"maxLength", 512}};
    const auto diagnostic = [&](const char* operation) {
        return closed_object({
            {"operation", enum_string({operation})},
            {"top", top},
            {"in_marker", marker},
        }, {"operation"});
    };
    return one_of({
        closed_object({{"operation", enum_string({"summary"})}}, {"operation"}),
        closed_object({
            {"operation", enum_string({"stages"})},
            {"depth", bounded_integer(0, 64)},
            {"parent", filter},
            {"top", top},
        }, {"operation"}),
        closed_object({
            {"operation", enum_string({"actions"})},
            {"top", top},
            {"sort", enum_string({"duration", "start"})},
            {"filter", filter},
            {"in_marker", marker},
            {"with_metrics", {{"type", "boolean"}}},
        }, {"operation"}),
        closed_object({
            {"operation", enum_string({"metric"})},
            {"name", {{"type", "string"}, {"minLength", 1}, {"maxLength", 512}}},
            {"in_marker", marker},
            {"all_matches", {{"type", "boolean"}}},
            {"top", top},
        }, {"operation", "name"}),
        diagnostic("stalls"),
        diagnostic("bandwidth"),
        diagnostic("shader_bound"),
        diagnostic("texture_cache"),
        diagnostic("overdraw"),
        diagnostic("geometry"),
        diagnostic("draws"),
    });
}

Json benchmark_visual_schema() {
    auto schema = visual_thresholds_schema();
    schema["properties"]["warmup_frames"] =
        bounded_integer(0, std::numeric_limits<std::uint32_t>::max());
    return schema;
}

Json benchmark_metrics_schema() {
    return Json{{"type", "array"}, {"minItems", 1}, {"maxItems", 256}, {"items", closed_object({
        {"metric_id", {{"type", "string"}, {"minLength", 1}}},
        {"role", enum_string({"target", "sibling", "sentinel"})},
        {"max_regression_ratio", {{"type", "number"}, {"minimum", 0.0}}},
    }, {"metric_id", "role"})}};
}

Json restore_policy_schema() {
    return closed_object({{"on_success", {{"type", "boolean"}}},
                          {"on_error", {{"type", "boolean"}}}},
                         {"on_success", "on_error"});
}

Json source_variant_schema() {
    return closed_object({{"label", {{"type", "string"}, {"minLength", 1}}}, {"source", source_schema()}},
                         {"label", "source"});
}

Json recipe_schema() {
    const auto frames = bounded_integer(0, std::numeric_limits<std::uint32_t>::max());
    const auto metric_frames = bounded_integer(1, 10'000);
    const auto result_detail = enum_string({"summary", "metrics", "full"});
    const auto metric_filter = string_array(256);
    const auto statistics = enum_array({"avg", "p5", "p50", "p95"}, 4);
    const auto converted_units = enum_array({"us", "ms"}, 2);
    const auto max_retries = bounded_integer(0, 5);
    const auto execution = enum_string({"sync", "async"});
    const Json config{{"type", "object"}};
    auto schema = scoped(closed_object({
        {"recipe", enum_string({"profile", "profile_matrix", "compile_validate", "benchmark_ab", "load_and_screenshot",
                                 "capture_debug_bundle", "ab_compare", "recover_runtime"})},
        {"preset_id", {{"type", "string"}, {"minLength", 1}}},
        {"source", source_schema()},
        {"baseline", one_of({source_schema(), closed_object({
            {"source_id", {{"type", "string"}, {"minLength", 1}}},
            {"config_id", {{"type", "string"}, {"minLength", 1}}}}, {"source_id", "config_id"})})},
        {"candidate", source_schema()},
        {"a", source_variant_schema()},
        {"b", source_variant_schema()},
        {"sources", named_sources_schema()},
        {"presets", Json{{"type", "array"}, {"items", {{"type", "string"}, {"minLength", 1}}},
            {"minItems", 1}, {"maxItems", 32}, {"uniqueItems", true}}},
        {"delta_statistic", enum_string({"p50", "avg"})},
        {"configs", named_configs_schema()},
        {"matrix", matrix_axes_schema()},
        {"config", config},
        {"baseline_config", config},
        {"warmup_frames", frames},
        {"frames", metric_frames},
        {"rounds", bounded_integer(1, 20)},
        {"control_rounds", bounded_integer(2, 20)},
        {"order", enum_string({"abba", "abab", "randomized", "palindrome", "sequential"})},
        {"random_seed", bounded_integer(0, std::numeric_limits<std::uint32_t>::max())},
        {"statistic", enum_string({"avg", "p5", "p50", "p95"})},
        {"result_detail", result_detail},
        {"metric_filter", metric_filter},
        {"statistics", statistics},
        {"converted_units", converted_units},
        {"max_retries", max_retries},
        {"execution", execution},
        {"restore_state", restore_policy_schema()},
        {"result_csv", {{"type", "boolean"}}},
        {"screenshot_format", enum_string({"png"})},
        {"screenshot", {{"type", "boolean"}}},
        {"textures", string_array(64)},
        {"buffers", string_array(64)},
        {"captures", {{"type", "array"}, {"items", capture_schema()}, {"minItems", 1}, {"maxItems", 64}}},
        {"visual", benchmark_visual_schema()},
        {"metrics", benchmark_metrics_schema()},
        {"visual_thresholds", visual_thresholds_schema()},
    }, {"recipe"}), false);
    return schema;
}

Json action_schema() {
    const Json artifact_name{{"type", "string"}, {"minLength", 1}, {"maxLength", 64}};
    const Json resource_name{{"type", "string"}, {"minLength", 1}};
    const auto texture_selector = closed_object({{"logical_name", resource_name},
                                                  {"view", enum_string({"current", "alternate", "main", "alt"})},
                                                  {"mip_level", bounded_integer(0, 31)},
                                                  {"layer", bounded_integer(0, 4095)}},
                                                 {"logical_name", "view"});
    const auto buffer_selector = closed_object({{"logical_name", resource_name}}, {"logical_name"});
    Json pass_id = resource_name;
    pass_id["description"] =
        "Optional canonical stage/program pass ID from vibris_list_resources. When set, captures the resource right "
        "after that pass completes; when omitted, captures the final frame output.";
    const auto frames = bounded_integer(1, 10'000);
    const auto empty_action = [](const char* type) {
        return closed_object({{"type", enum_string({type})}}, {"type"});
    };
    auto load_shader = closed_object({{"type", enum_string({"load_shader"})},
                                      {"source_id", {{"type", "string"}, {"minLength", 1}}},
                                      {"config_id", {{"type", "string"}, {"minLength", 1}}}},
                                     {"type", "source_id", "config_id"});
    load_shader["description"] =
        "Closes any open screen, hides the HUD, loads the selected source and config, reloads the shader pipeline, "
        "applies the request-scoped scene, resets temporal counters, and returns the resulting shader state, errors, "
        "and structured reload diagnostics.";
    return one_of({
        closed_object({{"type", enum_string({"camera_motion"})},
            {"artifact_prefix", {{"type", "string"}, {"minLength", 1}, {"maxLength", 48},
                {"pattern", "^[A-Za-z0-9][A-Za-z0-9._-]*$"}}},
            {"warmup_frames", bounded_integer(0, 256)},
            {"keyframes", {{"type", "array"}, {"minItems", 2}, {"maxItems", 16},
                {"items", closed_object({
                    {"frame_offset", bounded_integer(0, 239)},
                    {"yaw_delta_degrees", {{"type", "number"}, {"minimum", -720}, {"maximum", 720}}},
                    {"pitch_delta_degrees", {{"type", "number"}, {"minimum", -180}, {"maximum", 180}}},
                    {"x_delta", {{"type", "number"}, {"minimum", -16}, {"maximum", 16}}},
                    {"y_delta", {{"type", "number"}, {"minimum", -16}, {"maximum", 16}}},
                    {"z_delta", {{"type", "number"}, {"minimum", -16}, {"maximum", 16}}},
                }, {"frame_offset", "yaw_delta_degrees", "pitch_delta_degrees"})}}},
        }, {"type", "artifact_prefix", "keyframes"}),
        closed_object({{"type", enum_string({"wait_frames"})},
                       {"frames", bounded_integer(1, std::numeric_limits<std::uint32_t>::max())}},
                      {"type", "frames"}),
        closed_object({{"type", enum_string({"take_screenshot"})},
                       {"format", enum_string({"png"})},
                       {"artifact_name", artifact_name},
                       {"after_frames", bounded_integer(0, std::numeric_limits<std::int32_t>::max())}},
                      {"type"}),
        closed_object({{"type", enum_string({"dump_texture"})},
                       {"region", closed_object({{"x", bounded_integer(0, INT32_MAX)}, {"y", bounded_integer(0, INT32_MAX)},
                           {"width", bounded_integer(1, INT32_MAX)}, {"height", bounded_integer(1, INT32_MAX)}},
                           {"x", "y", "width", "height"})},
                       {"pass_id", pass_id},
                       {"resource", texture_selector},
                       {"format", enum_string({"bin", "png"})},
                       {"artifact_name", artifact_name}},
                      {"type", "resource", "format", "artifact_name"}),
        closed_object({{"type", enum_string({"dump_buffer"})},
                       {"pass_id", pass_id},
                       {"resource", buffer_selector},
                       {"artifact_name", artifact_name}},
                      {"type", "resource", "artifact_name"}),
        closed_object({{"type", enum_string({"texture_stats"})}, {"resource", texture_selector}, {"pass_id", pass_id},
            {"region", closed_object({{"x", bounded_integer(0, INT32_MAX)}, {"y", bounded_integer(0, INT32_MAX)},
                {"width", bounded_integer(1, INT32_MAX)}, {"height", bounded_integer(1, INT32_MAX)}},
                {"x", "y", "width", "height"})}}, {"type", "resource"}),
        closed_object({{"type", enum_string({"accumulate_texture"})}, {"resource", texture_selector}, {"pass_id", pass_id},
            {"region", closed_object({{"x", bounded_integer(0, INT32_MAX)}, {"y", bounded_integer(0, INT32_MAX)},
                {"width", bounded_integer(1, INT32_MAX)}, {"height", bounded_integer(1, INT32_MAX)}},
                {"x", "y", "width", "height"})},
            {"frames", bounded_integer(2, 65535)}, {"batches", bounded_integer(2, 64)},
            {"luma_weights", {{"type", "array"}, {"minItems", 3}, {"maxItems", 3}, {"items", {{"type", "number"}}}}},
            {"outputs", enum_array({"mean", "variance", "stderr", "flicker", "nonfinite"}, 5)},
            {"artifact_name", artifact_name}}, {"type", "resource", "frames", "artifact_name"}),
        empty_action("get_capture_status"),
        load_shader,
        closed_object({
            {"type", enum_string({"nsight_gpu_trace"})},
            {"capture", one_of({
                closed_object({
                    {"mode", enum_string({"single"})},
                    {"pass_id", resource_name},
                }, {"mode", "pass_id"}),
                closed_object({
                    {"mode", enum_string({"multi"})},
                    {"capture_type", enum_string({"prepare", "begin", "deferred", "composite"})},
                }, {"mode", "capture_type"}),
            })},
            {"artifact_name", artifact_name},
            {"replay_backend", enum_string({"gl", "vk"})},
            {"architecture", enum_string({"Turing", "Ampere GA10x", "Orin GA10B", "Ada", "Thor GB10B", "Blackwell GB20x", "T25x GB20x"})},
            {"metric_set_name", {{"type", "string"}, {"minLength", 1}, {"maxLength", 128}}},
            {"replay_frames", bounded_integer(2, 10'000)},
            {"start_after_ms", bounded_integer(0, 600'000)},
            {"max_duration_ms", bounded_integer(1, 600'000)},
            {"timeout_seconds", bounded_integer(30, 3'600)},
            {"time_every_action", {{"type", "boolean"}}},
            {"gpu_clocks", enum_string({"unaltered", "base", "boost"})},
        }, {"type", "capture", "artifact_name", "architecture"}),
        empty_action("inspect_shader"),
        closed_object({{"type", enum_string({"get_gpu_metrics"})}, {"frames", frames},
                       {"metric_ids", string_array(256)}}, {"type", "frames"}),
        closed_object({{"type", enum_string({"list_resources"})},
                       {"kinds", enum_array({"final_framebuffer", "texture", "buffer", "patched_shaders"}, 4)},
                       {"logical_name", resource_name}, {"pass_id", resource_name}}, {"type"}),
        closed_object({{"type", enum_string({"get_patched_shaders"})},
                       {"artifact_name", artifact_name}}, {"type", "artifact_name"}),
    });
}

Json output_schema() {
    return closed_object({
        {"schema_version", {{"type", "integer"}, {"const", 2}}},
        {"success", {{"type", "boolean"}}},
        {"result", {{"type", "object"}}},
        {"error", closed_object({
            {"code", {{"type", "string"}, {"minLength", 1}}},
            {"message", {{"type", "string"}}},
            {"retryable", {{"type", "boolean"}}},
            {"details", {{"type", "object"}}},
        }, {"code", "message", "retryable", "details"})},
    }, {"schema_version", "success"});
}

Json definition(
    const char* name, const char* description, Json input_schema, bool read_only,
    bool destructive = false) {
    return Json{{"name", name},
                {"schema_version", 2},
                {"description", description},
                {"inputSchema", std::move(input_schema)},
                {"outputSchema", output_schema()},
                {"annotations", {{"readOnlyHint", read_only},
                                 {"destructiveHint", destructive},
                                 {"openWorldHint", false}}}};
}

Json build_definitions() {
    Json definitions = Json::array({
        definition("vibris_get_status",
                   "Read agent-actionable admission, queue and job status for the explicit Git worktree. "
                   "When operational=true, submit immediately if can_accept_job=true; otherwise use one "
                   "wait_until=can_accept_job call; never wait for a global idle lease. "
                   "Internal shader compilation, runtime phases, foreign leases "
                   "and other healthy-server transitions are intentionally hidden. Core queues accepted "
                   "same-workspace and cross-workspace jobs by round-robin. wait_until can "
                   "wait for admission or one Core job's terminal state in one call; never poll with shell sleep. "
                   "Durable MCP workflows use vibris_job operation=wait. Resource "
                   "catalogs are intentionally omitted.",
                   scoped(closed_object({
                       {"detail", enum_string({"summary", "jobs", "full"})},
                       {"wait_until", enum_string({"can_accept_job", "job_terminal"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"timeout_ms", bounded_integer(0, 300'000)},
                   }), false), true),
        definition("vibris_restart",
                   "Schedule one graceful Minecraft restart for the explicit Git worktree's connected runtime "
                   "using restart_executable from server.json. "
                   "This is a top-level control operation, not a run action. Core immediately closes new job "
                   "admission, lets every already accepted queued or active job finish without cancellation, "
                   "broadcasts a planned-restart notice to all connected MCP clients, then restarts Minecraft. "
                   "Other MCP clients wait for the replacement runtime and retry job-starting calls instead of "
                   "treating the planned disconnect as a runtime failure.",
                   scoped(closed_object({
                       {"reason", {{"type", "string"}, {"maxLength", 512}}},
                   }), false), false, true),
        definition("vibris_list_presets",
                   "List Minecraft scene presets for the explicit Git worktree using exact preset and tag filters.",
                   scoped(closed_object({{"preset_id", {{"type", "string"}, {"minLength", 1}}},
                                         {"tags", string_array(32)}}), false), true),
        definition("vibris_list_resources",
                   "List the typed logical resource and named-pass catalog for the explicit Git worktree. "
                   "Resource layout_ref selects resource_layouts; texture_layout_defaults supplies omitted unit dimensions. "
                   "catalog_context identifies the loaded source, settings and shader generation.",
                   scoped(closed_object({
                       {"kinds", enum_array({"final_framebuffer", "texture", "buffer", "patched_shaders"}, 4)},
                       {"logical_name", {{"type", "string"}, {"minLength", 1}}},
                       {"pass_id", {{"type", "string"}, {"minLength", 1}}},
                   }), false), true),
        definition("mcp_vibiris_nsight_analyze",
                   "Analyze one managed Vibris Nsight GPU Trace auto-export bundle for the explicit Git worktree "
                   "locally in this MCP process. "
                   "No Minecraft/Core request is made. artifact_path must identify the bundle descriptor returned "
                   "by nsight_gpu_trace. The default evidence scope is the outer Replay marker: whole-capture, "
                   "CPU submission, Copy, and unmarked tail timing are never reported as shader-pass evidence. "
                   "GPUTRACE_REGIMES.xls is streamed and only requested metric columns are projected.",
                   scoped(closed_object({
                       {"artifact_path", {{"type", "string"}, {"minLength", 1}}},
                       {"query", nsight_query_schema()},
                   }, {"artifact_path", "query"}), false), true),
        definition("vibris_run_recipe",
                   "ADMISSION RULE: never wait for a global idle lease; when can_accept_job is "
                   "true, submit now and let Core queue the job by workspace round-robin. Run a standard shader "
                   "workflow for the explicit Git worktree and scene preset. "
                   "load_and_screenshot loads one "
                   "shader source and config, waits for the requested warmup frames, and saves a screenshot. Profile "
                   "recipes return normalized cases with summary, metrics, or full result detail. Long-running "
                   "recipes support durable sync/async execution. An async result contains next_action: invoke its "
                   "vibris_job operation=wait once and never poll with query or shell sleep. "
                   "benchmark_ab requires typed target, sibling, and sentinel metrics, repeated ABBA, ABAB, or "
                   "seeded randomized profiles, same-source controls, and deterministic visual thresholds; it "
                   "accepts only stable target improvements after compile, provenance, restoration, statistical, "
                   "guardrail, and visual gates pass. Jobs keep their final world and shader on success, failure, "
                   "and cancellation unless restore_state explicitly requests restoration. "
                   "recover_runtime is the only recipe without a preset or source; it reapplies and verifies the "
                   "retained safe snapshot after a transactional restore failure.",
                   recipe_schema(), false),
        definition("vibris_run_actions",
                   "ADMISSION RULE: never wait for a global idle lease; when can_accept_job is "
                   "true, submit now and let Core queue the job by workspace round-robin. Run one ordered shader "
                   "action sequence synchronously or as a durable async job for the explicit Git worktree and scene "
                   "preset. An async result's next_action is one blocking vibris_job operation=wait call; never poll. "
                   "Put related multi-image exports into one ordered action sequence instead of waiting for "
                   "an idle lease between images. restore_state defaults to preserve the final source/config on "
                   "success, failure, and cancellation. "
                   "Explicit restore_state can request a return to the previous state.",
                   scoped(closed_object({{"sources", named_sources_schema()},
                                         {"presets", Json{{"type", "array"}, {"items", {{"type", "string"}, {"minLength", 1}}},
                                             {"minItems", 1}, {"maxItems", 32}, {"uniqueItems", true}}},
                                         {"preset_id", {{"type", "string"}, {"minLength", 1}}},
                                         {"configs", named_configs_schema()},
                                         {"execution", enum_string({"sync", "async"})},
                                         {"restore_state", restore_policy_schema()},
                                         {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                        {"actions"}), true), false),
        definition("vibris_run_matrix",
                   "ADMISSION RULE: never wait for a global idle lease; when can_accept_job is "
                   "true, submit now and let Core queue the job by workspace round-robin. Run the action template "
                   "synchronously or as a durable async job for every selected source/config combination in the "
                   "explicit Git worktree and scene preset. An async result's next_action is one blocking vibris_job "
                   "operation=wait call; never poll. Use this single call for related config/image exports. "
                   "Each combination "
                   "automatically begins with load_shader; do not include load_shader in the action template.",
                   scoped(closed_object({{"sources", named_sources_schema()},
                                         {"preset_id", {{"type", "string"}, {"minLength", 1}}},
                                         {"presets", Json{{"type", "array"}, {"items", {{"type", "string"}, {"minLength", 1}}}, {"minItems", 1}, {"maxItems", 32}, {"uniqueItems", true}}},
                                         {"configs", named_configs_schema()},
                                         {"matrix", matrix_axes_schema()},
                                         {"restore_state", restore_policy_schema()},
                                         {"execution", enum_string({"sync", "async"})},
                                         {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                        {"sources", "configs", "matrix", "actions"}), false), false),
        definition("vibris_job",
                   "Wait for, inspect, retrieve, cancel or resume one durable v2 job for the explicit Git worktree. "
                   "For an async job, call operation=wait once with timeout_ms=240000; waits are capped at 240 seconds to leave transport deadline headroom; if that bounded wait times out, "
                   "call wait again. Never poll with query, repeated tool calls, Start-Sleep, or shell sleep. "
                   "query is only for a one-time diagnostic snapshot. "
                   "DURABLE_WORKFLOW_BUSY describes one local MCP workflow worker, not the Core runtime lease or "
                   "job queue; wait on the returned job_id instead of inspecting global runtime occupancy.",
                   scoped(closed_object({
                       {"operation", enum_string({"wait", "query", "result", "cancel", "resume"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"event_cursor", bounded_integer(0, std::numeric_limits<std::uint64_t>::max())},
                       {"timeout_ms", bounded_integer(1, 300'000)},
                       {"reason", {{"type", "string"}, {"maxLength", 512}}},
                   }, {"operation", "job_id"}), false), false),
        definition("vibris_artifacts",
                   "List manifest summaries, inspect, measure capacity for, or delete managed v2 artifacts in the explicit Git worktree. "
                   "List retains complete manifest_path and SHA references; operation=get returns one full manifest with files.",
                   scoped(closed_object({
                       {"operation", enum_string({"list", "get", "capacity", "delete", "prune"})},
                       {"older_than_ms", bounded_integer(0, std::numeric_limits<std::uint64_t>::max())},
                       {"job_ids", string_array(512)},
                       {"recipe", {{"type", "string"}, {"minLength", 1}}},
                       {"keep_latest", bounded_integer(0, 100'000)},
                       {"dry_run", {{"type", "boolean"}}},
                       {"manifest_id", {{"type", "string"}, {"minLength", 1}}},
                       {"expected_manifest_sha256", {{"type", "string"}, {"minLength", 64}, {"maxLength", 64}}},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"request_id", {{"type", "string"}, {"minLength", 1}}},
                   }, {"operation"}), false), false),
        definition("vibris_compare", "Compare local PNG or typed BIN captures for the explicit Git worktree inside worktree/.vibris. "
            "SSIM uses an 11x11 Gaussian luminance window (sigma 1.5), with reflected boundaries. "
            "Statistical mode assumes independent runs and accepts accumulate .mean.bin inputs. "
            "relMSE is squared difference divided by baseline energy. No thresholds means not_evaluated.",
            compare_schema(), true),
        definition("vibris_preview_presets", "Render and cache scene thumbnails for the explicit Git worktree by preset SHA, tile width and Vibris version. "
            "Uses a frozen workspace source and default settings; leaves the runtime at the final preview scene. Returns 6x4 contact sheets. "
            "Async execution uses the durable job workflow; wait once with vibris_job.",
            scoped(closed_object({{"preset_ids", string_array(512)}, {"tags", string_array(64)},
                {"refresh", {{"type", "boolean"}}}, {"tile_width", bounded_integer(64, 1024)},
                {"execution", enum_string({"sync", "async"})}}, {}), false), false),
    });
    return definitions;
}

bool has_type(const Json& value, std::string_view type) {
    if (type == "object") return value.is_object();
    if (type == "array") return value.is_array();
    if (type == "string") return value.is_string();
    if (type == "integer") return value.is_number_integer() || value.is_number_unsigned();
    if (type == "number") return value.is_number();
    if (type == "boolean") return value.is_boolean();
    return false;
}

std::optional<std::string> discriminator(const Json& branches) {
    for (const auto* key : {"type", "kind", "mode", "operation"}) {
        std::set<std::string> values;
        bool usable = true;
        for (const auto& branch : branches) {
            if (!branch.contains("properties") || !branch["properties"].contains(key)) {
                usable = false;
                break;
            }
            const auto& field = branch["properties"][key];
            if (!field.contains("enum") || field["enum"].size() != 1 ||
                !field["enum"][0].is_string() ||
                !values.insert(field["enum"][0].get<std::string>()).second) {
                usable = false;
                break;
            }
        }
        if (usable) return key;
    }
    return std::nullopt;
}

Json merge_public_fields(Json left, const Json& right) {
    if (left.contains("properties") && right.contains("properties")) {
        for (const auto& [key, value] : right["properties"].items()) {
            if (left["properties"].contains(key)) left["properties"][key] = merge_public_fields(left["properties"][key], value);
            else left["properties"][key] = value;
        }
        Json required = Json::array();
        const auto other_required = right.value("required", Json::array());
        for (const auto& key : left.value("required", Json::array()))
            if (std::find(other_required.begin(), other_required.end(), key) != other_required.end()) required.push_back(key);
        left["required"] = std::move(required);
    }
    if (left.contains("items") && right.contains("items")) left["items"] = merge_public_fields(left["items"], right["items"]);
    if (left.contains("enum") && right.contains("enum")) {
        for (const auto& option : right["enum"])
            if (std::find(left["enum"].begin(), left["enum"].end(), option) == left["enum"].end()) left["enum"].push_back(option);
    } else left.erase("enum");
    for (const auto* bound : {"minimum", "minItems", "minLength", "maximum", "maxItems", "maxLength"}) {
        if (!left.contains(bound) || !right.contains(bound)) left.erase(bound);
        else if (std::string_view(bound).starts_with("min")) left[bound] = std::min(left[bound], right[bound]);
        else left[bound] = std::max(left[bound], right[bound]);
    }
    if (left.contains("type") && right.contains("type") && left["type"] != right["type"]) {
        auto types = left["type"].is_array() ? left["type"] : Json::array({left["type"]});
        const auto others = right["type"].is_array() ? right["type"] : Json::array({right["type"]});
        for (const auto& type : others) if (std::find(types.begin(), types.end(), type) == types.end()) types.push_back(type);
        left["type"] = std::move(types);
    }
    return left;
}

Json public_schema(Json schema) {
    if (schema.contains("oneOf")) {
        auto branches = schema.at("oneOf");
        for (auto& branch : branches) branch = public_schema(std::move(branch));
        const auto tag = discriminator(branches);
        Json properties = Json::object();
        Json required = branches.front().value("required", Json::array());
        for (const auto& branch : branches) {
            const auto branch_required = branch.value("required", Json::array());
            Json intersection = Json::array();
            for (const auto& key : required) {
                if (std::find(branch_required.begin(), branch_required.end(), key) != branch_required.end())
                    intersection.push_back(key);
            }
            required = std::move(intersection);
            for (const auto& [key, field] : branch.at("properties").items()) {
                auto flattened = public_schema(field);
                if (!properties.contains(key)) properties[key] = flattened;
                else properties[key] = merge_public_fields(std::move(properties[key]), flattened);
            }
        }
        for (auto& [key, field] : properties.items()) {
            std::string applies;
            for (const auto& branch : branches) {
                if (!branch.at("properties").contains(key)) continue;
                if (!applies.empty()) applies += ", ";
                applies += tag ? branch["properties"][*tag]["enum"][0].get<std::string>()
                               : branch.value("required", Json::array()).dump();
            }
            const auto prior = field.value("description", std::string{});
            field["description"] = prior + (prior.empty() ? "" : " ") + "Applies to: " + applies + ".";
        }
        schema.erase("oneOf");
        schema["type"] = "object";
        schema["properties"] = std::move(properties);
        schema["additionalProperties"] = false;
        schema["required"] = std::move(required);
        std::string forms = "Exactly one supported form is required. ";
        for (const auto& branch : branches) {
            if (tag) forms += branch["properties"][*tag]["enum"][0].get<std::string>() + ": ";
            forms += "required " + branch.value("required", Json::array()).dump() + ". ";
            for (const auto& [key, field] : branch.at("properties").items()) {
                if (field.contains("required") && !field["required"].empty())
                    forms += key + " requires " + field["required"].dump() + ". ";
            }
        }
        schema["description"] = schema.value("description", std::string{}) + forms;
    }
    if (schema.contains("properties")) {
        for (auto& [key, field] : schema["properties"].items()) field = public_schema(std::move(field));
    }
    if (schema.contains("items")) schema["items"] = public_schema(std::move(schema["items"]));
    return schema;
}

std::optional<std::string> validate(const Json& value, const Json& schema, const std::string& path) {
    if (schema.contains("oneOf")) {
        const auto& branches = schema["oneOf"];
        if (const auto tag = discriminator(branches)) {
            Json allowed = Json::array();
            for (const auto& branch : branches) {
                const auto& option = branch["properties"][*tag]["enum"][0];
                allowed.push_back(option);
                if (value.is_object() && value.contains(*tag) && value[*tag] == option)
                    return validate(value, branch, path);
            }
            return path + "." + *tag + " must be one of " + allowed.dump();
        }
        std::size_t matches = 0;
        std::optional<std::string> deepest;
        std::size_t deepest_depth = 0;
        for (const auto& branch : branches) {
            const auto error = validate(value, branch, path);
            if (!error) { ++matches; continue; }
            const auto field_path = error->substr(0, error->find(' '));
            const auto depth = static_cast<std::size_t>(std::count(field_path.begin(), field_path.end(), '.') +
                std::count(field_path.begin(), field_path.end(), '['));
            if (!deepest || depth > deepest_depth) { deepest = error; deepest_depth = depth; }
        }
        if (matches == 0) return deepest;
        if (matches != 1) return path + " matches multiple supported forms; specify exactly one";
    }
    if (schema.contains("type") && !has_type(value, schema["type"].get<std::string>())) {
        return path + " has the wrong type";
    }
    if (schema.contains("enum") &&
        std::find(schema["enum"].begin(), schema["enum"].end(), value) == schema["enum"].end()) {
        return path + " has an unsupported value";
    }
    if (value.is_object() && schema.contains("properties")) {
        for (const auto& required : schema.value("required", Json::array())) {
            if (!value.contains(required.get<std::string>())) {
                return path + "." + required.get<std::string>() + " is required";
            }
        }
        for (const auto& [key, item] : value.items()) {
            const auto property = schema["properties"].find(key);
            if (property == schema["properties"].end()) {
                if (!schema.value("additionalProperties", true)) return path + "." + key + " is not allowed";
            } else if (const auto error = validate(item, *property, path + "." + key)) {
                return error;
            }
        }
    }
    if (value.is_array() && schema.contains("items")) {
        if (schema.contains("minItems") && value.size() < schema["minItems"].get<std::size_t>()) {
            return path + " has too few items";
        }
        if (schema.contains("maxItems") && value.size() > schema["maxItems"].get<std::size_t>()) {
            return path + " has too many items";
        }
        for (std::size_t index = 0; index < value.size(); ++index) {
            if (const auto error = validate(value[index], schema["items"],
                                            path + "[" + std::to_string(index) + "]")) {
                return error;
            }
        }
        if (schema.value("uniqueItems", false)) {
            for (std::size_t left = 0; left < value.size(); ++left) {
                for (std::size_t right = left + 1; right < value.size(); ++right) {
                    if (value[left] == value[right]) return path + " must contain unique items";
                }
            }
        }
    }
    if (value.is_string() && schema.contains("minLength") &&
        value.get_ref<const std::string&>().size() < schema["minLength"].get<std::size_t>()) {
        return path + " is too short";
    }
    if (value.is_string() && schema.contains("maxLength") &&
        value.get_ref<const std::string&>().size() > schema["maxLength"].get<std::size_t>()) {
        return path + " is too long";
    }
    if (value.is_number() && schema.contains("minimum") &&
        value.get<double>() < schema["minimum"].get<double>()) {
        return path + " is too small";
    }
    if (value.is_number() && schema.contains("maximum") &&
        value.get<double>() > schema["maximum"].get<double>()) {
        return path + " is too large";
    }
    return std::nullopt;
}

bool physical_texture_alias(const std::string_view name) {
    return name.ends_with(".main") || name.ends_with(".alt");
}

bool canonical_pass_id(const std::string_view value) {
    const auto separator = value.find('/');
    if (separator == std::string_view::npos || separator == 0 || separator + 1 == value.size() ||
        value.find('/', separator + 1) != std::string_view::npos) {
        return false;
    }
    constexpr std::array stages{"begin", "prepare", "deferred", "composite", "final", "shadow_composite"};
    if (std::ranges::find(stages, value.substr(0, separator)) == stages.end()) return false;
    const auto program = value.substr(separator + 1);
    if (program.size() > 128 || std::isalnum(static_cast<unsigned char>(program.front())) == 0) return false;
    return std::ranges::all_of(program, [](const unsigned char character) {
        return std::isalnum(character) != 0 || character == '.' || character == '_' || character == '-';
    });
}

std::optional<std::string> validate_canonical_resource_references(const Json& value, const std::string& path) {
    if (value.is_object()) {
        if (const auto name = value.find("logical_name"); name != value.end() && name->is_string() &&
            physical_texture_alias(name->get_ref<const std::string&>())) {
            return path + ".logical_name must use an explicit view instead of a physical suffix alias";
        }
        if (value.value("type", std::string{}) == "texture") {
            const auto name = value.find("name");
            if (name != value.end() && name->is_string() &&
                physical_texture_alias(name->get_ref<const std::string&>())) {
                return path + ".name must use a logical texture name instead of a physical suffix alias";
            }
        }
        const auto type = value.value("type", std::string{});
        if ((type == "dump_texture" || type == "dump_buffer" || type == "texture_stats" || type == "accumulate_texture") && value.contains("pass_id") &&
            !canonical_pass_id(value.at("pass_id").get_ref<const std::string&>())) {
            return path + ".pass_id must use canonical stage/program form";
        }
        if (type == "accumulate_texture" && value.contains("frames") &&
            value.at("frames").get<int>() < 2 * value.value("batches", 16))
            return path + ".frames must be at least twice batches (default 16)";
        for (const auto& [key, item] : value.items()) {
            if (key == "textures" && item.is_array()) {
                for (std::size_t index = 0; index < item.size(); ++index) {
                    if (item[index].is_string() &&
                        physical_texture_alias(item[index].get_ref<const std::string&>())) {
                        return path + ".textures[" + std::to_string(index) +
                            "] must use a logical texture name instead of a physical suffix alias";
                    }
                }
            }
            if (const auto error = validate_canonical_resource_references(item, path + "." + key)) return error;
        }
    } else if (value.is_array()) {
        for (std::size_t index = 0; index < value.size(); ++index) {
            if (const auto error = validate_canonical_resource_references(
                    value[index], path + "[" + std::to_string(index) + "]")) {
                return error;
            }
        }
    }
    return std::nullopt;
}

std::optional<std::string> validate_operation_shape(const std::string_view name, const Json& arguments) {
    if (name == "vibris_preview_presets" && arguments.contains("preset_ids") && arguments.contains("tags"))
        return "arguments.preset_ids and arguments.tags are mutually exclusive";
    const auto require = [&](std::initializer_list<const char*> fields) -> std::optional<std::string> {
        for (const auto* field : fields) {
            if (!arguments.contains(field)) return "arguments." + std::string(field) + " is required";
        }
        return std::nullopt;
    };
    if (name == "vibris_run_recipe") {
        const auto recipe = arguments.at("recipe").get<std::string>();
        if (recipe == "recover_runtime") {
            for (const auto& [key, ignored] : arguments.items()) {
                static_cast<void>(ignored);
                if (key != "recipe" && key != "worktree_root") {
                    return "arguments." + key + " is not allowed for recover_runtime";
                }
            }
            return std::nullopt;
        }
        if (!arguments.contains("preset_id") &&
            !(recipe == "profile_matrix" && arguments.contains("presets"))) return "arguments.preset_id is required";
        if (arguments.contains("presets") && (recipe != "profile_matrix" || arguments.contains("preset_id")))
            return "arguments.presets is only allowed for profile_matrix and is exclusive with preset_id";
        if (recipe == "profile") return require({"frames"});
        if (recipe == "compile_validate") {
            const bool matrix = arguments.contains("sources") || arguments.contains("configs") ||
                arguments.contains("matrix");
            if (matrix) {
                if (arguments.contains("source") || arguments.contains("config")) {
                    return "arguments.source and arguments.config cannot be mixed with a compile matrix";
                }
                if (const auto missing = require({"sources", "configs", "matrix"})) return missing;
            }
            if (arguments.contains("baseline_config") && !arguments.contains("baseline")) {
                return "arguments.baseline is required when arguments.baseline_config is present";
            }
            constexpr std::array allowed{"recipe", "worktree_root", "preset_id", "source", "config",
                "baseline", "baseline_config", "sources", "configs", "matrix", "execution", "restore_state",
                "result_csv", "converted_units", "__vibris_case_id", "__vibris_source_id",
                "__vibris_config_id", "__vibris_workflow_id", "__vibris_result_kind"};
            for (const auto& [key, ignored] : arguments.items()) {
                static_cast<void>(ignored);
                if (std::ranges::find(allowed, key) == allowed.end()) {
                    return "arguments." + key + " is not allowed for compile_validate";
                }
            }
            return std::nullopt;
        }
        if (recipe == "profile_matrix" || recipe == "benchmark_ab") {
            if (recipe == "profile_matrix") {
                if (arguments.value("rounds", 1) > 10) return "arguments.rounds must be at most 10 for profile_matrix";
                const auto order = arguments.value("order", std::string("palindrome"));
                if (order != "palindrome" && order != "sequential" && order != "randomized")
                    return "arguments.order must be palindrome, sequential or randomized for profile_matrix";
                if (arguments.contains("baseline") && (!arguments["baseline"].contains("source_id") ||
                    !arguments["baseline"].contains("config_id"))) return "arguments.baseline requires source_id and config_id";
                if (const auto missing = require({"sources", "configs", "matrix", "frames"})) return missing;
                const auto count = arguments["matrix"]["sources"].size() * arguments["matrix"]["configs"].size() *
                    arguments.value("rounds", 1) * (arguments.contains("presets") ? arguments["presets"].size() : 1) *
                    (order == "palindrome" ? 2 : 1);
                if (count > 512) return "arguments expands to more than 512 experiment steps";
                if (arguments.contains("baseline")) {
                    for (const auto& [field, axis] : {std::pair{"source_id", "sources"}, std::pair{"config_id", "configs"}}) {
                        const auto& values = arguments["matrix"][axis];
                        if (std::find(values.begin(), values.end(), arguments["baseline"][field]) == values.end())
                            return "arguments.baseline." + std::string(field) + " must be selected in matrix";
                    }
                }
                return std::nullopt;
            }
            if (arguments.value("rounds", 2) < 2) return "arguments.rounds must be at least 2 for benchmark_ab";
            if (arguments.contains("order") && arguments["order"] != "abba" && arguments["order"] != "abab" &&
                arguments["order"] != "randomized") return "arguments.order must be abba, abab or randomized for benchmark_ab";
            if (const auto missing = require({"baseline", "candidate", "frames", "metrics", "visual"})) {
                return missing;
            }
            if (arguments.contains("statistic") || arguments.contains("metric_filter")) {
                return "benchmark_ab uses only typed metrics and paired p50/p95 statistics";
            }
            bool visual_threshold = false;
            for (const auto& [key, ignored] : arguments.at("visual").items()) {
                static_cast<void>(ignored);
                if (key != "warmup_frames") visual_threshold = true;
            }
            if (!visual_threshold) return "arguments.visual must contain at least one deterministic threshold";
            std::set<std::string> metric_ids;
            bool target = false;
            for (std::size_t index = 0; index < arguments.at("metrics").size(); ++index) {
                const auto& metric = arguments.at("metrics").at(index);
                const auto metric_id = metric.at("metric_id").get<std::string>();
                if (!metric_ids.insert(metric_id).second) {
                    return "arguments.metrics contains duplicate metric_id " + metric_id;
                }
                const auto role = metric.at("role").get<std::string>();
                if (role == "target") {
                    target = true;
                    if (metric.contains("max_regression_ratio")) {
                        return "arguments.metrics[" + std::to_string(index) +
                            "].max_regression_ratio is not allowed for a target metric";
                    }
                } else if (!metric.contains("max_regression_ratio")) {
                    return "arguments.metrics[" + std::to_string(index) +
                        "].max_regression_ratio is required for a sibling or sentinel metric";
                }
            }
            return target ? std::nullopt : std::optional<std::string>(
                "arguments.metrics must contain at least one target metric");
        }
        if (recipe == "ab_compare") return require({"a", "b", "captures"});
    }
    if (name == "vibris_run_matrix") {
        if (arguments.contains("presets") == arguments.contains("preset_id"))
            return "arguments requires exactly one of presets and preset_id";
        const auto count = arguments["matrix"]["sources"].size() * arguments["matrix"]["configs"].size() *
            (arguments.contains("presets") ? arguments["presets"].size() : 1);
        if (count > 512) return "arguments expands to more than 512 experiment steps";
    }
    if (name == "vibris_artifacts") {
        const auto operation = arguments.at("operation").get<std::string>();
        if (operation == "get") return require({"manifest_id"});
        if (operation == "delete") return require({"manifest_id", "expected_manifest_sha256"});
        if (operation == "prune" && !arguments.contains("older_than_ms") &&
            !arguments.contains("job_ids") && !arguments.contains("recipe") && !arguments.contains("keep_latest"))
            return "arguments.prune requires older_than_ms, job_ids, recipe or keep_latest";
    }
    return std::nullopt;
}

std::string bounded_summary(std::string value) {
    constexpr std::size_t maximum_bytes = 2048;
    if (value.size() > maximum_bytes) {
        std::size_t boundary = maximum_bytes;
        while (boundary != 0 && (static_cast<unsigned char>(value[boundary]) & 0xc0U) == 0x80U) --boundary;
        value.resize(boundary);
    }
    return value;
}

Json mcp_result(std::string_view tool_name, Json payload, bool is_error) {
    Json structured{{"schema_version", 2}, {"success", !is_error}};
    std::string summary;
    if (is_error) {
        summary = payload.value("code", std::string("ERROR")) + ": " +
            payload.value("message", std::string("Tool execution failed."));
        structured["error"] = std::move(payload);
    } else {
        summary = std::string(tool_name) + " completed successfully.";
        structured["result"] = std::move(payload);
    }
    return Json{{"content", Json::array({{{"type", "text"}, {"text", bounded_summary(std::move(summary))}}})},
                {"structuredContent", std::move(structured)},
                {"isError", is_error}};
}

} // namespace

ToolRegistry::ToolRegistry(ToolDispatch dispatch)
    : strict_definitions_(build_definitions()), definitions_(strict_definitions_), dispatch_(std::move(dispatch)) {
    for (auto& definition : definitions_) definition["inputSchema"] = public_schema(definition["inputSchema"]);
}
const Json& ToolRegistry::definitions() const noexcept { return definitions_; }
InvocationResult ToolRegistry::invoke(std::string_view name, const Json& arguments) const {
    const auto definition_it = std::find_if(strict_definitions_.begin(), strict_definitions_.end(), [&](const Json& item) {
        return item["name"].get_ref<const std::string&>() == name;
    });
    if (definition_it == strict_definitions_.end()) {
        return InvocationError{InvocationErrorCode::UnknownTool, "Unknown tool: " + std::string(name)};
    }
    if (const auto error = validate_argument_policy(name, arguments)) return *error;
    if (const auto error = validate(arguments, (*definition_it)["inputSchema"], "arguments")) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }
    if (const auto error = validate_operation_shape(name, arguments)) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }
    if (const auto error = validate_canonical_resource_references(arguments, "arguments")) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }

    ToolOutcome outcome = dispatch_
                              ? dispatch_(name, arguments)
                              : ToolFailure{"SERVER_NOT_READY", "The MCP backend is not connected.", true,
                                            {{"tool", std::string(name)}}};
    if (const auto* value = std::get_if<Json>(&outcome))
        return mcp_result(name, ResultMapper::agent_projection(name, *value, arguments), false);

    const auto& failure = std::get<ToolFailure>(outcome);
    Json error{{"code", failure.code},
               {"message", failure.message},
               {"retryable", failure.retryable},
               {"details", failure.details}};
    return mcp_result(name, ResultMapper::agent_projection(name, std::move(error), arguments), true);
}

} // namespace vibris::mcp