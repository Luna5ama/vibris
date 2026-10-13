#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <filesystem>
#include <fstream>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using vibris::mcp::Json;
using vibris::mcp::ToolOutcome;

constexpr std::string_view workflow_id = "11111111-2222-4333-8444-555555555555";

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

Json arguments(std::string order = "abba", std::size_t rounds = 2) {
    return {{"recipe", "benchmark_ab"},
            {"preset_id", "spawn"},
            {"baseline", {{"kind", "commit"}, {"revision", "HEAD"}}},
            {"candidate", {{"kind", "workspace"}}},
            {"config", {{"QUALITY", 2}}},
            {"warmup_frames", 4},
            {"frames", 32},
            {"rounds", rounds},
            {"control_rounds", rounds},
            {"order", std::move(order)},
            {"random_seed", 7},
            {"metrics", Json::array({
                {{"metric_id", "composite_total"}, {"role", "target"}},
                {{"metric_id", "begin3_a_compute"}, {"role", "sibling"}, {"max_regression_ratio", 0.05}},
                {{"metric_id", "shadow_total"}, {"role", "sentinel"}, {"max_regression_ratio", 0.02}},
            })},
            {"visual", {{"pixel_error_threshold", 0.01}, {"max_threshold_pixel_ratio", 0.001}}},
            {"__vibris_preset", {{"preset_id", "spawn"}, {"version", "2"},
                {"display_name", "Spawn"}}},
            {"max_retries", 1}};
}

Json strict_provenance(std::string source_id, std::string config_hash, std::string scene_hash) {
    return {{"workspace_id", "workspace"}, {"worktree_root", "I:/code/worktree"},
        {"vcs_checkout_state", "VCS_CHECKOUT_STATE_ATTACHED"}, {"branch", "main"},
        {"requested_revision", "HEAD"}, {"resolved_revision", "0123456789abcdef"},
        {"start_head", "0123456789abcdef"}, {"completion_head", "0123456789abcdef"},
        {"head_changed", false}, {"stale", false}, {"shader_tree_id", "shader-tree"},
        {"dirty_shader_delta_sha256", "dirty-sha"},
        {"source_snapshot_sha256", source_id + "-source-hash"}, {"active_source_uuid", std::move(source_id)},
        {"config_sha256", std::move(config_hash)}, {"preset_id", "spawn"},
        {"preset_sha256", "preset-sha"}, {"scene_sha256", std::move(scene_hash)},
        {"effective_settings", {{"settings_sha256", "config-hash"}}},
        {"shader_loaded_at_unix_ms", 1000}, {"pass_mapping_sha256", "mapping-sha"},
        {"environment", {{"minecraft_version", "1.21.11"}, {"iris_version", "iris-test"},
            {"vibris_version", "vibris-test"}, {"java_version", "21"},
            {"operating_system", "Windows"}, {"gpu_vendor", "GPU vendor"},
            {"gpu_renderer", "GPU renderer"}, {"opengl_version", "4.6"},
            {"driver_version", "driver"}}}};
}

Json profile_result(const Json& request, double value, std::string config_hash = "config-hash",
    std::string scene_hash = "scene-hash", std::string program_id = "begin3_a",
    std::optional<std::size_t> frames = std::nullopt) {
    const auto source_id = request.at("__vibris_source_id").get<std::string>();
    const auto case_id = request.at("__vibris_case_id").get<std::string>();
    const auto sampled_frames = frames.value_or(request.at("frames").get<std::size_t>());
    const Json metrics{{"timing_unit", "ns"}, {"sampled_frames", sampled_frames},
        {"metrics", Json::array({
            {{"metric_id", "composite_total"}, {"program_id", ""}, {"pass_id", ""},
                {"average_ns", value}, {"p50_ns", value}, {"p95_ns", value},
                {"samples_ns", Json::array({value})}},
            {{"metric_id", "begin3_a_compute"}, {"program_id", std::move(program_id)},
                {"pass_id", "begin3"}, {"average_ns", value / 2.0}, {"p50_ns", value / 2.0},
                {"p95_ns", value / 2.0}, {"samples_ns", Json::array({value / 2.0})}},
            {{"metric_id", "shadow_total"}, {"program_id", "shadow"}, {"pass_id", "shadow"},
                {"average_ns", value * 0.75}, {"p50_ns", value * 0.75},
                {"p95_ns", value * 0.75}, {"samples_ns", Json::array({value * 0.75})}},
        })}};
    const auto provenance = strict_provenance(source_id, std::move(config_hash), std::move(scene_hash));
    Json profile_case{{"case_id", case_id},
                      {"source_id", source_id},
                      {"config_id", "config"},
                      {"status", "passed"},
                      {"error", nullptr},
                      {"frames", sampled_frames},
                      {"warmup_frames", request.at("warmup_frames")},
                      {"metrics", metrics},
                      {"attempt_count", 1}};
    const Json compile_catalog{{"mapping_sha256", "mapping-sha"}, {"shader_generation", 7},
        {"programs", Json::array({{{"program_id", "begin3_a"}, {"pass_id", "begin3"},
            {"compile_state", "COMPILE_STATE_SUCCEEDED"}, {"link_state", "COMPILE_STATE_SUCCEEDED"},
            {"patched_source_sha256", "patched-sha"}}})}};
    const Json compile_receipt{{"action_index", 1}, {"kind", "ACTION_KIND_INSPECT_SHADER"},
        {"status", "RECEIPT_STATUS_OK"}, {"shader_inspection", {{"catalog", compile_catalog}}}};
    return {{"success", true}, {"status", "completed"},
            {"cases", Json::array({std::move(profile_case)})}, {"artifacts", Json::array()},
            {"action_receipts", Json::array({compile_receipt})}, {"prelude_receipts", Json::array()},
            {"provenance", provenance},
            {"restoration", {{"status", "RECEIPT_STATUS_OK"}}},
            {"result_manifest_id", "profile-manifest"}};
}

double stable_value(const Json& request) {
    const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
    const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
    if (phase == "comparison") return variant == "candidate" ? 80.0 : 100.0;
    return variant == "b" ? 101.0 : 100.0;
}

Json visual_result(bool passed, std::string candidate_config_hash = "visual-config-hash",
    std::string candidate_scene_hash = "visual-scene-hash", bool include_heatmap = true,
    bool include_resets = true) {
    const auto load = [](std::size_t index, std::string source, std::string config_hash,
                          std::string scene_hash) {
        const auto source_hash = source + "-hash";
        return Json{{"action_index", index}, {"kind", "ACTION_KIND_LOAD_SHADER"},
            {"status", "RECEIPT_STATUS_OK"}, {"runtime_mutation", {{"source_uuid", std::move(source)},
                {"source_sha256", source_hash},
                {"effective_settings", {{"settings_sha256", std::move(config_hash)}}},
                {"scene_sha256", std::move(scene_hash)}, {"completed_at_unix_ms", 1000}}}};
    };
    Json result{{"success", true}, {"kind", "ab_compare"},
                {"comparison", {{"baseline_label", "baseline"},
                    {"candidate_label", "candidate"}, {"mean_absolute_error", 0.02},
                    {"root_mean_square_error", 0.03}, {"p95_absolute_error", 0.04},
                    {"max_absolute_error", 0.2},
                    {"threshold_pixel_ratio", passed ? 0.01 : 0.9270258246527778},
                    {"ssim", passed ? 0.98 : 0.8143305138814317},
                    {"sample_count", 300}, {"pixel_count", 100},
                    {"pixel_error_threshold", 0.01}, {"passed", passed},
                    {"verdict", passed ? "passed" : "failed"},
                    {"violations", passed ? Json::array() :
                        Json::array({"THRESHOLD_PIXEL_RATIO_EXCEEDED", "SSIM_BELOW_MINIMUM"})}}},
                {"artifacts", Json::array({{{"kind", "ARTIFACT_KIND_BENCHMARK_METRICS"},
                    {"format", "ARTIFACT_FORMAT_JSON"}, {"relative_path", "visual/diff.json"}}})},
                {"prelude_receipts", Json::array({
                    load(0, "baseline", "visual-config-hash", "visual-scene-hash"),
                    load(3, "candidate", std::move(candidate_config_hash),
                        std::move(candidate_scene_hash)),
                })},
                {"action_receipts", Json::array()}, {"frame_ids", Json::array({41, 42})},
                {"provenance", strict_provenance("candidate", "visual-config-hash", "visual-scene-hash")},
                {"restoration", {{"status", "RECEIPT_STATUS_OK"}}},
                {"result_manifest_id", "visual-manifest"}};
    if (include_resets) {
        result["action_receipts"] = Json::array({
            {{"action_index", 0}, {"kind", "ACTION_KIND_RESET_TEMPORAL_STATE"},
             {"status", "RECEIPT_STATUS_OK"}, {"reset_temporal", {{"completed_at_unix_ms", 1001}}}},
            {{"action_index", 3}, {"kind", "ACTION_KIND_RESET_TEMPORAL_STATE"},
             {"status", "RECEIPT_STATUS_OK"}, {"reset_temporal", {{"completed_at_unix_ms", 1002}}}},
        });
    }
    if (include_heatmap) {
        result["artifacts"].push_back({{"kind", "ARTIFACT_KIND_HEATMAP"},
            {"format", "ARTIFACT_FORMAT_PNG"}, {"relative_path", "visual/diff-heatmap.png"}});
    }
    return result;
}

Json stable_run(const Json& request_arguments, std::vector<Json>* requests = nullptr) {
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [requests](const Json& request) -> ToolOutcome {
            if (requests != nullptr) requests->push_back(request);
            auto profile = profile_result(request, stable_value(request));
            for (auto& metric : profile["cases"][0]["metrics"]["metrics"]) {
                if (metric.at("metric_id") == "shadow_total") {
                    metric["average_ns"] = 75.0;
                    metric["p50_ns"] = 75.0;
                    metric["p95_ns"] = 75.0;
                }
            }
            return profile;
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    require(std::holds_alternative<Json>(outcome), "Paired benchmark returned a tool failure.");
    return std::get<Json>(outcome);
}

std::vector<std::string> comparison_order(const std::vector<Json>& requests) {
    std::vector<std::string> result;
    for (const auto& request : requests) {
        if (request.at("__vibris_benchmark_phase") == "comparison") {
            result.push_back(request.at("__vibris_benchmark_variant").get<std::string>());
        }
    }
    return result;
}

const Json& aggregate_row(const Json& result) {
    const auto& table = result.at("comparison_table");
    const auto found = std::ranges::find_if(table, [](const Json& row) {
        return row.at("metric_id") == "composite_total";
    });
    if (found == table.end()) {
        throw std::runtime_error("Aggregate comparison row is missing: " + table.dump());
    }
    return *found;
}

void order_strategies_are_balanced_and_reproducible() {
    std::vector<Json> abba_requests;
    const auto abba = stable_run(arguments("abba"), &abba_requests);
    const std::vector<std::string> expected_abba{
        "baseline", "candidate", "candidate", "baseline",
        "baseline", "candidate", "candidate", "baseline",
    };
    require(comparison_order(abba_requests) == expected_abba,
        "ABBA did not preserve its paired order in every round.");
    require(abba.at("requested_measurements") == 16 && abba.at("round_samples").size() == 2 &&
            abba.at("control_round_samples").size() == 2,
        "ABBA did not include four comparison and four control measurements per round.");
    for (const auto& request : abba_requests) {
        require(request.at("__vibris_workflow_id") == std::string(workflow_id),
            "A nested profile omitted the isolation workflow identity.");
        require(request.at("preset_id") == "spawn",
            "A nested profile changed the explicit preset identity.");
        require(request.at("__vibris_preset") == arguments().at("__vibris_preset"),
            "A nested profile omitted the request-scoped scene-preset provenance.");
        require(request.at("__vibris_compile_gate") == true &&
                request.at("statistics") == Json::array({"p50", "p95"}),
            "A nested profile omitted its mandatory compile gate or p50/p95 statistics.");
        if (request.at("__vibris_benchmark_phase") == "control") {
            require(request.at("source") == arguments().at("baseline") &&
                    request.at("__vibris_source_id") == "baseline",
                "A same-commit control measurement did not use the baseline source.");
        }
    }

    std::vector<Json> abab_requests;
    static_cast<void>(stable_run(arguments("abab"), &abab_requests));
    const std::vector<std::string> expected_abab{
        "baseline", "candidate", "baseline", "candidate",
        "baseline", "candidate", "baseline", "candidate",
    };
    require(comparison_order(abab_requests) == expected_abab,
        "ABAB did not preserve its paired order in every round.");

    std::vector<Json> random_first;
    std::vector<Json> random_second;
    static_cast<void>(stable_run(arguments("randomized"), &random_first));
    static_cast<void>(stable_run(arguments("randomized"), &random_second));
    const auto first_order = comparison_order(random_first);
    require(first_order == comparison_order(random_second),
        "Seeded randomized order was not reproducible.");
    for (std::size_t offset = 0; offset < first_order.size(); offset += 4) {
        const auto baseline = std::count(first_order.begin() + offset, first_order.begin() + offset + 4, "baseline");
        const auto candidate = std::count(first_order.begin() + offset, first_order.begin() + offset + 4, "candidate");
        require(baseline == 2 && candidate == 2,
            "Randomized order did not keep a balanced pair inside each round.");
    }
}

void paired_aggregation_reports_effect_noise_and_confidence() {
    const auto result = stable_run(arguments("abba", 4));
    const auto& row = aggregate_row(result);
    require(result.at("success") == true && result.at("status") == "completed" &&
            result.at("verdict") == "ACCEPTED" && result.at("guards").at("passed") == true &&
            result.at("guards").at("runtime_state_restored") == true,
        "Stable paired samples did not produce a successful guarded result.");
    require(row.at("role") == "target" && row.at("baseline_p50_ns") == 100.0 &&
            row.at("candidate_p50_ns") == 80.0 && row.at("baseline_p95_ns") == 100.0 &&
            row.at("candidate_p95_ns") == 80.0 && row.at("paired_delta_ns") == -20.0 &&
            row.at("paired_delta_variance_ns2") == 0.0 &&
            row.at("noise_floor_ns") == 1.0 && row.at("clears_noise_floor") == true &&
            row.at("confidence_interval_95").at("low_ns") == -20.0 &&
            row.at("confidence_interval_95").at("high_ns") == -20.0 &&
            row.at("order_effect_ns") == 0.0 && row.at("direction_reversed") == false &&
            row.at("thermal_or_temporal_drift") == false && row.at("outlier_rounds").empty() &&
            row.at("decision") == "ACCEPTED" && row.at("direction") == "improved" &&
            result.at("acceptance_gates").at("compile") == true &&
            result.at("acceptance_gates").at("visual") == true,
        "Paired aggregation lost its median-of-runs, delta, variance, confidence, noise, or verdict fields.");
    require(result.at("round_samples").size() == 4 &&
            result.at("round_samples").at(0).at("metrics").at(0).contains("baseline_p50_samples_ns") &&
            result.at("comparison_table").size() == 3,
        "Paired result omitted per-round samples or the compact aggregate/program comparison table.");
    const auto sentinel = std::ranges::find_if(result.at("comparison_table"), [](const Json& value) {
        return value.at("role") == "sentinel";
    });
    require(sentinel != result.at("comparison_table").end() && sentinel->at("clears_noise_floor") == false &&
            sentinel->at("decision") == "GUARDRAIL_PASSED" && sentinel->at("guardrail_passed") == true,
        "An unchanged below-noise sentinel did not pass its non-regression guardrail.");
}

void measured_noise_floor_rejects_small_effects() {
    const auto request_arguments = arguments("abba", 4);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [](const Json& request) -> ToolOutcome {
            const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
            const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
            const auto value = phase == "comparison" ? (variant == "candidate" ? 105.0 : 100.0) :
                (variant == "b" ? 110.0 : 100.0);
            return profile_result(request, value);
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& result = std::get<Json>(outcome);
    const auto& row = aggregate_row(result);
    require(result.at("success") == false && result.at("verdict") == "INCONCLUSIVE" &&
            row.at("paired_delta_ns") == 5.0 && row.at("noise_floor_ns") == 10.0 &&
            row.at("clears_noise_floor") == false && row.at("decision") == "INCONCLUSIVE" &&
            row.at("direction") == "unchanged",
        "A candidate effect inside the measured same-commit noise floor was accepted as stable.");
}

void mismatched_frames_config_scene_and_program_identity_fail_closed() {
    const auto request_arguments = arguments("abba", 2);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [](const Json& request) -> ToolOutcome {
            const bool candidate = request.at("__vibris_source_id") == "candidate";
            return profile_result(request, stable_value(request), candidate ? "changed-config" : "config-hash",
                candidate ? "changed-scene" : "scene-hash",
                candidate ? "ChangedProgram.comp.glsl" : "GenerateSkyViewLUT.comp.glsl",
                candidate ? std::optional<std::size_t>{33} : std::nullopt);
        });
    const auto& result = std::get<Json>(outcome);
    std::set<std::string> codes;
    for (const auto& mismatch : result.at("guards").at("mismatches")) {
        codes.insert(mismatch.at("code").get<std::string>());
    }
    require(result.at("success") == false && result.at("status") == "invalid_comparison" &&
            result.at("verdict") == "GATE_FAILED" && result.at("comparison_table").empty() &&
            codes.contains("FRAME_COUNT_MISMATCH") && codes.contains("CONFIG_HASH_MISMATCH") &&
            codes.contains("SCENE_HASH_MISMATCH") && codes.contains("PROGRAM_IDENTITY_MISMATCH"),
        "Paired comparison did not fail closed on frames, effective config, scene, and program identity mismatches.");

    std::size_t calls = 0;
    const auto restore_outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [&calls](const Json& request) -> ToolOutcome {
            ++calls;
            auto profile = profile_result(request, stable_value(request));
            profile["restoration"]["status"] = "RECEIPT_STATUS_FAILED";
            return profile;
        });
    const auto& restore_result = std::get<Json>(restore_outcome);
    std::set<std::string> restore_codes;
    for (const auto& mismatch : restore_result.at("guards").at("mismatches")) {
        restore_codes.insert(mismatch.at("code").get<std::string>());
    }
    require(calls == 1 && restore_result.at("success") == false &&
            restore_result.at("status") == "incomplete" &&
            restore_result.at("requested_measurements") == 16 &&
            restore_result.at("remaining_measurements") == 15 &&
            restore_result.at("guards").at("runtime_state_restored") == false &&
            restore_codes.contains("RUNTIME_STATE_RESTORE_MISSING") &&
            restore_codes.contains("WORKFLOW_HALTED_BEFORE_RESTORATION"),
        "A missing restoration receipt did not halt later paired measurements.");
}

void visual_gate_returns_combined_performance_and_visual_verdicts() {
    auto request_arguments = arguments("abba", 2);
    request_arguments["visual"] = {{"warmup_frames", 6}, {"pixel_error_threshold", 0.01},
        {"max_threshold_pixel_ratio", 0.001}, {"min_ssim", 0.995}};
    Json visual_request;
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            return profile_result(request, stable_value(request));
        },
        [&visual_request](const Json& request) -> ToolOutcome {
            visual_request = request;
            return visual_result(false);
        });
    const auto& result = std::get<Json>(outcome);
    require(visual_request.at("recipe") == "ab_compare" &&
            visual_request.at("a").at("source") == request_arguments.at("baseline") &&
            visual_request.at("b").at("source") == request_arguments.at("candidate") &&
            visual_request.at("preset_id") == request_arguments.at("preset_id") &&
            visual_request.at("__vibris_preset") == request_arguments.at("__vibris_preset") &&
            visual_request.at("warmup_frames") == 6 &&
            visual_request.at("captures").at(0).at("type") == "screenshot" &&
            visual_request.at("visual_thresholds").at("min_ssim") == 0.995,
        "The paired benchmark did not request a deterministic screenshot comparison with its sources and thresholds.");
    require(result.at("success") == false && result.at("status") == "completed_with_failures" &&
            result.at("requested_measurements") == 16 &&
            result.at("performance_verdict") == "ACCEPTED" && result.at("visual_verdict") == "failed" &&
            result.at("verdict") == "GATE_FAILED" && result.at("visual").at("status") == "failed" &&
            result.at("visual").at("comparison").at("ssim") == 0.8143305138814317 &&
            result.at("visual").at("comparison").at("threshold_pixel_ratio") == 0.9270258246527778 &&
            result.at("visual").at("guards").at("passed") == true &&
            result.at("artifacts").back().at("benchmark_phase") == "visual",
        "A failed visual gate did not fail the benchmark while preserving the performance verdict and diff artifact.");
}

void visual_receipts_fail_closed_on_state_or_artifact_mismatch() {
    auto request_arguments = arguments("abba", 2);
    request_arguments["visual"] = {{"pixel_error_threshold", 0.01}, {"max_absolute_error", 0.1}};
    const auto run = [&request_arguments](std::string config_hash, std::string scene_hash,
                         bool include_heatmap, bool include_resets = true) {
        return std::get<Json>(vibris::mcp::run_paired_benchmark(
            request_arguments, workflow_id, 19,
            [](const Json& request) -> ToolOutcome {
                return profile_result(request, stable_value(request));
            },
            [config_hash = std::move(config_hash), scene_hash = std::move(scene_hash),
                include_heatmap, include_resets](const Json&) mutable -> ToolOutcome {
                return visual_result(true, std::move(config_hash), std::move(scene_hash),
                    include_heatmap, include_resets);
            }));
    };

    const auto mismatched = run("changed-config", "changed-scene", true);
    std::set<std::string> mismatch_codes;
    for (const auto& mismatch : mismatched.at("visual").at("guards").at("mismatches")) {
        mismatch_codes.insert(mismatch.at("code").get<std::string>());
    }
    require(mismatched.at("success") == false && mismatched.at("status") == "invalid_comparison" &&
            mismatched.at("performance_verdict") == "ACCEPTED" &&
            mismatched.at("visual_verdict") == "inconclusive" &&
            mismatched.at("verdict") == "GATE_FAILED" &&
            mismatched.at("visual").at("error").at("error_code") == "INVALID_VISUAL_RECEIPT" &&
            mismatch_codes.contains("VISUAL_CONFIG_HASH_MISMATCH") &&
            mismatch_codes.contains("VISUAL_SCENE_HASH_MISMATCH"),
        "A visual receipt with mismatched config or scene provenance was accepted.");

    const auto missing_artifact = run("visual-config-hash", "visual-scene-hash", false);
    require(missing_artifact.at("success") == false &&
            missing_artifact.at("status") == "invalid_comparison" &&
            missing_artifact.at("verdict") == "GATE_FAILED" &&
            missing_artifact.at("visual").at("guards").at("diff_heatmap_artifact") == false,
        "A visual receipt without the required difference heatmap was accepted.");

    const auto missing_resets = run("visual-config-hash", "visual-scene-hash", true, false);
    std::set<std::string> reset_codes;
    for (const auto& mismatch : missing_resets.at("visual").at("guards").at("mismatches")) {
        reset_codes.insert(mismatch.at("code").get<std::string>());
    }
    require(missing_resets.at("success") == false &&
            missing_resets.at("status") == "invalid_comparison" &&
            missing_resets.at("visual").at("guards").at("two_ordered_temporal_reset_receipts") == false &&
            reset_codes.contains("TWO_ORDERED_TEMPORAL_RESET_RECEIPTS_REQUIRED"),
        "A visual receipt without both ordered successful temporal resets was accepted.");
}

void typed_sibling_and_sentinel_guardrails_reject_regressions() {
    auto request_arguments = arguments("abba", 4);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            auto profile = profile_result(request, stable_value(request));
            if (request.at("__vibris_benchmark_phase") == "comparison" &&
                request.at("__vibris_benchmark_variant") == "candidate") {
                for (auto& metric : profile["cases"][0]["metrics"]["metrics"]) {
                    if (metric.at("metric_id") == "begin3_a_compute") {
                        metric["average_ns"] = 60.0;
                        metric["p50_ns"] = 60.0;
                        metric["p95_ns"] = 60.0;
                    } else if (metric.at("metric_id") == "shadow_total") {
                        metric["average_ns"] = 90.0;
                        metric["p50_ns"] = 90.0;
                        metric["p95_ns"] = 90.0;
                    }
                }
            }
            return profile;
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& result = std::get<Json>(outcome);
    const auto& table = result.at("comparison_table");
    const auto sibling = std::ranges::find_if(table, [](const Json& row) {
        return row.at("role") == "sibling";
    });
    const auto sentinel = std::ranges::find_if(table, [](const Json& row) {
        return row.at("role") == "sentinel";
    });
    require(result.at("verdict") == "REGRESSION" && result.at("success") == false &&
            sibling != table.end() && sibling->at("guardrail_passed") == false &&
            std::abs(sibling->at("regression_ratio").get<double>() - 0.2) < 1e-9 &&
            sibling->at("decision") == "REGRESSION" && sentinel != table.end() &&
            sentinel->at("guardrail_passed") == false && sentinel->at("decision") == "REGRESSION",
        "A stable sibling or sentinel regression beyond its explicit guardrail was not rejected.");

    const auto target_outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
            const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
            const auto value = phase == "comparison" ? (variant == "candidate" ? 120.0 : 100.0) :
                (variant == "b" ? 101.0 : 100.0);
            return profile_result(request, value);
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& target_result = std::get<Json>(target_outcome);
    require(target_result.at("verdict") == "REGRESSION" &&
            aggregate_row(target_result).at("decision") == "REGRESSION",
        "A stable target regression was not rejected.");
}

void direction_reversal_is_inconclusive() {
    const auto request_arguments = arguments("abba", 4);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
            const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
            double value = 100.0;
            if (phase == "comparison" && variant == "candidate") {
                value = request.at("__vibris_benchmark_slot") == 2 ? 80.0 : 120.0;
            } else if (phase == "control" && variant == "b") {
                value = 101.0;
            }
            return profile_result(request, value);
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& result = std::get<Json>(outcome);
    const auto& row = aggregate_row(result);
    require(result.at("verdict") == "INCONCLUSIVE" && result.at("direction_reversed") == true &&
            row.at("direction_reversed") == true && row.at("decision") == "INCONCLUSIVE",
        "Opposite above-noise order directions were not reported as an inconclusive reversal.");
}

void thermal_or_temporal_drift_is_inconclusive() {
    const auto request_arguments = arguments("abab", 4);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
            const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
            double value = stable_value(request);
            if (phase == "control" && request.at("__vibris_benchmark_round").get<std::size_t>() > 2) {
                value += 20.0;
            }
            return profile_result(request, value);
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& result = std::get<Json>(outcome);
    require(result.at("verdict") == "INCONCLUSIVE" && result.at("thermal_or_temporal_drift") == true &&
            aggregate_row(result).at("thermal_or_temporal_drift") == true,
        "Same-source control drift did not block benchmark acceptance.");
}

void compile_catalog_is_a_mandatory_gate() {
    const auto request_arguments = arguments("abba", 2);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19,
        [](const Json& request) -> ToolOutcome {
            auto profile = profile_result(request, stable_value(request));
            profile["action_receipts"] = Json::array();
            return profile;
        }, [](const Json&) -> ToolOutcome { return visual_result(true); });
    const auto& result = std::get<Json>(outcome);
    std::set<std::string> codes;
    for (const auto& mismatch : result.at("guards").at("mismatches")) {
        codes.insert(mismatch.at("code").get<std::string>());
    }
    require(result.at("verdict") == "GATE_FAILED" && result.at("success") == false &&
            result.at("acceptance_gates").at("compile") == false && codes.contains("COMPILE_GATE_FAILED"),
        "A missing compile catalog receipt did not fail the mandatory benchmark gate.");
}

void single_program_pass_aliases_are_verified_and_ambiguities_fail_closed() {
    for (int mode = 0; mode < 7; ++mode) {
        const auto outcome = vibris::mcp::run_paired_benchmark(arguments(), workflow_id, 19,
            [mode](const Json& request) -> ToolOutcome {
                auto profile = profile_result(request, stable_value(request));
                auto& timings = profile["cases"][0]["metrics"]["metrics"];
                auto program = timings[1];
                program["main_source"] = "/test/begin3_a.comp.glsl";
                timings[1] = program;
                auto alias = program;
                alias["program_id"] = ""; alias["main_source"] = "";
                if (mode == 1) alias["samples_ns"][0] = alias["samples_ns"][0].get<double>() + 1;
                if (mode == 2) alias.erase("samples_ns");
                if (mode == 3) { auto second=program;second["program_id"]="begin3_b";timings.push_back(second); }
                if (mode == 4) timings.push_back(program);
                if (mode == 5 || mode == 6) {
                    const double invalid=mode == 5 ? -1.0 : std::numeric_limits<double>::infinity();
                    timings[1]["samples_ns"][0]=invalid;alias["samples_ns"][0]=invalid;
                }
                timings.push_back(alias);
                return profile;
            }, [](const Json&) -> ToolOutcome { return visual_result(true); });
        const auto& result = std::get<Json>(outcome);
        if (mode == 0) {
            require(result.at("guards").at("passed") == true, "identical single-program pass alias rejected");
            require(result.at("guards").at("program_identity_count") == 2, "pass alias counted as another program");
        } else {
            require(result.at("guards").at("passed") == false && result.at("verdict") == "GATE_FAILED",
                "conflicting/missing/multi-program/repeated timing identity accepted");
        }
    }
}

// CPU-only entry point: callbacks return existing immutable receipts. No runtime, MCP or GPU call is made.
int reanalyze_saved_receipts(const char* request_path, const char* receipts_path, const char* output_path) {
    std::ifstream request_file(request_path); if(!request_file) throw std::runtime_error("missing request");
    Json saved_request = Json::parse(request_file);
    const auto request_arguments = saved_request.at("params").at("arguments");
    std::size_t index=0;
    const auto read_receipt = [&](std::size_t n) {
        const auto number=std::to_string(n);
        const auto name=std::string(8-number.size(),'0')+number+".json";
        std::ifstream file(std::filesystem::path(receipts_path)/name);
        if(!file) throw std::runtime_error("missing saved receipt");
        const auto receipt=Json::parse(file);
        require(receipt.at("success")==true,"saved receipt failed");
        return receipt.at("result");
    };
    const auto outcome=vibris::mcp::run_paired_benchmark(request_arguments,"offline-saved-evidence",128,
        [&](const Json& requested) -> ToolOutcome {
            auto profile=read_receipt(index++);
            require(profile.at("cases").size()==1,"saved profile is not one case");
            const auto& c=profile.at("cases")[0];
            require(c.at("case_id")==requested.at("__vibris_case_id"),"saved case ordering mismatch");
            require(c.at("source_id")==requested.at("__vibris_source_id"),"saved source role mismatch");
            const auto role=requested.at("__vibris_source_id").get<std::string>();
            const auto& source=request_arguments.at(role);
            if(source.at("kind")=="commit") {
                require(profile.at("provenance").at("requested_revision")==source.at("revision"),
                    "saved source revision mismatch");
            }
            return profile;
        }, [&](const Json&) -> ToolOutcome { return read_receipt(index++); });
    require(std::holds_alternative<Json>(outcome),"offline benchmark analysis failed");
    std::ofstream output(output_path); if(!output) throw std::runtime_error("cannot write analysis");
    output<<std::get<Json>(outcome).dump(2)<<'\n';
    std::cout<<"OFFLINE_REANALYSIS_RECEIPTS="<<index<<'\n';
    return 0;
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 11> test_cases{{
    {"PairedOrderStrategies", order_strategies_are_balanced_and_reproducible},
    {"PairedAggregation", paired_aggregation_reports_effect_noise_and_confidence},
    {"MeasuredNoiseFloorRejection", measured_noise_floor_rejects_small_effects},
    {"PairedMismatchGuards", mismatched_frames_config_scene_and_program_identity_fail_closed},
    {"PairedVisualGate", visual_gate_returns_combined_performance_and_visual_verdicts},
    {"PairedVisualReceiptGuards", visual_receipts_fail_closed_on_state_or_artifact_mismatch},
    {"TypedGuardrailRegression", typed_sibling_and_sentinel_guardrails_reject_regressions},
    {"PairedDirectionReversal", direction_reversal_is_inconclusive},
    {"PairedTemporalDrift", thermal_or_temporal_drift_is_inconclusive},
    {"PairedCompileGate", compile_catalog_is_a_mandatory_gate},
    {"PairedMetricAliases", single_program_pass_aliases_are_verified_and_ambiguities_fail_closed},
}};

} // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 5 && std::string_view(argv[1]) == "--reanalyze")
            return reanalyze_saved_receipts(argv[2],argv[3],argv[4]);
        if (argc == 2) {
            const auto found = std::ranges::find_if(test_cases, [&](const auto& value) {
                return value.first == argv[1];
            });
            if (found == test_cases.end()) throw std::runtime_error("unknown paired benchmark scenario");
            found->second();
            std::cout << "PASS " << found->first << '\n';
            return 0;
        }
        for (const auto& [name, test] : test_cases) {
            test();
            std::cout << "PASS " << name << '\n';
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL paired benchmark: " << error.what() << '\n';
        return 1;
    }
}
