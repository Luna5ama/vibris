#include "paired_benchmark.hpp"
#include "native_metrics.hpp"
#include "synchronous_job_runner.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <iterator>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <random>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

struct PlannedMeasurement final {
    std::string phase;
    std::size_t round = 0;
    std::size_t slot = 0;
    std::string variant;
    std::string physical_source;
    std::string case_id;
};

struct MetricValue final {
    Json identity;
    double p50_ns = 0.0;
    double p95_ns = 0.0;
};

struct Measurement final {
    PlannedMeasurement plan;
    Json profile = nullptr;
    Json profile_case = nullptr;
    std::map<std::string, MetricValue> metrics;
};

struct GuardState final {
    Json mismatches = Json::array();
    std::string config_hash;
    std::string scene_hash;
    std::map<std::string, std::string> source_hashes;
    std::set<std::string> aggregate_identities;
    std::set<std::string> program_identities;
    Json identities = Json::object();
    std::map<std::string, Json> metric_specs;
    bool runtime_state_restored = true;
    bool compile_catalogs_passed = true;
};

std::string padded(std::size_t value) {
    auto result = std::to_string(value);
    if (result.size() < 2) result.insert(result.begin(), 2 - result.size(), '0');
    return result;
}

std::vector<std::string> order_for(
    std::string_view strategy, std::string first, std::string second, std::mt19937& random) {
    if (strategy == "abab") return {first, second, first, second};
    if (strategy == "randomized") {
        std::vector result{first, first, second, second};
        std::shuffle(result.begin(), result.end(), random);
        return result;
    }
    return {first, second, second, first};
}

std::vector<PlannedMeasurement> plan(const Json& arguments) {
    const auto rounds = arguments.value("rounds", std::size_t{3});
    const auto control_rounds = arguments.value("control_rounds", rounds);
    const auto strategy = arguments.value("order", std::string("abba"));
    std::mt19937 random(arguments.value("random_seed", std::uint32_t{0}));
    std::vector<PlannedMeasurement> result;
    result.reserve((rounds + control_rounds) * 4);
    for (std::size_t round = 1; round <= std::max(rounds, control_rounds); ++round) {
        if (round <= rounds) {
            const auto order = order_for(strategy, "baseline", "candidate", random);
            for (std::size_t slot = 0; slot < order.size(); ++slot) {
                result.push_back({"comparison", round, slot + 1, order[slot], order[slot],
                    "ab-r" + padded(round) + "-s" + std::to_string(slot + 1) + "-" + order[slot]});
            }
        }
        if (round <= control_rounds) {
            const auto order = order_for(strategy, "a", "b", random);
            for (std::size_t slot = 0; slot < order.size(); ++slot) {
                result.push_back({"control", round, slot + 1, order[slot], "baseline",
                    "noise-r" + padded(round) + "-s" + std::to_string(slot + 1) + "-" + order[slot]});
            }
        }
    }
    return result;
}

Json profile_arguments(const Json& arguments, const PlannedMeasurement& measurement,
    std::string_view workflow_id, std::size_t default_warmup_frames) {
    Json result{{"recipe", "profile"},
                {"preset_id", arguments.at("preset_id")},
                {"source", arguments.at(measurement.physical_source)},
                {"frames", arguments.at("frames")},
                {"warmup_frames", arguments.value("warmup_frames", default_warmup_frames)},
                {"result_detail", "metrics"},
                {"statistics", Json::array({"p50", "p95"})},
                {"max_retries", arguments.value("max_retries", std::size_t{2})},
                {"__vibris_compile_gate", true},
                {"__vibris_case_id", measurement.case_id},
                {"__vibris_source_id", measurement.physical_source},
                {"__vibris_config_id", "config"},
                {"__vibris_result_kind", "benchmark_ab"},
                {"__vibris_workflow_id", workflow_id},
                {"__vibris_benchmark_phase", measurement.phase},
                {"__vibris_benchmark_round", measurement.round},
                {"__vibris_benchmark_slot", measurement.slot},
                {"__vibris_benchmark_variant", measurement.variant}};
    if (arguments.contains("config")) result["config"] = arguments.at("config");
    Json metric_filter = Json::array();
    for (const auto& metric : arguments.at("metrics")) metric_filter.push_back(metric.at("metric_id"));
    result["metric_filter"] = std::move(metric_filter);
    if (arguments.contains("__vibris_preset")) {
        result["__vibris_preset"] = arguments.at("__vibris_preset");
    }
    return result;
}

Json failure_json(const ToolFailure& failure) {
    return {{"success", false}, {"error_code", failure.code}, {"message", failure.message},
            {"retryable", failure.retryable}, {"details", failure.details}};
}

Json visual_arguments(const Json& arguments, std::size_t default_warmup_frames) {
    auto thresholds = arguments.at("visual");
    const auto warmup = thresholds.value(
        "warmup_frames", arguments.value("warmup_frames", default_warmup_frames));
    thresholds.erase("warmup_frames");
    Json result{{"recipe", "ab_compare"},
                {"preset_id", arguments.at("preset_id")},
                {"a", {{"label", "baseline"}, {"source", arguments.at("baseline")}}},
                {"b", {{"label", "candidate"}, {"source", arguments.at("candidate")}}},
                {"warmup_frames", warmup},
                {"captures", Json::array({{{"type", "screenshot"}, {"format", "png"}}})},
                {"visual_thresholds", std::move(thresholds)}};
    if (arguments.contains("config")) result["config"] = arguments.at("config");
    if (arguments.contains("__vibris_preset")) {
        result["__vibris_preset"] = arguments.at("__vibris_preset");
    }
    return result;
}

Json visual_receipt(ToolOutcome outcome) {
    if (const auto* failure = std::get_if<ToolFailure>(&outcome)) {
        return {{"requested", true}, {"success", false}, {"status", "failed"},
                {"verdict", "inconclusive"}, {"error", failure_json(*failure)},
                {"comparison", nullptr}, {"artifacts", Json::array()},
                {"guards", {{"passed", false}, {"mismatches", Json::array({
                    {{"code", "VISUAL_EXECUTION_FAILED"},
                     {"message", "The visual comparison job did not complete successfully."}}
                })}}}};
    }
    auto result = std::get<Json>(std::move(outcome));
    auto guards = visual_comparison_guards(result, true);
    const auto comparison = result.find("comparison");
    if (!guards.at("passed").get<bool>()) {
        return {{"requested", true}, {"success", false}, {"status", "invalid"},
                {"verdict", "inconclusive"},
                {"error", {{"success", false}, {"error_code", "INVALID_VISUAL_RECEIPT"},
                    {"message", "The visual comparison receipt failed its deterministic-state guards."},
                    {"retryable", false}, {"details", guards.at("mismatches")}}},
                {"comparison", comparison != result.end() && comparison->is_object()
                    ? Json(*comparison) : Json(nullptr)},
                {"artifacts", result.value("artifacts", Json::array())},
                {"action_receipts", result.value("action_receipts", Json::array())},
                {"prelude_receipts", result.value("prelude_receipts", Json::array())},
                {"provenance", result.value("provenance", Json(nullptr))},
                {"restoration", result.value("restoration", Json(nullptr))},
                {"frame_ids", result.value("frame_ids", Json::array())},
                {"guards", std::move(guards)}};
    }
    const bool passed = comparison->at("passed").get<bool>();
    Json receipt{{"requested", true}, {"success", passed},
                 {"status", passed ? "passed" : "failed"},
                 {"verdict", comparison->at("verdict")},
                 {"error", nullptr}, {"comparison", *comparison},
                 {"artifacts", result.value("artifacts", Json::array())},
                 {"action_receipts", result.value("action_receipts", Json::array())},
                 {"prelude_receipts", result.value("prelude_receipts", Json::array())},
                 {"provenance", result.value("provenance", Json(nullptr))},
                 {"restoration", result.value("restoration", Json(nullptr))},
                 {"frame_ids", result.value("frame_ids", Json::array())},
                 {"guards", std::move(guards)}};
    return receipt;
}

Json failed_case(const PlannedMeasurement& measurement, const Json& arguments, Json error,
    std::size_t default_warmup_frames) {
    return {{"case_id", measurement.case_id},
            {"source_id", measurement.physical_source},
            {"config_id", "config"},
            {"status", "failed"},
            {"error", std::move(error)},
            {"frames", arguments.at("frames")},
            {"warmup_frames", arguments.value("warmup_frames", default_warmup_frames)},
            {"metrics", nullptr},
            {"provenance", nullptr},
            {"barriers", Json::array()}};
}

Json first_case(const Json& profile, const PlannedMeasurement& measurement, const Json& arguments,
    std::size_t default_warmup_frames) {
    const auto cases = profile.find("cases");
    if (cases != profile.end() && cases->is_array() && cases->size() == 1 && cases->front().is_object()) {
        return cases->front();
    }
    return failed_case(measurement, arguments,
        {{"success", false}, {"error_code", "MALFORMED_PROFILE_RESULT"},
         {"message", "The nested profile did not return exactly one normalized case."}, {"retryable", false}},
        default_warmup_frames);
}

std::string text_at(const Json& value, std::initializer_list<const char*> path) {
    const Json* current = &value;
    for (const auto* name : path) {
        if (!current->is_object()) return {};
        const auto found = current->find(name);
        if (found == current->end()) return {};
        current = &*found;
    }
    return current->is_string() ? current->get<std::string>() : std::string{};
}

std::map<std::string, MetricValue> metric_values(const Json& profile_case) {
    std::map<std::string, MetricValue> result;
    const auto metrics = profile_case.find("metrics");
    if (metrics == profile_case.end() || !metrics->is_object()) return result;
    const auto values = metrics->value("metrics", Json::array());
    for (const auto& timing : values) {
        if (!timing.is_object()) continue;
        const auto p50 = timing.find("p50_ns");
        const auto p95 = timing.find("p95_ns");
        if (p50 == timing.end() || !p50->is_number() || p95 == timing.end() || !p95->is_number()) continue;
        const auto program = timing.value("program_id", std::string{});
        const auto pass = timing.value("pass_id", std::string{});
        Json identity{{"timing_kind", program.empty() && pass.empty() ? "aggregate" : "program"},
            {"metric", timing.value("metric_id", std::string{})}};
        if (!program.empty() || !pass.empty()) {
            identity["program"] = program;
            identity["framework_pass"] = pass;
        }
        const auto key = identity.dump();
        result.emplace(key, MetricValue{std::move(identity), p50->get<double>(), p95->get<double>()});
    }
    return result;
}

bool restored(const Json& profile) {
    const auto receipt = profile.find("restoration");
    return receipt != profile.end() && receipt->is_object() &&
        receipt->value("status", std::string{}) == "RECEIPT_STATUS_OK";
}

bool compile_catalog_passed(const Json& profile) {
    const auto receipts = profile.find("action_receipts");
    if (receipts == profile.end() || !receipts->is_array()) return false;
    for (const auto& receipt : *receipts) {
        if (!receipt.is_object() || receipt.value("kind", std::string{}) != "ACTION_KIND_INSPECT_SHADER" ||
            receipt.value("status", std::string{}) != "RECEIPT_STATUS_OK") continue;
        const auto inspection = receipt.find("shader_inspection");
        if (inspection == receipt.end() || !inspection->is_object()) return false;
        const auto catalog = inspection->find("catalog");
        if (catalog == inspection->end() || !catalog->is_object() ||
            catalog->value("mapping_sha256", std::string{}).empty() ||
            catalog->value("shader_generation", std::uint64_t{}) == 0) return false;
        const auto programs = catalog->find("programs");
        return programs != catalog->end() && programs->is_array() && !programs->empty() &&
            std::ranges::all_of(*programs, [](const Json& program) {
                return program.is_object() &&
                    program.value("compile_state", std::string{}) == "COMPILE_STATE_SUCCEEDED" &&
                    program.value("link_state", std::string{}) == "COMPILE_STATE_SUCCEEDED" &&
                    !program.value("patched_source_sha256", std::string{}).empty();
            });
    }
    return false;
}

void mismatch(GuardState& guards, std::string code, const PlannedMeasurement& measurement,
    std::string field, Json expected, Json actual) {
    guards.mismatches.push_back({{"code", std::move(code)}, {"case_id", measurement.case_id},
        {"phase", measurement.phase}, {"round", measurement.round}, {"slot", measurement.slot},
        {"field", std::move(field)}, {"expected", std::move(expected)}, {"actual", std::move(actual)}});
}

std::set<std::string> identities(const Measurement& measurement, std::string_view kind) {
    std::set<std::string> result;
    for (const auto& [key, metric] : measurement.metrics) {
        if (metric.identity.value("timing_kind", std::string{}) == kind) result.insert(key);
    }
    return result;
}

void validate_guards(const std::vector<Measurement>& measurements, const Json& arguments, GuardState& guards) {
    const auto expected_frames = arguments.at("frames");
    bool reference_metrics = false;
    for (const auto& measurement : measurements) {
        const auto& profile_case = measurement.profile_case;
        if (profile_case.value("case_id", std::string{}) != measurement.plan.case_id) {
            mismatch(guards, "CASE_ID_MISMATCH", measurement.plan, "case_id", measurement.plan.case_id,
                profile_case.value("case_id", Json(nullptr)));
        }
        if (profile_case.value("source_id", std::string{}) != measurement.plan.physical_source) {
            mismatch(guards, "SOURCE_ROLE_MISMATCH", measurement.plan, "source_id",
                measurement.plan.physical_source, profile_case.value("source_id", Json(nullptr)));
        }
        if (!restored(measurement.profile)) {
            guards.runtime_state_restored = false;
            mismatch(guards, "RUNTIME_STATE_RESTORE_MISSING", measurement.plan,
                "restoration.status", "RECEIPT_STATUS_OK", nullptr);
        }
        if (!compile_catalog_passed(measurement.profile)) {
            guards.compile_catalogs_passed = false;
            mismatch(guards, "COMPILE_GATE_FAILED", measurement.plan, "action_receipts.shader_inspection",
                "complete successful compile catalog", nullptr);
        }
        if (profile_case.value("status", std::string{}) != "passed") {
            mismatch(guards, "MEASUREMENT_INCOMPLETE", measurement.plan, "status", "passed",
                profile_case.value("status", std::string("missing")));
            continue;
        }
        const auto provenance = measurement.profile.find("provenance");
        if (provenance == measurement.profile.end() ||
            !detail::complete_result_provenance(*provenance)) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance", "complete strict-v2 ResultProvenance", provenance == measurement.profile.end()
                    ? Json(nullptr) : Json(*provenance));
        }
        if (profile_case.value("frames", std::uint64_t{}) != expected_frames.get<std::uint64_t>()) {
            mismatch(guards, "FRAME_COUNT_MISMATCH", measurement.plan, "frames", expected_frames,
                profile_case.value("frames", Json(nullptr)));
        }
        const auto config_hash = provenance != measurement.profile.end()
            ? provenance->value("config_sha256", std::string{}) : std::string{};
        const auto scene_hash = provenance != measurement.profile.end()
            ? provenance->value("scene_sha256", std::string{}) : std::string{};
        const auto source_hash = provenance != measurement.profile.end()
            ? provenance->value("source_snapshot_sha256", std::string{}) : std::string{};
        if (config_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.config_sha256", "non-empty", nullptr);
        } else if (guards.config_hash.empty()) {
            guards.config_hash = config_hash;
        } else if (config_hash != guards.config_hash) {
            mismatch(guards, "CONFIG_HASH_MISMATCH", measurement.plan,
                "provenance.config_sha256", guards.config_hash, config_hash);
        }
        if (scene_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.scene_sha256", "non-empty", nullptr);
        } else if (guards.scene_hash.empty()) {
            guards.scene_hash = scene_hash;
        } else if (scene_hash != guards.scene_hash) {
            mismatch(guards, "SCENE_HASH_MISMATCH", measurement.plan,
                "provenance.scene_sha256", guards.scene_hash, scene_hash);
        }
        const auto physical = measurement.plan.physical_source;
        if (source_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.source_snapshot_sha256", "non-empty", nullptr);
        } else if (!guards.source_hashes.contains(physical)) {
            guards.source_hashes.emplace(physical, source_hash);
        } else if (source_hash != guards.source_hashes.at(physical)) {
            mismatch(guards, "SOURCE_IDENTITY_MISMATCH", measurement.plan,
                "provenance.source_snapshot_sha256", guards.source_hashes.at(physical), source_hash);
        }

        const auto aggregate = identities(measurement, "aggregate");
        const auto programs = identities(measurement, "program");
        if (!reference_metrics) {
            guards.aggregate_identities = aggregate;
            guards.program_identities = programs;
            for (const auto& [key, metric] : measurement.metrics) guards.identities[key] = metric.identity;
            reference_metrics = true;
        } else {
            if (aggregate != guards.aggregate_identities) {
                mismatch(guards, "METRIC_IDENTITY_MISMATCH", measurement.plan,
                    "metrics.aggregate", Json(guards.aggregate_identities), Json(aggregate));
            }
            if (programs != guards.program_identities) {
                mismatch(guards, "PROGRAM_IDENTITY_MISMATCH", measurement.plan,
                    "metrics.program", Json(guards.program_identities), Json(programs));
            }
        }
    }
    if (!reference_metrics || (guards.aggregate_identities.empty() && guards.program_identities.empty())) {
        guards.mismatches.push_back({{"code", "NO_COMPARABLE_METRICS"},
            {"field", "metrics"}, {"expected", "at least one selected timing"}, {"actual", nullptr}});
    }
    Json selected = Json::object();
    for (const auto& spec : arguments.at("metrics")) {
        const auto metric_id = spec.at("metric_id").get<std::string>();
        std::vector<std::string> matches;
        for (const auto& [key, identity] : guards.identities.items()) {
            if (identity.value("metric", std::string{}) == metric_id) matches.push_back(key);
        }
        if (matches.size() != 1) {
            guards.mismatches.push_back({{"code", "TYPED_METRIC_IDENTITY_NOT_UNIQUE"},
                {"field", "metrics." + metric_id}, {"expected", "exactly one timing identity"},
                {"actual", matches.size()}});
            continue;
        }
        selected[matches.front()] = guards.identities.at(matches.front());
        guards.metric_specs.emplace(matches.front(), spec);
    }
    guards.identities = std::move(selected);
}

double median(std::vector<double> values) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const auto middle = values.size() / 2;
    return values.size() % 2 == 0 ? (values[middle - 1] + values[middle]) / 2.0 : values[middle];
}

double quantile(std::vector<double> values, double probability) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const auto position = probability * static_cast<double>(values.size() - 1);
    const auto lower = static_cast<std::size_t>(std::floor(position));
    const auto upper = static_cast<std::size_t>(std::ceil(position));
    if (lower == upper) return values[lower];
    const auto fraction = position - static_cast<double>(lower);
    return values[lower] + (values[upper] - values[lower]) * fraction;
}


const Measurement& find_measurement(
    const std::vector<Measurement>& measurements, std::string_view phase, std::size_t round, std::size_t slot) {
    const auto found = std::ranges::find_if(measurements, [&](const Measurement& measurement) {
        return measurement.plan.phase == phase && measurement.plan.round == round && measurement.plan.slot == slot;
    });
    if (found == measurements.end()) throw std::logic_error("paired benchmark plan lost a measurement");
    return *found;
}

std::vector<double> samples_for(const std::vector<Measurement>& measurements, std::string_view phase,
    std::size_t round, std::string_view variant, std::string_view identity, const bool p95 = false) {
    std::vector<double> result;
    for (const auto& measurement : measurements) {
        if (measurement.plan.phase != phase || measurement.plan.round != round ||
            measurement.plan.variant != variant) continue;
        const auto& metric = measurement.metrics.at(std::string(identity));
        result.push_back(p95 ? metric.p95_ns : metric.p50_ns);
    }
    return result;
}

Json round_samples(const std::vector<Measurement>& measurements, const GuardState& guards,
    std::string_view phase, std::size_t count, std::string_view first, std::string_view second) {
    Json result = Json::array();
    for (std::size_t round = 1; round <= count; ++round) {
        Json order = Json::array();
        Json case_ids = Json::array();
        for (std::size_t slot = 1; slot <= 4; ++slot) {
            const auto& measurement = find_measurement(measurements, phase, round, slot);
            order.push_back(measurement.plan.variant);
            case_ids.push_back(measurement.plan.case_id);
        }
        Json metrics = Json::array();
        for (const auto& [key, identity] : guards.identities.items()) {
            auto first_samples = samples_for(measurements, phase, round, first, key);
            auto second_samples = samples_for(measurements, phase, round, second, key);
            auto first_p95 = samples_for(measurements, phase, round, first, key, true);
            auto second_p95 = samples_for(measurements, phase, round, second, key, true);
            const auto first_median = median(first_samples);
            const auto second_median = median(second_samples);
            Json metric{{"identity", identity},
                        {std::string(first) + "_p50_samples_ns", first_samples},
                        {std::string(second) + "_p50_samples_ns", second_samples},
                        {std::string(first) + "_p95_samples_ns", first_p95},
                        {std::string(second) + "_p95_samples_ns", second_p95},
                        {std::string(first) + "_p50_ns", first_median},
                        {std::string(second) + "_p50_ns", second_median},
                        {std::string(first) + "_p95_ns", median(first_p95)},
                        {std::string(second) + "_p95_ns", median(second_p95)},
                        {"delta_ns", second_median - first_median}};
            metric["delta_percent"] = first_median == 0.0 ? Json(nullptr) :
                Json((second_median - first_median) / first_median * 100.0);
            metrics.push_back(std::move(metric));
        }
        result.push_back({{"round", round}, {"order", std::move(order)},
            {"case_ids", std::move(case_ids)}, {"metrics", std::move(metrics)}});
    }
    return result;
}

const Json& round_metric(const Json& rounds, std::size_t round, const Json& identity) {
    for (const auto& metric : rounds.at(round - 1).at("metrics")) {
        if (metric.at("identity") == identity) return metric;
    }
    throw std::logic_error("paired benchmark round lost a metric identity");
}

Json comparison_table(const std::vector<Measurement>& measurements, const GuardState& guards,
    const Json& comparison_rounds, const Json& control_rounds) {
    Json table = Json::array();
    for (const auto& [key, identity] : guards.identities.items()) {
        std::vector<double> baseline_p50;
        std::vector<double> candidate_p50;
        std::vector<double> baseline_p95;
        std::vector<double> candidate_p95;
        for (const auto& measurement : measurements) {
            if (measurement.plan.phase != "comparison") continue;
            const auto& value = measurement.metrics.at(key);
            if (measurement.plan.variant == "baseline") {
                baseline_p50.push_back(value.p50_ns);
                baseline_p95.push_back(value.p95_ns);
            } else {
                candidate_p50.push_back(value.p50_ns);
                candidate_p95.push_back(value.p95_ns);
            }
        }
        std::vector<double> paired_deltas;
        for (std::size_t round = 1; round <= comparison_rounds.size(); ++round) {
            const auto& metric = round_metric(comparison_rounds, round, identity);
            paired_deltas.push_back(metric.at("delta_ns").get<double>());
        }
        std::vector<double> noise_deltas;
        for (std::size_t round = 1; round <= control_rounds.size(); ++round) {
            noise_deltas.push_back(round_metric(control_rounds, round, identity).at("delta_ns").get<double>());
        }
        std::vector<double> first_order_deltas;
        std::vector<double> second_order_deltas;
        for (std::size_t round = 1; round <= comparison_rounds.size(); ++round) {
            std::vector<const Measurement*> baseline;
            std::vector<const Measurement*> candidate;
            for (const auto& measurement : measurements) {
                if (measurement.plan.phase != "comparison" || measurement.plan.round != round) continue;
                (measurement.plan.variant == "baseline" ? baseline : candidate).push_back(&measurement);
            }
            first_order_deltas.push_back(candidate.at(0)->metrics.at(key).p50_ns - baseline.at(0)->metrics.at(key).p50_ns);
            second_order_deltas.push_back(candidate.at(1)->metrics.at(key).p50_ns - baseline.at(1)->metrics.at(key).p50_ns);
        }
        std::vector<double> control_levels;
        for (std::size_t round = 1; round <= control_rounds.size(); ++round) {
            std::vector<double> values;
            for (const auto& measurement : measurements) {
                if (measurement.plan.phase == "control" && measurement.plan.round == round) {
                    values.push_back(measurement.metrics.at(key).p50_ns);
                }
            }
            control_levels.push_back(median(std::move(values)));
        }

        const auto statistics = analyze_paired_metric(baseline_p50, candidate_p50, baseline_p95, candidate_p95,
            paired_deltas, noise_deltas, first_order_deltas, second_order_deltas, control_levels);
        Json interval = nullptr;
        if (statistics.confidence_interval_95_ns) {
            interval = {{"low_ns", statistics.confidence_interval_95_ns->first},
                {"high_ns", statistics.confidence_interval_95_ns->second},
                {"confidence", 0.95}, {"method", "paired_student_t"}};
        }
        const auto& spec = guards.metric_specs.at(key);
        const auto role = spec.at("role").get<std::string>();
        const auto regression_ratio = statistics.baseline_p50_ns == 0.0 ? 0.0 :
            (statistics.candidate_p50_ns - statistics.baseline_p50_ns) / statistics.baseline_p50_ns;
        const bool order_unstable = statistics.order_effect_ns > statistics.noise_floor_ns;
        const bool statistically_stable = statistics.clears_noise_floor && statistics.confidence_excludes_zero &&
            statistics.outlier_rounds.empty() && !order_unstable && !statistics.direction_reversed &&
            !statistics.thermal_or_temporal_drift;
        const bool statistically_unstable = !statistics.outlier_rounds.empty() || order_unstable ||
            statistics.direction_reversed || statistics.thermal_or_temporal_drift;
        const bool stable_improvement = statistically_stable && statistics.paired_delta_ns < 0.0;
        const bool stable_regression = statistically_stable && statistics.paired_delta_ns > 0.0;
        const auto maximum_regression = spec.value("max_regression_ratio", 0.0);
        const bool guardrail_regression = role != "target" && stable_regression &&
            regression_ratio > maximum_regression;
        const auto decision = role == "target"
            ? (stable_improvement ? "ACCEPTED" : stable_regression ? "REGRESSION" : "INCONCLUSIVE")
            : (statistically_unstable ? "INCONCLUSIVE" :
                guardrail_regression ? "REGRESSION" : "GUARDRAIL_PASSED");
        const auto direction = !statistics.clears_noise_floor ? "unchanged" :
            (statistics.paired_delta_ns < 0.0 ? "improved" : "regressed");
        Json row{{"metric_id", spec.at("metric_id")}, {"role", role}, {"identity", identity},
                 {"baseline_p50_ns", statistics.baseline_p50_ns},
                 {"candidate_p50_ns", statistics.candidate_p50_ns},
                 {"baseline_p95_ns", statistics.baseline_p95_ns},
                 {"candidate_p95_ns", statistics.candidate_p95_ns},
                 {"paired_delta_ns", statistics.paired_delta_ns},
                 {"paired_delta_mean_ns", statistics.paired_delta_mean_ns},
                 {"paired_delta_variance_ns2", statistics.paired_delta_variance_ns2},
                 {"regression_ratio", regression_ratio},
                 {"max_regression_ratio", role == "target" ? Json(nullptr) : Json(maximum_regression)},
                 {"confidence_interval_95", std::move(interval)},
                 {"control_paired_deltas_ns", noise_deltas},
                 {"noise_floor_ns", statistics.noise_floor_ns},
                 {"order_effect_ns", statistics.order_effect_ns},
                 {"direction_reversed", statistics.direction_reversed},
                 {"thermal_or_temporal_drift", statistics.thermal_or_temporal_drift},
                 {"clears_noise_floor", statistics.clears_noise_floor},
                 {"confidence_excludes_zero", statistics.confidence_excludes_zero},
                 {"outlier_rounds", statistics.outlier_rounds},
                 {"guardrail_passed", role == "target" ? stable_improvement :
                    !statistically_unstable && !guardrail_regression},
                 {"decision", decision},
                 {"direction", direction}};
        table.push_back(std::move(row));
    }
    return table;
}

std::string overall_verdict(const Json& table) {
    bool target = false;
    bool inconclusive = table.empty();
    for (const auto& row : table) {
        const auto decision = row.at("decision").get<std::string>();
        if (decision == "REGRESSION") return "REGRESSION";
        if (row.at("role") == "target") {
            target = true;
            if (decision != "ACCEPTED") inconclusive = true;
        } else if (decision == "INCONCLUSIVE") {
            inconclusive = true;
        }
    }
    return !target || inconclusive ? "INCONCLUSIVE" : "ACCEPTED";
}

Json compact_execution(const Measurement& measurement) {
    const auto& profile_case = measurement.profile_case;
    return {{"case_id", measurement.plan.case_id},
            {"phase", measurement.plan.phase},
            {"round", measurement.plan.round},
            {"slot", measurement.plan.slot},
            {"variant", measurement.plan.variant},
            {"physical_source", measurement.plan.physical_source},
            {"status", profile_case.value("status", std::string("failed"))},
            {"error", profile_case.value("error", Json(nullptr))},
            {"attempt_count", profile_case.value("attempt_count", std::size_t{1})}};
}

Json guard_receipt(const GuardState& guards, const Json& arguments) {
    Json source_hashes = Json::object();
    for (const auto& [name, hash] : guards.source_hashes) source_hashes[name] = hash;
    return {{"passed", guards.mismatches.empty()},
            {"expected_frames", arguments.at("frames")},
            {"config_sha256", guards.config_hash.empty() ? Json(nullptr) : Json(guards.config_hash)},
            {"scene_sha256", guards.scene_hash.empty() ? Json(nullptr) : Json(guards.scene_hash)},
            {"source_identity_sha256", std::move(source_hashes)},
            {"aggregate_identity_count", guards.aggregate_identities.size()},
            {"program_identity_count", guards.program_identities.size()},
            {"compile_catalogs_passed", guards.compile_catalogs_passed},
            {"runtime_state_restored", guards.runtime_state_restored},
            {"mismatches", guards.mismatches}};
}

} // namespace

Json visual_comparison_guards(const Json& result, bool require_heatmap) {
    Json mismatches = Json::array();
    const auto add_mismatch = [&mismatches](std::string code, std::string message) {
        mismatches.push_back({{"code", std::move(code)}, {"message", std::move(message)}});
    };

    const auto success = result.find("success");
    const bool runtime_success = result.value("capture_job_success",
        success != result.end() && success->is_boolean() && success->get<bool>());
    if (!runtime_success) {
        add_mismatch("VISUAL_JOB_NOT_SUCCESSFUL",
            "The visual comparison job did not return a successful runtime completion receipt.");
    }

    const auto comparison = result.find("comparison");
    const bool comparison_receipt = comparison != result.end() && comparison->is_object() &&
        comparison->contains("passed") && (comparison->at("passed").is_boolean() ||
            comparison->value("verdict", std::string{}) == "not_evaluated") &&
        comparison->contains("verdict") && comparison->at("verdict").is_string() &&
        !comparison->at("verdict").get_ref<const std::string&>().empty();
    if (!comparison_receipt) {
        add_mismatch("THRESHOLD_VERDICT_MISSING",
            "The visual comparison did not return a typed threshold verdict.");
    }

    const auto frame_ids = result.find("frame_ids");
    bool two_frames = frame_ids != result.end() && frame_ids->is_array() && frame_ids->size() >= 2 && frame_ids->size() % 2 == 0;
    if (two_frames) for (std::size_t index = 0; index < frame_ids->size() / 2; ++index) {
        const auto& a = frame_ids->at(index);
        const auto& b = frame_ids->at(index + frame_ids->size() / 2);
        two_frames = two_frames && a.is_number_integer() && b.is_number_integer() && a != b;
    }
    if (!two_frames) {
        add_mismatch("TWO_DISTINCT_FRAME_RECEIPTS_REQUIRED",
            "The visual comparison must identify distinct baseline and candidate capture frames.");
    }

    std::vector<const Json*> resets;
    if (const auto receipts = result.find("action_receipts"); receipts != result.end() && receipts->is_array()) {
        for (const auto& receipt : *receipts) {
            if (receipt.is_object() &&
                receipt.value("kind", std::string{}) == "ACTION_KIND_RESET_TEMPORAL_STATE") {
                resets.push_back(&receipt);
            }
        }
    }
    bool reset_receipts = resets.size() == 2;
    if (reset_receipts) {
        std::uint64_t previous_index = 0;
        for (std::size_t index = 0; index < resets.size(); ++index) {
            const auto detail = resets[index]->find("reset_temporal");
            const auto action_index = resets[index]->value("action_index", std::uint64_t{});
            const bool ordered = index == 0 || action_index > previous_index;
            const bool successful = resets[index]->value("status", std::string{}) == "RECEIPT_STATUS_OK" &&
                detail != resets[index]->end() && detail->is_object() &&
                detail->value("completed_at_unix_ms", std::uint64_t{}) != 0;
            reset_receipts = reset_receipts && ordered && successful;
            previous_index = action_index;
        }
    }
    if (!reset_receipts) {
        add_mismatch("TWO_ORDERED_TEMPORAL_RESET_RECEIPTS_REQUIRED",
            "The visual comparison must return two ordered successful typed temporal-reset receipts.");
    }

    bool metrics_artifact = false;
    const auto artifacts = result.find("artifacts");
    if (artifacts != result.end() && artifacts->is_array()) {
        metrics_artifact = std::ranges::any_of(*artifacts, [](const Json& artifact) {
            return artifact.is_object() &&
                artifact.value("kind", std::string{}) == "ARTIFACT_KIND_BENCHMARK_METRICS" &&
                artifact.value("format", std::string{}) == "ARTIFACT_FORMAT_JSON" &&
                artifact.value("relative_path", std::string{}).ends_with("diff.json");
        });
    }
    if (!metrics_artifact) {
        add_mismatch("DIFF_METRICS_ARTIFACT_MISSING",
            "The visual comparison did not return its diff.json metrics artifact.");
    }

    const bool heatmap_artifact = artifacts != result.end() && artifacts->is_array() &&
        std::ranges::any_of(*artifacts, [](const Json& artifact) {
            return artifact.is_object() && artifact.value("kind", std::string{}) == "ARTIFACT_KIND_HEATMAP" &&
                artifact.value("format", std::string{}) == "ARTIFACT_FORMAT_PNG" &&
                artifact.value("relative_path", std::string{}).ends_with(".png");
        });
    if (require_heatmap && !heatmap_artifact) {
        add_mismatch("DIFF_HEATMAP_ARTIFACT_MISSING",
            "The visual comparison did not return its PNG difference heatmap artifact.");
    }

    std::vector<const Json*> loads;
    for (const auto* field : {"prelude_receipts", "action_receipts"}) {
        const auto receipts = result.find(field);
        if (receipts != result.end() && receipts->is_array()) {
            for (const auto& receipt : *receipts) {
                if (receipt.is_object() &&
                    receipt.value("kind", std::string{}) == "ACTION_KIND_LOAD_SHADER") {
                    loads.push_back(&receipt);
                }
            }
        }
    }
    bool load_receipts = loads.size() == 2;
    if (!load_receipts) {
        add_mismatch("TWO_SHADER_LOAD_RECEIPTS_REQUIRED",
            "The visual comparison must return exactly two shader load receipts.");
    }

    Json config_hashes = Json::array();
    Json scene_hashes = Json::array();
    Json source_hashes = Json::array();
    std::array<std::string, 2> config_values;
    std::array<std::string, 2> scene_values;
    std::array<std::string, 2> source_values;
    if (loads.size() == 2) {
        for (std::size_t index = 0; index < loads.size(); ++index) {
            const auto mutation = loads[index]->find("runtime_mutation");
            const bool load_success = loads[index]->value("status", std::string{}) == "RECEIPT_STATUS_OK" &&
                mutation != loads[index]->end() && mutation->is_object();
            if (!load_success) {
                load_receipts = false;
                add_mismatch("SHADER_LOAD_RECEIPT_FAILED",
                    "A baseline or candidate shader load receipt was unsuccessful.");
            }
            config_values[index] = text_at(*loads[index],
                {"runtime_mutation", "effective_settings", "settings_sha256"});
            scene_values[index] = text_at(*loads[index], {"runtime_mutation", "scene_sha256"});
            source_values[index] = text_at(*loads[index], {"runtime_mutation", "source_sha256"});
            config_hashes.push_back(config_values[index].empty() ? Json(nullptr) : Json(config_values[index]));
            scene_hashes.push_back(scene_values[index].empty() ? Json(nullptr) : Json(scene_values[index]));
            source_hashes.push_back(source_values[index].empty() ? Json(nullptr) : Json(source_values[index]));
            if (config_values[index].empty()) {
                load_receipts = false;
                add_mismatch("CONFIG_HASH_MISSING",
                    "A shader load receipt omitted its effective config hash.");
            }
            if (scene_values[index].empty()) {
                load_receipts = false;
                add_mismatch("SCENE_HASH_MISSING",
                    "A shader load receipt omitted its effective scene-context hash.");
            }
            if (source_values[index].empty()) {
                load_receipts = false;
                add_mismatch("SOURCE_HASH_MISSING",
                    "A shader load receipt omitted its prepared source-identity hash.");
            }
        }
    }
    const bool config_match = loads.size() == 2 && !config_values[0].empty() &&
        config_values[0] == config_values[1];
    const bool scene_match = loads.size() == 2 && !scene_values[0].empty() &&
        scene_values[0] == scene_values[1];
    if (loads.size() == 2 && !config_values[0].empty() && !config_values[1].empty() && !config_match) {
        add_mismatch("VISUAL_CONFIG_HASH_MISMATCH",
            "Baseline and candidate captures used different effective shader configurations.");
    }
    if (loads.size() == 2 && !scene_values[0].empty() && !scene_values[1].empty() && !scene_match) {
        add_mismatch("VISUAL_SCENE_HASH_MISMATCH",
            "Baseline and candidate captures used different effective scene contexts.");
    }

    return {{"passed", mismatches.empty()},
            {"runtime_success", runtime_success},
            {"comparison_receipt", comparison_receipt},
            {"two_distinct_frames", two_frames},
            {"two_ordered_temporal_reset_receipts", reset_receipts},
            {"diff_metrics_artifact", metrics_artifact},
            {"diff_heatmap_required", require_heatmap},
            {"diff_heatmap_artifact", heatmap_artifact},
            {"two_successful_load_receipts", load_receipts},
            {"config_hash_match", config_match},
            {"scene_hash_match", scene_match},
            {"config_hashes", std::move(config_hashes)},
            {"scene_hashes", std::move(scene_hashes)},
            {"source_hashes", std::move(source_hashes)},
            {"mismatches", std::move(mismatches)}};
}

ToolOutcome run_paired_benchmark(const Json& arguments, std::string_view workflow_id,
    std::size_t default_warmup_frames, const PairedProfileExecutor& execute_profile,
    const PairedVisualExecutor& execute_visual) {
    const auto measurements_plan = plan(arguments);
    std::vector<Measurement> measurements;
    measurements.reserve(measurements_plan.size());
    Json artifacts = Json::array();
    bool halted_without_restore = false;
    for (const auto& item : measurements_plan) {
        const auto nested_arguments = profile_arguments(arguments, item, workflow_id, default_warmup_frames);
        auto outcome = execute_profile(nested_arguments);
        Measurement measurement{.plan = item};
        if (const auto* profile = std::get_if<Json>(&outcome)) {
            measurement.profile = *profile;
            measurement.profile_case = first_case(*profile, item, arguments, default_warmup_frames);
            const auto profile_artifacts = profile->value("artifacts", Json::array());
            for (const auto& artifact : profile_artifacts) {
                auto attributed = artifact;
                attributed["case_id"] = item.case_id;
                artifacts.push_back(std::move(attributed));
            }
        } else {
            const auto error = failure_json(std::get<ToolFailure>(outcome));
            measurement.profile = {{"success", false}, {"status", "completed_with_failures"},
                {"cases", Json::array({failed_case(item, arguments, error, default_warmup_frames)})}};
            measurement.profile_case = measurement.profile.at("cases").front();
        }
        measurement.metrics = metric_values(measurement.profile_case);
        measurements.push_back(std::move(measurement));
        if (!restored(measurements.back().profile)) {
            halted_without_restore = true;
            break;
        }
    }

    GuardState guards;
    validate_guards(measurements, arguments, guards);
    if (halted_without_restore) {
        guards.mismatches.push_back({{"code", "WORKFLOW_HALTED_BEFORE_RESTORATION"},
            {"field", "measurements"}, {"expected", measurements_plan.size()},
            {"actual", measurements.size()}});
    }
    const bool valid = guards.mismatches.empty();
    Json comparison_rounds = Json::array();
    Json control_rounds = Json::array();
    Json table = Json::array();
    if (valid) {
        comparison_rounds = round_samples(measurements, guards, "comparison",
            arguments.value("rounds", std::size_t{3}), "baseline", "candidate");
        control_rounds = round_samples(measurements, guards, "control",
            arguments.value("control_rounds", arguments.value("rounds", std::size_t{3})), "a", "b");
        table = comparison_table(measurements, guards, comparison_rounds, control_rounds);
    }

    Json executions = Json::array();
    Json profiles = Json::array();
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    for (const auto& measurement : measurements) {
        executions.push_back(compact_execution(measurement));
        const auto measurement_status = measurement.profile_case.value("status", std::string{});
        if (measurement_status == "passed") ++passed;
        else if (measurement_status == "failed") ++failed;
        else ++incomplete;
        if (arguments.value("result_detail", std::string("metrics")) == "full") {
            profiles.push_back(measurement.profile);
        }
    }
    const auto performance_verdict = valid ? overall_verdict(table) : "INCONCLUSIVE";
    Json visual{{"requested", false}, {"success", false}, {"status", "missing"},
                {"verdict", "inconclusive"}, {"comparison", nullptr}, {"artifacts", Json::array()}};
    if (arguments.contains("visual")) {
        if (!execute_visual) {
            visual = {{"requested", true}, {"success", false}, {"status", "failed"},
                      {"verdict", "inconclusive"},
                      {"error", {{"success", false}, {"error_code", "VISUAL_EXECUTOR_UNAVAILABLE"},
                          {"message", "Visual comparison execution is unavailable."}, {"retryable", false}}},
                      {"comparison", nullptr}, {"artifacts", Json::array()}};
        } else if (halted_without_restore) {
            visual = {{"requested", true}, {"success", false}, {"status", "skipped"},
                      {"verdict", "inconclusive"},
                      {"error", {{"success", false}, {"error_code", "RUNTIME_STATE_NOT_RESTORED"},
                          {"message", "Visual comparison was skipped because benchmark state was not restored."},
                          {"retryable", true}}},
                      {"comparison", nullptr}, {"artifacts", Json::array()}};
        } else {
            visual = visual_receipt(execute_visual(visual_arguments(arguments, default_warmup_frames)));
            for (const auto& artifact : visual.at("artifacts")) {
                auto attributed = artifact;
                attributed["benchmark_phase"] = "visual";
                artifacts.push_back(std::move(attributed));
            }
        }
    }
    const bool performance_success = failed == 0 && incomplete == 0 && valid;
    const bool visual_success = visual.at("success").get<bool>();
    const bool visual_invalid = visual.value("status", std::string{}) == "invalid";
    const auto combined_verdict = !performance_success || !visual_success
        ? std::string("GATE_FAILED") : performance_verdict;
    const auto status = halted_without_restore || incomplete != 0 ? "incomplete" :
        (!valid || visual_invalid ? "invalid_comparison" :
            (combined_verdict == "ACCEPTED" || combined_verdict == "INCONCLUSIVE"
                ? "completed" : "completed_with_failures"));
    double measured_noise_floor = 0.0;
    double order_effect = 0.0;
    bool direction_reversed = false;
    bool thermal_or_temporal_drift = false;
    Json violations = Json::array();
    for (const auto& row : table) {
        measured_noise_floor = std::max(measured_noise_floor, row.at("noise_floor_ns").get<double>());
        order_effect = std::max(order_effect, row.at("order_effect_ns").get<double>());
        direction_reversed = direction_reversed || row.at("direction_reversed").get<bool>();
        thermal_or_temporal_drift = thermal_or_temporal_drift ||
            row.at("thermal_or_temporal_drift").get<bool>();
        if (row.at("decision") == "REGRESSION") {
            violations.push_back({{"code", "METRIC_REGRESSION"}, {"metric_id", row.at("metric_id")},
                {"role", row.at("role")}});
        }
    }
    if (!valid) violations.push_back({{"code", "BENCHMARK_GUARD_FAILED"}});
    if (!visual_success) violations.push_back({{"code", "VISUAL_GATE_FAILED"}});
    if (performance_verdict == "INCONCLUSIVE") violations.push_back({{"code", "STATISTICAL_INCONCLUSIVE"}});
    if (direction_reversed) violations.push_back({{"code", "DIRECTION_REVERSED"}});
    if (thermal_or_temporal_drift) violations.push_back({{"code", "THERMAL_OR_TEMPORAL_DRIFT"}});
    const bool provenance_passed = std::ranges::none_of(guards.mismatches, [](const Json& value) {
        const auto code = value.value("code", std::string{});
        return code == "PROVENANCE_GUARD_MISSING" || code == "CONFIG_HASH_MISMATCH" ||
            code == "SCENE_HASH_MISMATCH" || code == "SOURCE_IDENTITY_MISMATCH" ||
            code == "SOURCE_ROLE_MISMATCH";
    });
    Json acceptance_gates{{"compile", guards.compile_catalogs_passed},
        {"provenance", provenance_passed},
        {"restoration", guards.runtime_state_restored},
        {"visual", visual_success},
        {"statistical", performance_verdict == "ACCEPTED"}};
    Json result{{"success", combined_verdict == "ACCEPTED"},
                {"kind", "benchmark_ab"},
                {"status", status},
                {"verdict", combined_verdict},
                {"performance_verdict", performance_verdict},
                {"visual_verdict", visual.at("verdict")},
                {"visual", std::move(visual)},
                {"result_detail", arguments.value("result_detail", std::string("metrics"))},
                {"gpu_timing_unit", "ns"},
                {"metrics", arguments.at("metrics")},
                {"order", arguments.value("order", std::string("abba"))},
                {"random_seed", arguments.value("random_seed", std::uint32_t{0})},
                {"round_count", arguments.value("rounds", std::size_t{3})},
                {"control_round_count", arguments.value(
                    "control_rounds", arguments.value("rounds", std::size_t{3}))},
                {"requested_measurements", measurements_plan.size()},
                {"completed_measurements", passed + failed},
                {"passed_measurements", passed},
                {"failed_measurements", failed},
                {"incomplete_measurements", incomplete},
                {"remaining_measurements", measurements_plan.size() - measurements.size()},
                {"same_commit_control_source", "baseline"},
                {"measured_noise_floor_ns", measured_noise_floor},
                {"order_effect_ns", order_effect},
                {"direction_reversed", direction_reversed},
                {"thermal_or_temporal_drift", thermal_or_temporal_drift},
                {"violations", std::move(violations)},
                {"acceptance_gates", std::move(acceptance_gates)},
                {"guards", guard_receipt(guards, arguments)},
                {"executions", std::move(executions)},
                {"round_samples", std::move(comparison_rounds)},
                {"control_round_samples", std::move(control_rounds)},
                {"comparison_table", std::move(table)},
                {"artifacts", std::move(artifacts)}};
    if (!profiles.empty()) result["profiles"] = std::move(profiles);
    return result;
}

std::vector<PairedBenchmarkStep> paired_benchmark_plan(const Json& arguments) {
	std::vector<PairedBenchmarkStep> result;
	for (const auto& item : plan(arguments)) {
		result.push_back({item.phase, item.round, item.slot, item.variant,
			item.physical_source, item.case_id});
	}
	return result;
}

Json paired_benchmark_profile_arguments(const Json& arguments,
	const PairedBenchmarkStep& step, std::string_view workflow_id,
	const std::size_t default_warmup_frames) {
	return profile_arguments(arguments,
		{step.phase, step.round, step.slot, step.variant, step.physical_source, step.case_id},
		workflow_id, default_warmup_frames);
}

Json paired_benchmark_visual_arguments(
	const Json& arguments, const std::size_t default_warmup_frames) {
	return visual_arguments(arguments, default_warmup_frames);
}

} // namespace vibris::mcp
