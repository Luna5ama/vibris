#include "synchronous_job_runner.hpp"

#include "result_mapper.hpp"

#include "config_document.hpp"
#include "job_protocol.hpp"
#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <filesystem>
#include <fstream>
#include <future>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v2;

struct CompletionState final {
    std::mutex mutex;
    std::condition_variable ready;
    grpc::Status status;
    std::optional<proto::ServerMessage> terminal;
    bool done = false;
};

ToolFailure transport_failure(const grpc::Status& status) {
    if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
        return {"SERVER_RESTARTED", "The accepted Vibris job was lost after reconnect.", true};
    }
    auto message = status.error_message();
    if (message.empty()) message = "The local Vibris server disconnected before the job completed.";
    if (message.size() > 512) message.resize(512);
    return {"SERVER_OFFLINE", std::move(message), true};
}

ToolFailure request_failure(ToolFailure failure, std::string_view request_id, const bool request_accepted) {
    failure.details["request_id"] = request_id;
    failure.details["request_accepted"] = request_accepted;
    if (request_accepted) failure.details["resume_required"] = true;
    return failure;
}

constexpr auto job_watchdog_interval = std::chrono::seconds(5);

std::string progress_stage(const proto::JobStage stage) {
    switch (stage) {
        case proto::JOB_STAGE_VALIDATING:
        case proto::JOB_STAGE_QUEUED:
        case proto::JOB_STAGE_ACTIVATING_SOURCE:
        case proto::JOB_STAGE_LOADING_WORLD:
        case proto::JOB_STAGE_APPLYING_CONTEXT:
        case proto::JOB_STAGE_COMPILING:
            return "loading";
        case proto::JOB_STAGE_RESETTING_TEMPORAL_STATE:
        case proto::JOB_STAGE_WARMING_UP:
            return "warming";
        case proto::JOB_STAGE_SAMPLING:
        case proto::JOB_STAGE_CAPTURING:
            return "sampling";
        case proto::JOB_STAGE_WRITING_ARTIFACTS:
        case proto::JOB_STAGE_FINALIZING:
            return "checkpointing";
        case proto::JOB_STAGE_COMPARING:
            return "sampling";
        case proto::JOB_STAGE_UNSPECIFIED:
            return "loading";
    }
    return "loading";
}

void report_progress(const SynchronousJobProgressSink& sink, std::string request_id,
    std::string stage, const bool accepted) {
    if (sink) sink({std::move(request_id), std::move(stage), accepted});
}

const Json& strict_job_result(const Json& terminal) {
    if (!terminal.is_object() || !terminal.value("success", false)) {
        throw std::invalid_argument("strict v2 normalization requires a successful terminal envelope");
    }
    const auto found = terminal.find("result");
    if (found == terminal.end() || !found->is_object()) {
        throw std::invalid_argument("strict v2 terminal envelope is missing JobResult");
    }
    constexpr std::array removed_fields{
        "kind", "action_results", "manifest_path", "frame_ids", "benchmark_barriers",
    };
    for (const auto* field : removed_fields) {
        if (found->contains(field)) {
            throw std::invalid_argument(std::string("removed JobResult field is not accepted: ") + field);
        }
    }
    return *found;
}

const Json& strict_array(const Json& object, std::string_view field) {
    const auto found = object.find(field);
    if (found == object.end() || !found->is_array()) {
        throw std::invalid_argument("strict v2 result field is not an array: " + std::string(field));
    }
    return *found;
}

bool receipt_ok(const Json& receipt) {
    return receipt.is_object() && receipt.value("status", std::string{}) == "RECEIPT_STATUS_OK";
}

Json receipt_error(const Json& receipt) {
    const auto found = receipt.find("error");
    if (found != receipt.end() && found->is_object()) return *found;
    return {{"code", "ERROR_CODE_INTERNAL"}, {"message", "A typed action receipt failed without an error."},
            {"retryable", false}, {"details", Json::object()}};
}

std::vector<const Json*> ordered_receipts(const Json& result) {
    std::vector<const Json*> receipts;
    for (const auto* field : {"prelude_receipts", "action_receipts"}) {
        for (const auto& receipt : strict_array(result, field)) receipts.push_back(&receipt);
    }
    return receipts;
}

std::uint64_t wire_uint64(const Json& value) {
    if (value.is_number_unsigned()) return value.get<std::uint64_t>();
    if (value.is_number_integer()) {
        const auto signed_value = value.get<std::int64_t>();
        if (signed_value < 0) throw std::invalid_argument("negative uint64 protobuf JSON value");
        return static_cast<std::uint64_t>(signed_value);
    }
    throw std::invalid_argument("invalid native uint64 JSON value");
}

Json normalized_gpu_metrics(const Json& receipt) {
    if (!receipt.is_object()) throw std::invalid_argument("gpu_metrics receipt is not an object");
    Json metrics = Json::array();
    for (const auto& value : strict_array(receipt, "metrics")) {
        if (!value.is_object()) throw std::invalid_argument("GpuTimingMetric is not an object");
        Json samples = Json::array();
        for (const auto& sample : strict_array(value, "samples_ns")) samples.push_back(wire_uint64(sample));
        metrics.push_back({{"metric_id", value.value("metric_id", std::string{})},
            {"main_source", value.value("main_source", std::string{})},
            {"program_id", value.value("program_id", std::string{})},
            {"pass_id", value.value("pass_id", std::string{})},
            {"average_ns", wire_uint64(value.at("average_ns"))},
            {"p50_ns", wire_uint64(value.at("p50_ns"))},
            {"p95_ns", wire_uint64(value.at("p95_ns"))},
            {"samples_ns", std::move(samples)}});
    }
    return {{"timing_unit", receipt.value("timing_unit", std::string{})},
            {"missing", receipt.value("missing", Json::array())},
            {"sampled_frames", receipt.value("sampled_frames", std::uint32_t{})},
            {"metrics", std::move(metrics)}};
}

bool has_gpu_samples(const Json& metrics) {
    return metrics.is_object() && metrics.value("sampled_frames", std::uint32_t{}) > 0 &&
        metrics.contains("metrics") && metrics.at("metrics").is_array() && !metrics.at("metrics").empty();
}

bool restored(const Json& result) {
    const auto receipt = result.find("restoration");
    return receipt != result.end() && receipt->is_object() &&
        receipt->value("status", std::string{}) == "RECEIPT_STATUS_OK";
}

Json no_gpu_samples_error(std::string_view case_id, std::string_view reason) {
    return {{"success", false},
            {"error_code", "NO_GPU_SAMPLES"},
            {"message", "GPU metrics did not return aggregate or program timing samples."},
            {"retryable", true},
            {"details", {{"case_id", case_id}, {"reason", reason}}}};
}

Json incomplete_provenance_error(std::string_view case_id) {
    return {{"success", false},
            {"error_code", "INCOMPLETE_PROVENANCE"},
            {"message", "Benchmark provenance did not prove the complete measured state."},
            {"retryable", false},
            {"details", {{"case_id", case_id}}}};
}

Json incomplete_restoration_error(std::string_view case_id) {
    return {{"success", false},
            {"error_code", "RESTORATION_RECEIPT_FAILED"},
            {"message", "The benchmark case did not return a successful restoration receipt."},
            {"retryable", false},
            {"details", {{"case_id", case_id}}}};
}

struct ProfileCounts final {
    std::size_t requested = 0;
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    std::size_t with_metrics = 0;
};

Json profile_case(const Json& receipts, const Json& provenance, const Json& restoration,
    ProfileCounts& counts, const Json& arguments, std::size_t warmup_frames,
    std::string case_id, std::string source_id, std::string config_id, std::string_view detail,
    bool retain_receipts) {
    const bool include_metrics = detail != "summary";
    Json metrics = nullptr;
    Json error = nullptr;
    bool metrics_seen = false;
    for (const auto& receipt : receipts) {
        if (!receipt_ok(receipt) && error.is_null()) error = receipt_error(receipt);
        if (receipt.value("kind", std::string{}) == "ACTION_KIND_GET_GPU_METRICS") {
            metrics_seen = true;
            const auto found = receipt.find("gpu_metrics");
            if (found != receipt.end()) metrics = normalized_gpu_metrics(*found);
        }
    }

    ++counts.requested;
    const bool has_metrics = has_gpu_samples(metrics);
    const bool reported_missing = metrics.is_object() && !metrics.value("missing", Json::array()).empty();
    const bool has_provenance = detail::complete_result_provenance(provenance);
    const bool has_restoration = restoration.is_object() &&
        restoration.value("status", std::string{}) == "RECEIPT_STATUS_OK";
    if (has_metrics) ++counts.with_metrics;
    const bool failed = !error.is_null();
    const bool incomplete = !failed && ((!has_metrics && !reported_missing) || !has_provenance || !has_restoration);
    const char* status = "passed";
    if (failed) {
        ++counts.failed;
        status = "failed";
    } else if (incomplete) {
        ++counts.incomplete;
        status = "incomplete";
        error = !has_metrics ? no_gpu_samples_error(
            case_id, metrics_seen ? "empty_gpu_timings" : "missing_gpu_metrics_action") :
            (!has_provenance ? incomplete_provenance_error(case_id) : incomplete_restoration_error(case_id));
    } else {
        ++counts.passed;
    }

    Json item{{"case_id", std::move(case_id)},
              {"source_id", std::move(source_id)},
              {"config_id", std::move(config_id)},
              {"status", status},
              {"error", std::move(error)},
              {"frames", arguments.at("frames")},
              {"warmup_frames", warmup_frames},
              {"metrics", include_metrics && (has_metrics || reported_missing) ? std::move(metrics) : Json(nullptr)}};
    if (retain_receipts) item["action_receipts"] = receipts;
    return item;
}

Json profile_result(const Json& terminal, const Json& arguments, const JobContext& config, bool matrix) {
    return detail::normalize_profile_result(terminal, arguments, config.default_warmup_frames, matrix);
}

Json matrix_result(const Json& terminal, const Json& arguments) {
    return detail::normalize_matrix_result(terminal, arguments);
}

bool compile_program_succeeded(const Json& program) {
    return program.value("compile_state", std::string{}) == "COMPILE_STATE_SUCCEEDED" &&
        program.value("link_state", std::string{}) == "COMPILE_STATE_SUCCEEDED";
}

Json compile_validation_result(Json terminal, const Json& arguments) {
    const auto& raw = terminal.at("result");
    const auto& validation = raw.at("compile_validation");
    Json cases = Json::array();
    std::size_t passed = 0;
    std::size_t failed = 0;
    for (const auto& value : validation.value("cases", Json::array())) {
        const auto& catalog = value.at("catalog");
        const auto programs = catalog.value("programs", Json::array());
        const bool compiled = !programs.empty() &&
            std::ranges::all_of(programs, compile_program_succeeded);
        const auto& provenance = value.at("provenance");
        const bool provenance_complete = provenance.is_object() &&
            !provenance.value("workspace_id", std::string{}).empty() &&
            !provenance.value("source_snapshot_sha256", std::string{}).empty() &&
            !provenance.value("active_source_uuid", std::string{}).empty() &&
            !provenance.value("pass_mapping_sha256", std::string{}).empty();
        const bool ok = compiled && provenance_complete;
        ok ? ++passed : ++failed;
        Json item{{"case_id", value.at("case_id")},
                  {"source_id", arguments.value("__vibris_source_id", std::string("source"))},
                  {"config_id", arguments.value("__vibris_config_id", std::string("config"))},
                  {"status", ok ? "passed" : "failed"},
                  {"catalog", catalog},
                  {"added_diagnostics", value.value("added_diagnostics", Json::array())},
                  {"resolved_diagnostics", value.value("resolved_diagnostics", Json::array())},
                  {"unchanged_diagnostics", value.value("unchanged_diagnostics", Json::array())},
                  {"provenance", provenance}};
        if (!ok) item["error"] = {{"success", false},
            {"error_code", compiled ? "INCOMPLETE_PROVENANCE" : "SHADER_COMPILE_FAILED"},
            {"message", compiled ? "Compile validation provenance is incomplete."
                                  : "One or more intended shader programs did not compile and link."},
            {"retryable", false}};
        cases.push_back(std::move(item));
    }
    const auto restoration = raw.value("restoration", Json::object());
    const bool restored = restoration.value("status", std::string{}) == "RECEIPT_STATUS_OK";
    if (!restored) ++failed;
    return {{"success", failed == 0}, {"kind", "compile_validate"},
            {"status", failed == 0 ? "completed" : "completed_with_failures"},
            {"requested_cases", validation.value("cases", Json::array()).size()},
            {"completed_cases", validation.value("cases", Json::array()).size()},
            {"passed", passed}, {"failed", failed}, {"cases", std::move(cases)},
            {"restoration", restoration}, {"artifacts", raw.value("artifacts", Json::array())},
            {"job_id", terminal.value("job_id", std::string{})},
            {"request_id", terminal.value("request_id", std::string{})}};
}

struct ProfileCaseSpec final {
    std::string case_id;
    std::string source_id;
    std::string config_id;
    Json source = nullptr;
    Json config = nullptr;
};

struct ProfileCaseState final {
    ProfileCaseSpec spec;
    Json value = nullptr;
    Json attempts = Json::array();
    std::size_t run_attempts = 0;
};

const Json& named_value(const Json& values, std::string_view id, std::string_view kind) {
    const auto found = std::ranges::find_if(values, [id](const Json& value) {
        return value.at("id").get_ref<const std::string&>() == id;
    });
    if (found == values.end()) throw std::invalid_argument(std::string(kind) + " ID is not declared");
    return *found;
}

std::vector<ProfileCaseState> profile_cases(const Json& arguments, bool matrix) {
    std::vector<ProfileCaseState> result;
    if (!matrix) {
        ProfileCaseSpec spec{
            arguments.value("__vibris_case_id", std::string("source--config")),
            arguments.value("__vibris_source_id", std::string("source")),
            arguments.value("__vibris_config_id", std::string("config")),
        };
        if (arguments.contains("source")) spec.source = arguments.at("source");
        if (arguments.contains("config")) spec.config = arguments.at("config");
        result.push_back({std::move(spec)});
        return result;
    }
    for (const auto& source_axis : arguments.at("matrix").at("sources")) {
        const auto source_id = source_axis.get<std::string>();
        auto source = named_value(arguments.at("sources"), source_id, "source");
        source.erase("id");
        for (const auto& config_axis : arguments.at("matrix").at("configs")) {
            const auto config_id = config_axis.get<std::string>();
            const auto& named_config = named_value(arguments.at("configs"), config_id, "config");
            result.push_back({ProfileCaseSpec{
                source_id + "--" + config_id,
                source_id,
                config_id,
                source,
                named_config.contains("values") ? named_config.at("values") : Json(nullptr),
            }});
        }
    }
    return result;
}

Json failure_error(const ToolFailure& failure) {
    return { {"success", false},
             {"error_code", failure.code},
             {"message", failure.message},
             {"retryable", failure.retryable},
             {"details", failure.details} };
}

bool retryable_error(const Json& error) {
    if (!error.is_object()) return false;
    const auto code = error.value("error_code", std::string{});
    constexpr std::array retryable_codes{
        "NO_GPU_SAMPLES", "SERVER_OFFLINE", "SERVER_RESTARTED", "SERVER_NOT_READY",
        "QUEUE_FULL", "QUEUE_TIMEOUT", "EXECUTION_TIMEOUT", "WORLD_LOAD_FAILED",
        "SOURCE_ACTIVATION_FAILED", "INTERNAL_ERROR", "CAPTURE_FAILED",
    };
    if (std::ranges::find(retryable_codes, code) != retryable_codes.end()) return true;
    return error.value("retryable", false);
}

bool resume_required(const Json& error) {
    const auto details = error.find("details");
    return details != error.end() && details->is_object() && details->value("resume_required", false);
}

Json failed_profile_case(
    const ProfileCaseSpec& spec, const Json& arguments, std::size_t default_warmup, Json error) {
    return {{"case_id", spec.case_id},
            {"source_id", spec.source_id},
            {"config_id", spec.config_id},
            {"status", "failed"},
            {"error", std::move(error)},
            {"frames", arguments.at("frames")},
            {"warmup_frames", arguments.value("warmup_frames", default_warmup)},
            {"metrics", nullptr},
            {"provenance", nullptr}};
}

const Json* find_profile_case(const Json& result, std::string_view case_id) {
    const auto cases = result.find("cases");
    if (cases == result.end() || !cases->is_array()) return nullptr;
    const auto found = std::ranges::find_if(*cases, [case_id](const Json& value) {
        return value.at("case_id").get_ref<const std::string&>() == case_id;
    });
    return found == cases->end() ? nullptr : &*found;
}

Json missing_profile_case(const ProfileCaseSpec& spec, const Json& arguments, std::size_t default_warmup) {
    auto result = failed_profile_case(spec, arguments, default_warmup,
        no_gpu_samples_error(spec.case_id, "missing_case_result"));
    result["status"] = "incomplete";
    return result;
}

Json collect_artifacts(const Json& source, std::size_t attempt, const Json& case_ids, Json& artifacts) {
    Json ids = Json::array();
    const auto found = source.find("artifacts");
    if (found == source.end() || !found->is_array()) return ids;
    for (const auto& value : *found) {
        auto artifact = value;
        artifact["attempt"] = attempt;
        artifact["case_ids"] = case_ids;
        const auto id = artifact.value("artifact_id", artifact.value("file_name", std::string{}));
        if (!id.empty()) ids.push_back(id);
        artifacts.push_back(std::move(artifact));
    }
    return ids;
}

Json attempt_record(std::size_t attempt, const Json& profile_case, Json artifact_ids) {
    const auto& error = profile_case.at("error");
    return {{"attempt", attempt},
            {"status", profile_case.at("status")},
            {"retryable", retryable_error(error)},
            {"error", error},
            {"artifact_ids", std::move(artifact_ids)}};
}

Json job_attempt_record(std::size_t attempt, const Json& case_ids, std::string status, Json error,
    Json artifact_ids, const Json* terminal) {
    Json result{{"attempt", attempt},
                {"case_ids", case_ids},
                {"status", std::move(status)},
                {"error", std::move(error)},
                {"artifact_ids", std::move(artifact_ids)}};
    if (terminal != nullptr && terminal->contains("timings")) result["timings"] = terminal->at("timings");
    return result;
}

Json retry_arguments(const Json& arguments, const ProfileCaseState& state, std::size_t attempt) {
    Json result{{"recipe", "profile"}, {"frames", arguments.at("frames")}};
    constexpr std::array copied_fields{
        "preset_id", "warmup_frames", "result_detail", "metric_filter", "statistics", "converted_units",
        "result_csv",
    };
    for (const auto* field : copied_fields) {
        if (arguments.contains(field)) result[field] = arguments.at(field);
    }
    if (arguments.contains("__vibris_preset")) result["__vibris_preset"] = arguments.at("__vibris_preset");
    if (arguments.contains("__vibris_workflow_id")) {
        result["__vibris_workflow_id"] = arguments.at("__vibris_workflow_id");
    }
    if (!state.spec.source.is_null()) result["source"] = state.spec.source;
    if (!state.spec.config.is_null()) result["config"] = state.spec.config;
    result["__vibris_case_id"] = state.spec.case_id;
    result["__vibris_source_id"] = state.spec.source_id;
    result["__vibris_config_id"] = state.spec.config_id;
    result["__vibris_result_kind"] = arguments.value("__vibris_result_kind", arguments.at("recipe"));
    result["__vibris_attempt"] = attempt;
    result["__vibris_previous_attempts"] = state.attempts;
    return result;
}

bool case_has_metrics(const Json& profile_case) {
    if (profile_case.at("status") == "passed") return true;
    const auto metrics = profile_case.find("metrics");
    return metrics != profile_case.end() && has_gpu_samples(*metrics);
}

Json retry_profile_impl(
    const Json& arguments, bool matrix, std::size_t default_warmup, const detail::ProfileAttempt& submit) {
    auto states = profile_cases(arguments, matrix);
    if (!matrix && arguments.contains("__vibris_previous_attempts")) {
        states.front().attempts = arguments.at("__vibris_previous_attempts");
    }
    const auto max_retries = arguments.value("max_retries", std::size_t{2});
    Json artifacts = Json::array();
    Json job_attempts = Json::array();
    Json aggregate = Json::object();
    bool aggregate_initialized = false;

    Json initial_arguments = arguments;
    const auto initial_attempt = states.front().attempts.size() + 1;
    initial_arguments["__vibris_attempt"] = initial_attempt;
    const auto initial_outcome = submit(initial_arguments, matrix);
    Json all_case_ids = Json::array();
    for (const auto& state : states) all_case_ids.push_back(state.spec.case_id);
    if (const auto* result = std::get_if<Json>(&initial_outcome)) {
        aggregate = *result;
        aggregate_initialized = true;
        if (!matrix && result->contains("recovered_previous_attempts") &&
            states.front().attempts.size() < result->at("recovered_previous_attempts").size()) {
            states.front().attempts = result->at("recovered_previous_attempts");
        }
        const auto actual_attempt = result->value("recovered_attempt", initial_attempt);
        aggregate.erase("recovered_attempt");
        aggregate.erase("recovered_previous_attempts");
        const auto artifact_ids = collect_artifacts(*result, actual_attempt, all_case_ids, artifacts);
        job_attempts.push_back(job_attempt_record(
            actual_attempt, all_case_ids, result->at("status").get<std::string>(), nullptr, artifact_ids, result));
        for (auto& state : states) {
            const auto* profile_case = find_profile_case(*result, state.spec.case_id);
            state.value = profile_case == nullptr ?
                missing_profile_case(state.spec, arguments, default_warmup) : *profile_case;
            state.attempts.push_back(attempt_record(actual_attempt, state.value, artifact_ids));
            state.run_attempts = 1;
        }
    } else {
        const auto& failure = std::get<ToolFailure>(initial_outcome);
        const auto error = failure_error(failure);
        const auto artifact_ids = collect_artifacts(failure.details, initial_attempt, all_case_ids, artifacts);
        job_attempts.push_back(job_attempt_record(
            initial_attempt, all_case_ids, "failed", error, artifact_ids, nullptr));
        for (auto& state : states) {
            state.value = failed_profile_case(state.spec, arguments, default_warmup, error);
            state.attempts.push_back(attempt_record(initial_attempt, state.value, artifact_ids));
            state.run_attempts = 1;
        }
    }

    for (auto& state : states) {
        while (retryable_error(state.value.at("error")) && !resume_required(state.value.at("error")) &&
            state.run_attempts <= max_retries) {
            const auto attempt = state.attempts.size() + 1;
            const auto outcome = submit(retry_arguments(arguments, state, attempt), false);
            const Json case_ids = Json::array({state.spec.case_id});
            if (const auto* result = std::get_if<Json>(&outcome)) {
                if (!aggregate_initialized) {
                    aggregate = *result;
                    aggregate_initialized = true;
                }
                const auto artifact_ids = collect_artifacts(*result, attempt, case_ids, artifacts);
                job_attempts.push_back(job_attempt_record(
                    attempt, case_ids, result->at("status").get<std::string>(), nullptr, artifact_ids, result));
                const auto* profile_case = find_profile_case(*result, state.spec.case_id);
                state.value = profile_case == nullptr ?
                    missing_profile_case(state.spec, arguments, default_warmup) : *profile_case;
                state.attempts.push_back(attempt_record(attempt, state.value, artifact_ids));
                ++state.run_attempts;
            } else {
                const auto& failure = std::get<ToolFailure>(outcome);
                const auto error = failure_error(failure);
                const auto artifact_ids = collect_artifacts(failure.details, attempt, case_ids, artifacts);
                job_attempts.push_back(
                    job_attempt_record(attempt, case_ids, "failed", error, artifact_ids, nullptr));
                state.value = failed_profile_case(state.spec, arguments, default_warmup, error);
                state.attempts.push_back(attempt_record(attempt, state.value, artifact_ids));
                ++state.run_attempts;
            }
        }
    }

    if (!aggregate_initialized) {
        aggregate = {{"result_detail", arguments.value("result_detail", std::string("metrics"))},
                     {"gpu_timing_unit", "ns"},
                     {"metric_filter", arguments.value("metric_filter", Json(nullptr))},
                     {"statistics", arguments.value("statistics", Json(nullptr))},
                     {"converted_units", arguments.value("converted_units", Json::array())}};
    }

    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    std::size_t with_metrics = 0;
    std::size_t retried = 0;
    std::size_t total_attempts = 0;
    Json cases = Json::array();
    for (auto& state : states) {
        const auto status = state.value.at("status").get<std::string>();
        if (status == "passed") ++passed;
        else if (status == "failed") ++failed;
        else ++incomplete;
        if (case_has_metrics(state.value)) ++with_metrics;
        if (state.attempts.size() > 1) ++retried;
        total_attempts += state.attempts.size();
        state.value["attempt_count"] = state.attempts.size();
        state.value["retry_exhausted"] = retryable_error(state.value.at("error")) &&
            state.run_attempts == max_retries + 1;
        state.value["attempts"] = std::move(state.attempts);
        cases.push_back(std::move(state.value));
    }
    aggregate["success"] = passed == states.size();
    aggregate["kind"] = matrix ? "profile_matrix" : "profile";
    aggregate["status"] = incomplete != 0 ? "incomplete" : (failed == 0 ? "completed" : "completed_with_failures");
    aggregate["requested_cases"] = states.size();
    aggregate["completed_cases"] = passed + failed;
    aggregate["cases_with_metrics"] = with_metrics;
    aggregate["missing_cases"] = states.size() - with_metrics;
    aggregate["failed_cases"] = failed;
    aggregate["retried_cases"] = retried;
    aggregate["total_attempts"] = total_attempts;
    aggregate["max_retries"] = max_retries;
    aggregate["passed"] = passed;
    aggregate["failed"] = failed;
    aggregate["incomplete"] = incomplete;
    aggregate["cases"] = std::move(cases);
    aggregate["artifacts"] = std::move(artifacts);
    aggregate["job_attempts"] = std::move(job_attempts);
    return aggregate;
}

}

namespace detail {

Json retry_profile(const Json& arguments, const bool matrix, const std::size_t default_warmup_frames,
    const ProfileAttempt& submit) {
    return retry_profile_impl(arguments, matrix, default_warmup_frames, submit);
}

bool complete_result_provenance(const Json& provenance) {
    if (!provenance.is_object() || provenance.value("stale", true)) return false;
    constexpr std::array text_fields{
        "workspace_id", "worktree_root", "requested_revision", "resolved_revision", "start_head",
        "completion_head", "shader_tree_id", "source_snapshot_sha256", "active_source_uuid", "config_sha256",
        "preset_id", "preset_sha256", "scene_sha256", "pass_mapping_sha256",
    };
    for (const auto* field : text_fields) {
        const auto found = provenance.find(field);
        if (found == provenance.end() || !found->is_string() || found->get_ref<const std::string&>().empty()) {
            return false;
        }
    }
    const auto checkout_state = provenance.find("vcs_checkout_state");
    const auto branch = provenance.find("branch");
    if (checkout_state == provenance.end() || !checkout_state->is_string() ||
        branch == provenance.end() || !branch->is_string()) {
        return false;
    }
    const auto& state = checkout_state->get_ref<const std::string&>();
    const auto& branch_name = branch->get_ref<const std::string&>();
    if ((state == "VCS_CHECKOUT_STATE_ATTACHED" && branch_name.empty()) ||
        (state == "VCS_CHECKOUT_STATE_DETACHED" && !branch_name.empty()) ||
        (state != "VCS_CHECKOUT_STATE_ATTACHED" && state != "VCS_CHECKOUT_STATE_DETACHED")) {
        return false;
    }
    const auto loaded = provenance.find("shader_loaded_at_unix_ms");
    if (loaded == provenance.end() || wire_uint64(*loaded) == 0) return false;
    const auto environment = provenance.find("environment");
    if (environment == provenance.end() || !environment->is_object()) return false;
    constexpr std::array environment_fields{
        "minecraft_version", "iris_version", "vibris_version", "java_version", "operating_system", "gpu_vendor",
        "gpu_renderer", "opengl_version", "driver_version",
    };
    for (const auto* field : environment_fields) {
        const auto found = environment->find(field);
        if (found == environment->end() || !found->is_string() || found->get_ref<const std::string&>().empty()) {
            return false;
        }
    }
    return true;
}

Json normalize_profile_result(const Json& terminal, const Json& arguments,
    const std::size_t default_warmup_frames, const bool matrix) {
    const auto& wire = strict_job_result(terminal);
    const auto detail = arguments.value("result_detail", std::string("metrics"));
    const auto warmup = arguments.value("warmup_frames", default_warmup_frames);
    const auto restoration = wire.value("restoration", Json(nullptr));
    ProfileCounts counts;
    Json cases = Json::array();

    if (matrix) {
        const auto matrix_value = wire.find("matrix");
        if (matrix_value == wire.end() || !matrix_value->is_object()) {
            throw std::invalid_argument("strict v2 profile matrix result is missing MatrixResult");
        }
        for (const auto& value : strict_array(*matrix_value, "cases")) {
            if (!value.is_object()) throw std::invalid_argument("MatrixCaseResult is not an object");
            const auto case_id = value.value("case_id", std::string{});
            const auto separator = case_id.find("--");
            if (case_id.empty() || separator == std::string::npos) {
                throw std::invalid_argument("strict v2 matrix case has an invalid case_id");
            }
            auto item = profile_case(strict_array(value, "action_receipts"),
                value.value("provenance", Json(nullptr)), restoration, counts, arguments, warmup, case_id,
                case_id.substr(0, separator), case_id.substr(separator + 2), detail, detail == "full");
            if (value.value("status", std::string{}) != "RECEIPT_STATUS_OK") {
                if (item.at("status") == "passed") {
                    --counts.passed;
                    ++counts.failed;
                } else if (item.at("status") == "incomplete") {
                    --counts.incomplete;
                    ++counts.failed;
                }
                item["status"] = "failed";
                if (item.at("error").is_null()) {
                    item["error"] = value.value("error", Json({{"code", "ERROR_CODE_INTERNAL"},
                        {"message", "Matrix case failed without an error."}, {"retryable", false}}));
                }
            }
            item["provenance"] = value.value("provenance", Json(nullptr));
            cases.push_back(std::move(item));
        }
    } else {
        Json receipts = Json::array();
        for (const auto* receipt : ordered_receipts(wire)) receipts.push_back(*receipt);
        cases.push_back(profile_case(receipts, wire.value("provenance", Json(nullptr)), restoration, counts,
            arguments, warmup, arguments.value("__vibris_case_id", std::string("source--config")),
            arguments.value("__vibris_source_id", std::string("source")),
            arguments.value("__vibris_config_id", std::string("config")), detail, false));
        cases.back()["provenance"] = wire.value("provenance", Json(nullptr));
    }

    Json result{{"success", counts.failed == 0 && counts.incomplete == 0},
        {"kind", matrix ? "profile_matrix" : "profile"},
        {"status", counts.incomplete != 0 ? "incomplete" :
            (counts.failed == 0 ? "completed" : "completed_with_failures")},
        {"result_detail", detail},
        {"gpu_timing_unit", "ns"},
        {"metric_filter", arguments.value("metric_filter", Json(nullptr))},
        {"statistics", arguments.value("statistics", Json(nullptr))},
        {"converted_units", arguments.value("converted_units", Json::array())},
        {"requested_cases", counts.requested},
        {"completed_cases", counts.passed + counts.failed},
        {"cases_with_metrics", counts.with_metrics},
        {"missing_cases", counts.requested - counts.with_metrics},
        {"failed_cases", counts.failed},
        {"retried_cases", 0},
        {"passed", counts.passed},
        {"failed", counts.failed},
        {"incomplete", counts.incomplete},
        {"cases", std::move(cases)},
        {"job_id", terminal.value("job_id", std::string{})},
        {"request_id", terminal.value("request_id", std::string{})},
        {"timings", wire.value("timings", Json::object())},
        {"provenance", wire.value("provenance", Json(nullptr))},
        {"restoration", restoration},
        {"action_receipts", wire.value("action_receipts", Json::array())},
        {"prelude_receipts", wire.value("prelude_receipts", Json::array())},
        {"artifacts", wire.value("artifacts", Json::array())},
        {"result_manifest_id", wire.value("result_manifest_id", std::string{})}};
    return result;
}

Json normalize_matrix_result(const Json& terminal, const Json& arguments) {
    const auto& wire = strict_job_result(terminal);
    auto matrix_value = wire.value("matrix", Json(nullptr));
    if (!matrix_value.is_object() && wire.contains("action_receipts") && arguments.contains("matrix") &&
        arguments["matrix"]["sources"].size() == 1 && arguments["matrix"]["configs"].size() == 1) {
        const auto source_id = arguments["matrix"]["sources"][0].get<std::string>();
        const auto config_id = arguments["matrix"]["configs"][0].get<std::string>();
        bool ok = true;
        for (const auto* receipt : ordered_receipts(wire)) ok = ok && receipt_ok(*receipt);
        matrix_value = {{"cases", Json::array({{{"case_id", source_id + "--" + config_id},
            {"status", ok ? "RECEIPT_STATUS_OK" : "RECEIPT_STATUS_FAILED"},
            {"action_receipts", wire.at("action_receipts")},
            {"provenance", wire.value("provenance", Json(nullptr))}}})}};
    }
    const auto* matrix = &matrix_value;
    if (!matrix->is_object()) {
        throw std::invalid_argument("strict v2 matrix result is missing MatrixResult");
    }
    Json cases = Json::array();
    std::size_t passed = 0;
    std::size_t failed = 0;
    for (const auto& value : strict_array(*matrix, "cases")) {
        if (!value.is_object()) throw std::invalid_argument("MatrixCaseResult is not an object");
        const auto case_id = value.value("case_id", std::string{});
        const auto separator = case_id.find("--");
        if (case_id.empty() || separator == std::string::npos) {
            throw std::invalid_argument("strict v2 matrix case has an invalid case_id");
        }
        const bool ok = value.value("status", std::string{}) == "RECEIPT_STATUS_OK" &&
            complete_result_provenance(value.value("provenance", Json(nullptr)));
        ok ? ++passed : ++failed;
        cases.push_back({{"case_id", case_id}, {"source_id", case_id.substr(0, separator)},
            {"config_id", case_id.substr(separator + 2)}, {"status", ok ? "passed" : "failed"},
            {"error", value.value("error", Json(nullptr))},
            {"action_receipts", strict_array(value, "action_receipts")},
            {"provenance", value.value("provenance", Json(nullptr))}});
    }
    const bool restoration_ok = restored(wire);
    return {{"success", failed == 0 && restoration_ok}, {"kind", "matrix"},
        {"status", failed == 0 && restoration_ok ? "completed" : "completed_with_failures"},
        {"requested_cases", matrix->value("requested_cases", cases.size())},
        {"completed_cases", matrix->value("completed_cases", cases.size())},
        {"passed", passed}, {"failed", failed + (restoration_ok ? 0U : 1U)},
        {"cases", std::move(cases)}, {"job_id", terminal.value("job_id", std::string{})},
        {"request_id", terminal.value("request_id", std::string{})},
        {"timings", wire.value("timings", Json::object())},
        {"provenance", wire.value("provenance", Json(nullptr))},
        {"restoration", wire.value("restoration", Json(nullptr))},
        {"artifacts", wire.value("artifacts", Json::array())},
        {"result_manifest_id", wire.value("result_manifest_id", std::string{})},
        {"matrix_axes", arguments.value("matrix", Json(nullptr))}};
}

Json normalize_action_sequence_result(const Json& terminal, const std::string_view kind) {
    const auto& wire = strict_job_result(terminal);
    Json frame_ids = Json::array();
    Json comparisons = Json::array();
    Json first_error = nullptr;
    bool receipts_ok = true;
    for (const auto* receipt : ordered_receipts(wire)) {
        if (!receipt_ok(*receipt)) {
            receipts_ok = false;
            if (first_error.is_null()) first_error = receipt_error(*receipt);
        }
        const auto receipt_kind = receipt->value("kind", std::string{});
        if ((receipt_kind == "ACTION_KIND_TAKE_SCREENSHOT" || receipt_kind == "ACTION_KIND_DUMP_TEXTURE" ||
                receipt_kind == "ACTION_KIND_DUMP_BUFFER") && receipt->contains("capture")) {
            const auto& capture = receipt->at("capture");
            if (capture.is_object() && capture.contains("frame_id")) {
                frame_ids.push_back(wire_uint64(capture.at("frame_id")));
            }
        }
    }
    const bool provenance_ok = complete_result_provenance(wire.value("provenance", Json(nullptr)));
    const bool restoration_ok = restored(wire);
    const bool complete = receipts_ok && provenance_ok && restoration_ok;
    Json result{{"success", complete}, {"kind", kind},
        {"status", complete ? "completed" : "incomplete"},
        {"error", std::move(first_error)}, {"job_id", terminal.value("job_id", std::string{})},
        {"request_id", terminal.value("request_id", std::string{})},
        {"timings", wire.value("timings", Json::object())},
        {"provenance", wire.value("provenance", Json(nullptr))},
        {"restoration", wire.value("restoration", Json(nullptr))},
        {"action_receipts", wire.value("action_receipts", Json::array())},
        {"prelude_receipts", wire.value("prelude_receipts", Json::array())},
        {"artifacts", wire.value("artifacts", Json::array())},
        {"result_manifest_id", wire.value("result_manifest_id", std::string{})},
        {"frame_ids", std::move(frame_ids)}, {"comparisons", comparisons}};
    if (comparisons.size() == 1) result["comparison"] = comparisons.front();
    return result;
}

Json normalize_load_and_screenshot_result(const Json& terminal, const Json& arguments) {
    const auto& wire = strict_job_result(terminal);
    const auto& artifacts = strict_array(wire, "artifacts");
    const Json* screenshot = nullptr;
    const Json* manifest = nullptr;
    for (const auto& artifact : artifacts) {
        if (!artifact.is_object()) throw std::invalid_argument("ArtifactMetadata is not an object");
        const auto kind = artifact.value("kind", std::string{});
        if (kind == "ARTIFACT_KIND_SCREENSHOT" &&
            artifact.value("role", std::string{}) == "ARTIFACT_ROLE_PRIMARY") {
            if (screenshot != nullptr) {
                throw std::invalid_argument("load_and_screenshot returned multiple primary screenshots");
            }
            screenshot = &artifact;
        } else if (kind == "ARTIFACT_KIND_MANIFEST") {
            if (manifest != nullptr) {
                throw std::invalid_argument("load_and_screenshot returned multiple manifest artifacts");
            }
            manifest = &artifact;
        }
    }
    if (screenshot == nullptr) {
        throw std::invalid_argument("load_and_screenshot is missing its primary screenshot artifact");
    }
    if (manifest == nullptr) {
        throw std::invalid_argument("load_and_screenshot is missing its manifest artifact");
    }

    const auto result_manifest_id = wire.value("result_manifest_id", std::string{});
    if (result_manifest_id.empty() || manifest->value("artifact_id", std::string{}) != result_manifest_id) {
        throw std::invalid_argument("load_and_screenshot manifest identity does not match JobResult");
    }

    const auto provenance = wire.value("provenance", Json::object());
    if (!provenance.is_object()) {
        throw std::invalid_argument("load_and_screenshot provenance is not an object");
    }
    const auto restoration = wire.value("restoration", Json::object());
    if (!restoration.is_object()) {
        throw std::invalid_argument("load_and_screenshot restoration is not an object");
    }
    bool receipts_ok = true;
    Json first_error = nullptr;
    for (const auto* receipt : ordered_receipts(wire)) {
        if (!receipt_ok(*receipt)) {
            receipts_ok = false;
            if (first_error.is_null()) first_error = receipt_error(*receipt);
        }
    }
    const auto matches = [&restoration](const std::string_view expected, const std::string_view actual) {
        const auto expected_value = restoration.value(expected, std::string{});
        return !expected_value.empty() && expected_value == restoration.value(actual, std::string{});
    };
    const bool restoration_ok = restoration.value("status", std::string{}) == "RECEIPT_STATUS_OK";
    const bool complete = receipts_ok && restoration_ok;

    Json compact_restoration{
        {"status", restoration.value("status", std::string{})},
        {"source_matches", matches("expected_source_uuid", "actual_source_uuid") &&
            matches("expected_source_sha256", "actual_source_sha256")},
        {"settings_match", matches("expected_settings_sha256", "actual_settings_sha256")},
        {"scene_matches", matches("expected_scene_sha256", "actual_scene_sha256")},
        {"temporal_state_reset", restoration.value("temporal_state_reset", false)},
        {"verified_at_unix_ms", restoration.value("verified_at_unix_ms", Json(0))},
    };
    if (const auto error = restoration.find("error"); error != restoration.end() && error->is_object()) {
        compact_restoration["error"] = *error;
    }

    Json manifest_handle{
        {"manifest_id", manifest->value("artifact_id", std::string{})},
        {"relative_path", manifest->value("relative_path", std::string{})},
        {"sha256", manifest->value("sha256", std::string{})},
        {"byte_size", manifest->value("byte_size", Json(0))},
        {"created_at_unix_ms", manifest->value("created_at_unix_ms", Json(0))},
        {"expires_at_unix_ms", manifest->value("expires_at_unix_ms", Json(0))},
    };

    return {{"success", complete},
        {"kind", "load_and_screenshot"},
        {"status", complete ? "completed" : "incomplete"},
        {"error", std::move(first_error)},
        {"job_id", terminal.value("job_id", std::string{})},
        {"request_id", terminal.value("request_id", std::string{})},
        {"source", {{"source_id", arguments.value("__vibris_source_id", std::string("source"))},
            {"source_uuid", provenance.value("active_source_uuid", std::string{})},
            {"source_sha256", provenance.value("source_snapshot_sha256", std::string{})},
            {"requested_revision", provenance.value("requested_revision", std::string{})},
            {"resolved_revision", provenance.value("resolved_revision", std::string{})}}},
        {"config", {{"config_id", arguments.value("__vibris_config_id", std::string("config"))},
            {"config_sha256", provenance.value("config_sha256", std::string{})},
            {"settings_sha256", provenance.value("effective_settings", Json::object())
                .value("settings_sha256", std::string{})}}},
        {"preset", {{"preset_id", provenance.value("preset_id",
            arguments.value("preset_id", std::string{}))},
            {"preset_sha256", provenance.value("preset_sha256", std::string{})}}},
        {"screenshot", *screenshot},
        {"manifest", std::move(manifest_handle)},
        {"restoration", std::move(compact_restoration)},
        {"timings", wire.value("timings", Json::object())}};
}

}

SynchronousJobRunner::SynchronousJobRunner(
    GrpcClient& client, SourceHandler& sources, const JobContext& config,
    const std::chrono::milliseconds maximum_wait)
    : client_(client), sources_(sources), config_(config), maximum_wait_(maximum_wait) {
    if (maximum_wait_.count() < 0) throw std::invalid_argument("maximum wait must not be negative");
}

std::optional<bool> SynchronousJobRunner::job_present(
    const std::string_view request_id, const std::stop_token stop) {
    proto::GetStatusRequest request;
    request.set_detail(proto::STATUS_DETAIL_JOBS);
    auto completion = std::make_shared<std::promise<std::pair<grpc::Status, proto::GetStatusResponse>>>();
    auto result = completion->get_future();
    if (!client_.get_status(std::move(request),
        [completion](const grpc::Status& status, const proto::GetStatusResponse& response) {
            completion->set_value({status, response});
        })) {
        return std::nullopt;
    }
    while (!stop.stop_requested() &&
        result.wait_for(std::chrono::milliseconds(100)) != std::future_status::ready) {
    }
    if (stop.stop_requested()) return std::nullopt;
    auto [status, response] = result.get();
    if (!status.ok()) return std::nullopt;
    return std::ranges::any_of(response.status().jobs(), [request_id](const proto::JobSummary& job) {
        return job.request_id() == request_id || job.job_id() == request_id;
    });
}

ToolOutcome SynchronousJobRunner::submit_once(std::string_view tool_name, const Json& arguments,
    const proto::ServerHello& server, const proto::SceneContext& context,
    const SynchronousJobControl& control) {
    if (const auto actions = arguments.find("actions"); actions != arguments.end() && actions->is_array() &&
        std::any_of(actions->begin(), actions->end(), [](const Json& action) {
            return action.value("type", std::string{}) == "camera_motion";
        }) && std::find(server.capabilities().begin(), server.capabilities().end(),
            proto::CAPABILITY_CAMERA_MOTION) == server.capabilities().end()) {
        return ToolFailure{"UNSUPPORTED_ACTION", "The connected runtime does not support camera_motion.", false};
    }
    if (const auto actions = arguments.find("actions"); actions != arguments.end() && actions->is_array() &&
        std::any_of(actions->begin(), actions->end(), [](const Json& action) {
            if (action.value("type", std::string{}) != "camera_motion") return false;
            const auto& keys = action.at("keyframes");
            return std::any_of(keys.begin(), keys.end(), [](const Json& key) {
                return key.value("x_delta", 0.0) != 0.0 || key.value("y_delta", 0.0) != 0.0 || key.value("z_delta", 0.0) != 0.0;
            });
        }) && std::find(server.capabilities().begin(), server.capabilities().end(),
            proto::CAPABILITY_CAMERA_TRANSLATION) == server.capabilities().end()) {
        return ToolFailure{"UNSUPPORTED_ACTION", "The connected runtime does not support camera XYZ translation.", false};
    }
    const auto request_id = detail::generate_uuid();
    report_progress(control.progress, request_id, "loading", false);
    sources_.prepare(tool_name, arguments, server);
    const auto references = sources_.bind_latest(request_id);
    try {
        auto request = JobProtocol::request(tool_name, arguments, config_, context, references, request_id);
        const auto server_timeout = std::chrono::milliseconds(request.submit_job().job().timeouts().total_timeout_ms());
        const auto maximum_wait = maximum_wait_.count() == 0 ? server_timeout + std::chrono::seconds(5)
                                                              : maximum_wait_;
        const auto state = std::make_shared<CompletionState>();
        const auto request_accepted = std::make_shared<std::atomic_bool>(false);
        const bool accepted = client_.submit(std::move(request),
            [this, state, request_accepted, progress = control.progress, request_id](
                const grpc::Status& status, const proto::ServerMessage& message) {
            sources_.observe(message);
            if (status.ok() && message.has_job_accepted()) {
                request_accepted->store(true, std::memory_order_relaxed);
                report_progress(progress, request_id, "loading", true);
            } else if (status.ok() && message.has_job_progress()) {
                request_accepted->store(true, std::memory_order_relaxed);
                report_progress(progress, request_id, progress_stage(message.job_progress().stage()), true);
            }
            if (status.ok() && !JobProtocol::is_terminal(message)) return;
            {
                std::scoped_lock lock(state->mutex);
                if (state->done) return;
                state->status = status;
                if (status.ok()) state->terminal = message;
                state->done = true;
            }
            state->ready.notify_one();
        });
        if (!accepted) {
            sources_.retire(request_id);
            return ToolFailure{"QUEUE_FULL", "The bounded gRPC request registry is full.", true};
        }

        std::unique_lock lock(state->mutex);
        const auto deadline = std::chrono::steady_clock::now() + maximum_wait;
        auto next_watchdog = std::chrono::steady_clock::now() + job_watchdog_interval;
        bool server_restarted = false;
        while (!state->done && !control.stop.stop_requested() &&
            std::chrono::steady_clock::now() < deadline) {
            state->ready.wait_until(lock, std::min(deadline, next_watchdog), [&state] { return state->done; });
            if (state->done || !request_accepted->load(std::memory_order_relaxed) ||
                std::chrono::steady_clock::now() < next_watchdog) {
                continue;
            }
            lock.unlock();
            const auto present = job_present(request_id, control.stop);
            if (present == false) {
                server_restarted = client_.cancel(
                    request_id, "The job watchdog found a new Vibris server instance without this request.");
            }
            lock.lock();
            if (server_restarted && !state->done) {
                state->ready.wait(lock, [&state] { return state->done; });
            }
            next_watchdog = std::chrono::steady_clock::now() + job_watchdog_interval;
        }
        if (control.stop.stop_requested() && !state->done) {
            lock.unlock();
            if (!client_.cancel(request_id, "Vibris profile workflow was cancelled.")) {
                lock.lock();
                state->ready.wait(lock, [&state] { return state->done; });
                lock.unlock();
            }
            sources_.retire(request_id);
            return request_failure(
                {"CANCELLED", "The Vibris profile workflow was cancelled.", false}, request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        if (!state->done) {
            lock.unlock();
            if (!client_.cancel(request_id, "Vibris job exceeded the local synchronous deadline.")) {
                lock.lock();
                state->ready.wait(lock, [&state] { return state->done; });
                lock.unlock();
            }
            sources_.retire(request_id);
            return request_failure(
                {"EXECUTION_TIMEOUT", "The Vibris job exceeded its total deadline.", true}, request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        const auto status = state->status;
        auto terminal = std::move(state->terminal);
        lock.unlock();
        sources_.retire(request_id);
        if (server_restarted) {
            report_progress(control.progress, request_id, "retrying", false);
            return ToolFailure{"SERVER_RESTARTED",
                "The connected Vibris server no longer owns the accepted job; it may be submitted again.", true};
        }
        if (!status.ok()) {
            if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
                report_progress(control.progress, request_id, "loading", false);
                return transport_failure(status);
            }
            return request_failure(transport_failure(status), request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        if (!terminal) throw std::logic_error("gRPC completed without a terminal job message");
        report_progress(control.progress, request_id, "checkpointing", false);
        auto outcome = JobProtocol::terminal(*terminal);
        if (auto* result = std::get_if<Json>(&outcome)) ResultMapper::finalize_provenance(*result);
        if (const auto* failure = std::get_if<ToolFailure>(&outcome);
            failure != nullptr &&
            (failure->code == "server_restarted" || failure->code == "SERVER_RESTARTED") &&
            client_.restart_scheduled()) {
            report_progress(control.progress, request_id, "retrying", false);
            if (!client_.wait_for_restart(std::chrono::minutes(5))) {
                return ToolFailure{"SERVER_RESTARTING",
                    "The planned Minecraft restart did not finish within five minutes; retry this call.", true};
            }
            return submit_once(tool_name, arguments, server, context, control);
        }
        return outcome;
    } catch (...) {
        sources_.retire(request_id);
        throw;
    }
}

ToolOutcome SynchronousJobRunner::resume_once(std::string_view request_id,
    const SynchronousJobControl& control) {
    report_progress(control.progress, std::string(request_id), "loading", true);
    const auto state = std::make_shared<CompletionState>();
    const bool accepted = client_.resume(std::string(request_id),
        [state, progress = control.progress, id = std::string(request_id)](
            const grpc::Status& status, const proto::ServerMessage& message) {
            if (status.ok() && message.has_job_progress()) {
                report_progress(progress, id, progress_stage(message.job_progress().stage()), true);
            }
            if (status.ok() && !JobProtocol::is_terminal(message)) return;
            {
                std::scoped_lock lock(state->mutex);
                if (state->done) return;
                state->status = status;
                if (status.ok()) state->terminal = message;
                state->done = true;
            }
            state->ready.notify_one();
        });
    if (!accepted) {
        return request_failure(
            {"QUEUE_FULL", "The bounded gRPC request registry is full.", true}, request_id, true);
    }

    const auto maximum_wait = maximum_wait_.count() == 0 ? std::chrono::minutes(15) : maximum_wait_;
    std::unique_lock lock(state->mutex);
    const auto deadline = std::chrono::steady_clock::now() + maximum_wait;
    auto next_watchdog = std::chrono::steady_clock::now() + job_watchdog_interval;
    bool server_restarted = false;
    while (!state->done && !control.stop.stop_requested() &&
        std::chrono::steady_clock::now() < deadline) {
        state->ready.wait_until(lock, std::min(deadline, next_watchdog), [&state] { return state->done; });
        if (state->done || std::chrono::steady_clock::now() < next_watchdog) continue;
        lock.unlock();
        const auto present = job_present(request_id, control.stop);
        if (present == false) {
            server_restarted = client_.cancel(
                request_id, "The job watchdog found a new Vibris server instance without this request.");
        }
        lock.lock();
        if (server_restarted && !state->done) {
            state->ready.wait(lock, [&state] { return state->done; });
        }
        next_watchdog = std::chrono::steady_clock::now() + job_watchdog_interval;
    }
    if (control.stop.stop_requested() && !state->done) {
        lock.unlock();
        if (!client_.cancel(request_id, "Vibris profile workflow was cancelled.")) {
            lock.lock();
            state->ready.wait(lock, [&state] { return state->done; });
            lock.unlock();
        }
        return request_failure(
            {"CANCELLED", "The Vibris profile workflow was cancelled.", false}, request_id, true);
    }
    if (!state->done) {
        lock.unlock();
        if (!client_.cancel(request_id, "Vibris resumed job exceeded the local synchronous deadline.")) {
            lock.lock();
            state->ready.wait(lock, [&state] { return state->done; });
            lock.unlock();
        }
        return request_failure(
            {"EXECUTION_TIMEOUT", "The resumed Vibris job exceeded its deadline.", true}, request_id, true);
    }
    const auto status = state->status;
    auto terminal = std::move(state->terminal);
    lock.unlock();
    if (server_restarted) {
        report_progress(control.progress, std::string(request_id), "retrying", false);
        return ToolFailure{"SERVER_RESTARTED",
            "The connected Vibris server no longer owns the accepted job; it may be submitted again.", true};
    }
    if (!status.ok()) {
        if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
            report_progress(control.progress, std::string(request_id), "loading", false);
            return transport_failure(status);
        }
        return request_failure(transport_failure(status), request_id, true);
    }
    if (!terminal) throw std::logic_error("gRPC resume completed without a terminal job message");
    report_progress(control.progress, std::string(request_id), "checkpointing", false);
    auto outcome = JobProtocol::terminal(*terminal);
    if (auto* result = std::get_if<Json>(&outcome)) ResultMapper::finalize_provenance(*result);
    return outcome;
}

ToolOutcome SynchronousJobRunner::resume_or_submit(std::string_view request_id,
    std::string_view tool_name, const Json& arguments, const proto::ServerHello& server,
    const proto::SceneContext& context, const SynchronousJobControl& control) {
    auto outcome = resume_once(request_id, control);
    const auto* failure = std::get_if<ToolFailure>(&outcome);
    if (failure == nullptr ||
        (failure->code != "server_restarted" && failure->code != "SERVER_RESTARTED")) {
        return outcome;
    }
    report_progress(control.progress, {}, "retrying", false);
    if (client_.restart_scheduled() && !client_.wait_for_restart(std::chrono::minutes(5))) {
        return ToolFailure{"SERVER_RESTARTING",
            "The planned Minecraft restart did not finish within five minutes; resume this job after restart.", true};
    }
    return submit_once(tool_name, arguments, server, context, control);
}

ToolOutcome SynchronousJobRunner::run(std::string_view tool_name, const Json& arguments,
    const proto::ServerHello& server, const proto::SceneContext& context,
    const SynchronousJobControl& control) {
    if (tool_name == "vibris_run_recipe") {
        const auto recipe = arguments.at("recipe").get<std::string>();
        if (recipe == "profile" || recipe == "profile_matrix") {
            bool first_attempt = true;
            return retry_profile_impl(arguments, recipe == "profile_matrix", config_.default_warmup_frames,
                [this, &server, &context, &control, &first_attempt](const Json& attempt, bool matrix) -> ToolOutcome {
                    ToolOutcome outcome;
                    if (first_attempt && control.resume_request_id) {
                        outcome = resume_or_submit(*control.resume_request_id,
                            "vibris_run_recipe", attempt, server, context, control);
                        if (auto* failure = std::get_if<ToolFailure>(&outcome);
                            failure != nullptr && failure->code == "CANCELLED" &&
                            (!failure->details.is_object() ||
                                !failure->details.value("resume_required", false))) {
                            *failure = ToolFailure{"SERVER_RESTARTED",
                                "The resumed request was cancelled and can be submitted safely again.", true};
                        }
                    } else {
                        if (!first_attempt) report_progress(control.progress, {}, "retrying", false);
                        outcome = submit_once("vibris_run_recipe", attempt, server, context, control);
                    }
                    first_attempt = false;
                    if (!std::holds_alternative<Json>(outcome)) return outcome;
                    return profile_result(std::get<Json>(outcome), attempt, config_, matrix);
                });
        }
        if (recipe == "benchmark_ab") {
            const auto workflow_id = detail::generate_uuid();
            return run_paired_benchmark(arguments, workflow_id, config_.default_warmup_frames,
                [this, &server, &context, &control](const Json& profile_arguments) -> ToolOutcome {
                    return retry_profile_impl(profile_arguments, false, config_.default_warmup_frames,
                        [this, &server, &context, &control](const Json& attempt, bool) -> ToolOutcome {
                            auto outcome = submit_once("vibris_run_recipe", attempt, server, context, control);
                            if (!std::holds_alternative<Json>(outcome)) return outcome;
                            return profile_result(std::get<Json>(outcome), attempt, config_, false);
                        });
                },
                [this, &server, &context, &control](const Json& visual_arguments) -> ToolOutcome {
                    auto outcome = submit_once("vibris_run_recipe", visual_arguments, server, context, control);
                    if (!std::holds_alternative<Json>(outcome)) return outcome;
                    return detail::normalize_action_sequence_result(
                        std::get<Json>(std::move(outcome)), "ab_compare");
                });
        }
        if (recipe == "compile_validate") {
            auto outcome = control.resume_request_id
                ? resume_or_submit(*control.resume_request_id, tool_name, arguments, server, context, control)
                : submit_once(tool_name, arguments, server, context, control);
            if (!std::holds_alternative<Json>(outcome)) return outcome;
            return compile_validation_result(std::get<Json>(std::move(outcome)), arguments);
        }
        auto outcome = control.resume_request_id
            ? resume_or_submit(*control.resume_request_id, tool_name, arguments, server, context, control)
            : submit_once(tool_name, arguments, server, context, control);
        if (recipe == "ab_compare" && std::holds_alternative<Json>(outcome)) {
            auto result = detail::normalize_action_sequence_result(std::get<Json>(std::move(outcome)), recipe);
            return result;
        }
        if (recipe == "load_and_screenshot" && std::holds_alternative<Json>(outcome)) {
            return detail::normalize_load_and_screenshot_result(
                std::get<Json>(std::move(outcome)), arguments);
        }
        if (auto* result = std::get_if<Json>(&outcome)) {
            (*result)["kind"] = recipe;
        }
        return outcome;
    }
    auto outcome = control.resume_request_id
        ? resume_or_submit(*control.resume_request_id, tool_name, arguments, server, context, control)
        : submit_once(tool_name, arguments, server, context, control);
    if (tool_name == "vibris_run_matrix" && std::holds_alternative<Json>(outcome)) {
        return matrix_result(std::get<Json>(outcome), arguments);
    }
    return outcome;
}

}