#include "job_protocol.hpp"
#include "paired_benchmark.hpp"
#include "synchronous_job_fixture.hpp"
#include "synchronous_job_runner.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>

namespace {

using vibris::mcp::JobProtocol;
using vibris::mcp::Json;
using namespace vibris::mcp::test;

void require(const bool condition, const std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

Json terminal_json(const proto::ServerMessage& message) {
	auto outcome = JobProtocol::terminal(message);
	const auto* result = std::get_if<Json>(&outcome);
	if (result == nullptr) throw std::runtime_error("strict-v2 completion mapped to a failure");
	return *result;
}

Json profile_arguments() {
	return {{"recipe", "profile"}, {"frames", 32}, {"warmup_frames", 4},
		{"result_detail", "full"}, {"__vibris_case_id", "candidate--default"},
		{"__vibris_source_id", "candidate"}, {"__vibris_config_id", "default"}};
}

std::size_t occurrences(const std::string_view text, const std::string_view token) {
	std::size_t count = 0;
	for (std::size_t offset = 0; (offset = text.find(token, offset)) != std::string_view::npos;
		offset += token.size()) {
		++count;
	}
	return count;
}

void strict_v2_result_rejects_removed_shape() {
	const Json removed{{"success", true}, {"job_id", "job"}, {"request_id", "request"},
		{"result", {{"action_results", Json::array()}}}};
	bool rejected = false;
	try {
		(void)vibris::mcp::detail::normalize_profile_result(removed, profile_arguments(), 8, false);
	} catch (const std::invalid_argument&) {
		rejected = true;
	}
	require(rejected, "strict-v2 normalization accepted the removed action_results shape");

	const auto inspection = vibris::mcp::detail::normalize_action_sequence_result(
		terminal_json(completed_inspection_message()), "inspect");
	require(inspection.at("success") == true && inspection.at("prelude_receipts").size() == 1 &&
		inspection.at("prelude_receipts").front().contains("runtime_mutation") &&
		inspection.at("action_receipts").size() == 1 &&
		inspection.at("action_receipts").front().at("shader_inspection").at("catalog")
			.at("programs").front().at("program_id") == "composite",
		"strict-v2 normalization lost typed runtime mutation or compile-catalog inspection details");
}

void profile_normalization_strict_v2() {
	const auto result = vibris::mcp::detail::normalize_profile_result(
		terminal_json(completed_profile_message()), profile_arguments(), 8, false);
	require(result.at("success") == true && result.at("kind") == "profile" &&
		result.at("cases").size() == 1 && result.at("cases").front().at("status") == "passed",
		"profile normalization did not complete its single strict-v2 case");
	const auto& metrics = result.at("cases").front().at("metrics");
	require(metrics.at("timing_unit") == "ns" && metrics.at("sampled_frames") == 3 &&
		metrics.at("metrics").front().at("metric_id") == "composite_total" &&
		metrics.at("metrics").front().at("p50_ns") == 900 &&
		metrics.at("metrics").front().at("p95_ns") == 1100,
		"profile normalization lost typed GPU timing fields");
	require(result.at("provenance").at("active_source_uuid") == "candidate" &&
		result.at("restoration").at("status") == "RECEIPT_STATUS_OK" &&
		result.at("action_receipts").size() == 1 && result.at("prelude_receipts").size() == 1 &&
		!result.contains("action_results") && !result.at("provenance").contains("complete"),
		"profile normalization synthesized legacy receipt or provenance fields");
}

void provenance_checkout_state_is_strict() {
	const auto attached = terminal_json(completed_profile_message()).at("result").at("provenance");
	require(vibris::mcp::detail::complete_result_provenance(attached),
		"complete attached provenance was rejected");

	auto detached = attached;
	detached["vcs_checkout_state"] = "VCS_CHECKOUT_STATE_DETACHED";
	detached["branch"] = "";
	require(vibris::mcp::detail::complete_result_provenance(detached),
		"complete detached provenance was rejected");

	auto branch_only = attached;
	branch_only.erase("vcs_checkout_state");
	require(!vibris::mcp::detail::complete_result_provenance(branch_only),
		"branch-only provenance was accepted");

	detached["branch"] = "synthetic";
	require(!vibris::mcp::detail::complete_result_provenance(detached),
		"detached provenance with a synthetic branch was accepted");
}

void profile_retry_preserves_exact_preset_id() {
	auto arguments = profile_arguments();
	arguments["preset_id"] = "night-gi-1-720p";
	arguments["source"] = {{"kind", "workspace"}};
	arguments["config"] = {{"QUALITY", 2}};
	arguments["max_retries"] = 1;
	arguments["__vibris_workflow_id"] = "workflow-id";
	arguments["__vibris_preset"] = {{"preset_id", "night-gi-1-720p"},
		{"preset_sha256", "preset-sha"}};
	std::size_t calls = 0;
	Json retry = nullptr;
	const auto result = vibris::mcp::detail::retry_profile(arguments, false, 8,
		[&](const Json& attempt, const bool matrix) -> vibris::mcp::ToolOutcome {
			++calls;
			require(!matrix, "single profile retry was changed into a matrix request");
			if (calls == 1) {
				return vibris::mcp::ToolFailure{"NO_GPU_SAMPLES", "fixture retry", true};
			}
			retry = attempt;
			return Json{{"success", true}, {"status", "completed"},
				{"cases", Json::array({{{"case_id", "candidate--default"},
					{"source_id", "candidate"}, {"config_id", "default"},
					{"status", "passed"}, {"error", nullptr}, {"metrics", Json::object()}}})},
				{"artifacts", Json::array()}};
		});
	require(calls == 2 && result.at("success") == true && result.at("retried_cases") == 1 &&
		retry.at("preset_id") == "night-gi-1-720p" &&
		retry.at("__vibris_preset") == arguments.at("__vibris_preset") &&
		retry.at("__vibris_workflow_id") == "workflow-id" &&
		retry.at("source") == arguments.at("source") && retry.at("config") == arguments.at("config") &&
		retry.at("__vibris_attempt") == 2 && retry.at("__vibris_previous_attempts").size() == 1,
		"automatic profile retry changed preset identity or lost immutable retry context");
}

void matrix_normalization_strict_v2() {
	const Json single{{"matrix", {{"sources", Json::array({"baseline"})},
		{"configs", Json::array({"default"})}}}};
	const auto action_case = vibris::mcp::detail::normalize_matrix_result(
		terminal_json(completed_profile_message()), single);
	require(action_case.at("success") == true && action_case.at("cases").size() == 1 &&
		action_case.at("cases")[0].at("case_id") == "baseline--default",
		"action-sequence matrix step was not normalized as one case");
	const Json arguments{{"frames", 32}, {"warmup_frames", 4}, {"result_detail", "full"},
		{"matrix", {{"sources", Json::array({"baseline", "candidate"})},
		{"configs", Json::array({"default"})}}}};
	const auto terminal = terminal_json(completed_matrix_message());
	const auto profile = vibris::mcp::detail::normalize_profile_result(terminal, arguments, 8, true);
	require(profile.at("success") == true && profile.at("cases").size() == 2 &&
		profile.at("cases").at(0).at("metrics").at("metrics").front().at("average_ns") == 1200 &&
		profile.at("cases").at(1).at("metrics").at("metrics").front().at("average_ns") == 1000,
		"profile matrix normalization did not consume MatrixCaseResult receipts");
	const auto matrix = vibris::mcp::detail::normalize_matrix_result(terminal, arguments);
	require(matrix.at("success") == true && matrix.at("requested_cases") == 2 &&
		matrix.at("completed_cases") == 2 && matrix.at("cases").at(0).at("action_receipts").size() == 1 &&
		matrix.at("restoration").at("status") == "RECEIPT_STATUS_OK" &&
		!matrix.contains("action_results"),
		"generic matrix normalization did not preserve strict-v2 typed receipts");

	auto failed_message = completed_matrix_message();
	auto* failed_case = failed_message.mutable_job_completed()->mutable_result()->mutable_matrix()->mutable_cases(0);
	failed_case->set_status(proto::RECEIPT_STATUS_FAILED);
	failed_case->mutable_error()->set_code(proto::ERROR_CODE_NO_GPU_SAMPLES);
	failed_case->mutable_error()->set_message("no samples");
	const auto failed_profile = vibris::mcp::detail::normalize_profile_result(
		terminal_json(failed_message), arguments, 8, true);
	require(failed_profile.at("success") == false && failed_profile.at("passed") == 1 &&
		failed_profile.at("failed") == 1 && failed_profile.at("cases").front().at("status") == "failed",
		"failed MatrixCaseResult was counted as a successful normalized profile case");
}

void visual_normalization_strict_v2() {
	const auto result = vibris::mcp::detail::normalize_action_sequence_result(
		terminal_json(completed_visual_message()), "ab_compare");
	require(result.at("success") == true && result.at("frame_ids") == Json::array({41, 42}) &&
		result.at("comparisons").empty() && result.at("artifacts").size() == 2 &&
		result.at("action_receipts").size() == 7 && result.at("prelude_receipts").size() == 2 &&
		result.at("action_receipts").at(0).at("kind") == "ACTION_KIND_RESET_TEMPORAL_STATE" &&
		result.at("action_receipts").at(0).at("reset_temporal").at("completed_at_unix_ms") == 1001 &&
		result.at("action_receipts").at(3).at("kind") == "ACTION_KIND_RESET_TEMPORAL_STATE" &&
		result.at("action_receipts").at(3).at("reset_temporal").at("completed_at_unix_ms") == 1002 &&
		!result.contains("action_results"),
		"visual normalization did not derive its receipt from strict-v2 action details");
	const auto guards = vibris::mcp::visual_comparison_guards(result, true);
	require(guards.at("passed") == false && guards.at("two_ordered_temporal_reset_receipts") == true,
		"capture-only receipt must require a local comparison verdict while retaining reset guards");
}

void screenshot_result_compact_strict_v2() {
	const auto terminal = terminal_json(completed_screenshot_message());
	const auto durable_bytes = terminal.dump();
	require(occurrences(durable_bytes, "changed_from_default") == 692,
		"screenshot fixture does not reproduce the expanded effective-setting payload");
	const auto durable_path = std::filesystem::temp_directory_path() /
		("vibris-screenshot-result-" + std::to_string(
			std::chrono::steady_clock::now().time_since_epoch().count()) + ".json");
	{
		std::ofstream output(durable_path, std::ios::binary | std::ios::trunc);
		output.write(durable_bytes.data(), static_cast<std::streamsize>(durable_bytes.size()));
	}

	const Json arguments{{"recipe", "load_and_screenshot"}, {"preset_id", "night-gi-1-720p"}};
	const auto result = vibris::mcp::detail::normalize_load_and_screenshot_result(terminal, arguments);
	std::ifstream input(durable_path, std::ios::binary);
	const std::string durable_after{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
	std::error_code cleanup_error;
	std::filesystem::remove(durable_path, cleanup_error);
	require(durable_after == durable_bytes && terminal.dump() == durable_bytes,
		"compact projection changed the complete immutable durable result bytes");
	require(terminal.at("result").at("artifacts").at(1).at("sha256") == "result-sha256",
		"compact projection changed the durable result artifact hash identity");

	const auto encoded = result.dump();
	require(encoded.size() <= 16U * 1024U && encoded.find("changed_from_default") == std::string::npos,
		"compact screenshot result exceeded 16 KiB or retained effective-setting entries");
	require(!result.contains("action_receipts") && !result.contains("prelude_receipts") &&
		!result.contains("artifacts") && !result.contains("provenance"),
		"compact screenshot result retained raw audit collections");
	require(result.at("success") == true && result.at("status") == "completed" &&
		result.at("job_id") == "job-screenshot" && result.at("request_id") == "request-screenshot",
		"compact screenshot result lost its terminal identity or state");
	require(result.at("source").at("source_id") == "source" &&
		result.at("source").at("source_uuid") == "source-uuid" &&
		result.at("source").at("source_sha256") == "source-sha" &&
		result.at("config").at("config_id") == "config" &&
		result.at("config").at("config_sha256") == "config-sha256" &&
		result.at("preset").at("preset_id") == "night-gi-1-720p",
		"compact screenshot result lost source, config, or preset identity");
	require(result.at("screenshot").at("artifact_id") == "screenshot-artifact" &&
		result.at("screenshot").at("sha256") == "screenshot-sha256" &&
		result.at("screenshot").at("byte_size") == 1888374 &&
		result.at("screenshot").at("resource").at("width") == 1280 &&
		result.at("screenshot").at("resource").at("height") == 720 &&
		result.at("manifest").at("manifest_id") == "manifest-artifact" &&
		result.at("manifest").at("sha256") == "manifest-sha256",
		"compact screenshot result lost its single screenshot or manifest handle");
	require(result.at("restoration").at("status") == "RECEIPT_STATUS_OK" &&
		result.at("restoration").at("source_matches") == true &&
		result.at("restoration").at("settings_match") == true &&
		result.at("restoration").at("scene_matches") == true &&
		result.at("restoration").at("temporal_state_reset") == true &&
		result.at("timings").at("total_ms") == 200,
		"compact screenshot result lost restoration or timing evidence");
}

void run(const std::string_view scenario) {
	if (scenario == "StrictV2ResultShape") return strict_v2_result_rejects_removed_shape();
	if (scenario == "ProfileNormalizationStrictV2") return profile_normalization_strict_v2();
	if (scenario == "ProvenanceCheckoutStateStrict") return provenance_checkout_state_is_strict();
	if (scenario == "ProfileRetryPresetIdentity") return profile_retry_preserves_exact_preset_id();
	if (scenario == "MatrixNormalizationStrictV2") return matrix_normalization_strict_v2();
	if (scenario == "VisualNormalizationStrictV2") return visual_normalization_strict_v2();
	if (scenario == "ScreenshotResultCompactStrictV2") return screenshot_result_compact_strict_v2();
	if (scenario == "all") {
		strict_v2_result_rejects_removed_shape();
		profile_normalization_strict_v2();
		provenance_checkout_state_is_strict();
		profile_retry_preserves_exact_preset_id();
		matrix_normalization_strict_v2();
		visual_normalization_strict_v2();
		screenshot_result_compact_strict_v2();
		return;
	}
	throw std::invalid_argument("unknown scenario");
}

} // namespace

int main(const int argc, char** argv) {
	const std::string scenario = argc > 1 ? argv[1] : "all";
	try {
		run(scenario);
		std::cout << "PASS " << scenario << '\n';
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL " << scenario << ": " << error.what() << '\n';
		return 1;
	}
}
