#include "profile_matrix_workflow.hpp"
#include "config_document.hpp"
#include "state_error.hpp"
#include "workspace_source_fixture.hpp"

#include <chrono>
#include <array>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace {

using vibris::mcp::DurableJobStepExecution;
using vibris::mcp::DurableJobWorkflow;
using vibris::mcp::JobContext;
using vibris::mcp::Json;
using vibris::mcp::ToolFailure;
using vibris::mcp::ToolOutcome;
using vibris::mcp::test::WorkspaceFixture;
using namespace std::chrono_literals;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";

void require(const bool condition, std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

JobContext config() {
	return {.workspace_id = std::string(workspace_id),
		.save_id = "shader-test-world", .dimension_id = "minecraft:overworld",
		.time_preset_id = "noon", .camera_preset_id = "spawn", .fov = 70.0,
		.default_warmup_frames = 8};
}

Json scene() {
	return {{"save_id", "shader-test-world"}, {"dimension_id", "minecraft:overworld"},
		{"time_preset_id", "noon"}, {"weather_preset_id", "clear"},
		{"camera_preset_id", "spawn"}, {"fov", 70.0},
		{"resolution", {{"width", 1280}, {"height", 720}}}, {"settings_preset_id", "quality"}};
}

Json matrix(const std::size_t count, std::string execution = "sync") {
	Json configs = Json::array();
	Json axis = Json::array();
	for (std::size_t index = 0; index < count; ++index) {
		const auto id = "config-" + std::to_string(index + 1);
		configs.push_back({{"id", id}, {"values", {{"QUALITY", index + 1}}}});
		axis.push_back(id);
	}
	return {{"recipe", "profile_matrix"},
		{"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
		{"configs", std::move(configs)},
		{"matrix", {{"sources", Json::array({"source"})}, {"configs", std::move(axis)}}},
		{"__vibris_scene_context", scene()}, {"frames", 16}, {"warmup_frames", 4},
		{"metrics", Json::array({{{"metric_id", "composite_total"}, {"role", "target"}}})},
		{"execution", std::move(execution)}};
}

ToolOutcome profile_success(const DurableJobStepExecution& execution) {
	const auto case_id = execution.arguments.at("__vibris_case_id").get<std::string>();
	return Json{{"success", true}, {"status", "completed"},
		{"cases", Json::array({{{"case_id", case_id},
			{"source_id", execution.arguments.at("__vibris_source_id")},
			{"config_id", execution.arguments.at("__vibris_config_id")},
			{"status", "passed"}, {"error", nullptr},
			{"metrics", {{"metrics", Json::array({{{"metric_id", "composite_total"},
				{"average_ns", 1}, {"p50_ns", 1}, {"p95_ns", 1}}})}}}}})},
		{"artifacts", Json::array()}};
}

Json wait_terminal(DurableJobWorkflow& workflow, const std::string& job_id) {
	auto value = std::get<Json>(workflow.control(
		{{"operation", "wait"}, {"job_id", job_id}, {"timeout_ms", 5'000}}));
	require(!value.at("wait_timed_out").get<bool>(),
		"single blocking durable-job wait timed out");
	return value;
}

void interruption_after_17_resumes_at_18() {
	WorkspaceFixture workspace;
	std::set<std::string> completed_ids;
	std::size_t calls = 0;
	DurableJobWorkflow first(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++calls;
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			const auto request = "request-" + std::to_string(calls);
			execution.progress(request, "submitted", false);
			execution.progress(request, "accepted", true);
			if (calls == 18) return ToolFailure{"SERVER_OFFLINE", "simulated accepted interruption", true,
				{{"resume_required", true}}};
			require(completed_ids.insert(id).second, "a case was executed twice before restart");
			return profile_success(execution);
		});
	const auto paused = std::get<Json>(first.start("vibris_run_recipe", matrix(38), config()));
	const auto job_id = paused.at("job_id").get<std::string>();
	const auto root = workspace.worktree() / ".vibris" / "jobs" / job_id;
	require(paused.at("workflow_state") == "paused" &&
		paused.at("progress").at("completed_steps") == 17 &&
		paused.at("progress").at("current_step") == 17 && paused.at("progress").at("eta_ms").is_number(),
		"the first process did not durably stop after 17 of 38 cases");
	require(std::filesystem::is_regular_file(root / "request.json") &&
		std::filesystem::is_regular_file(root / "state.json") &&
		std::filesystem::is_regular_file(root / "events.jsonl") &&
		std::filesystem::is_directory(root / "receipts") &&
		std::filesystem::is_directory(root / "sources") &&
		std::distance(std::filesystem::directory_iterator(root / "receipts"),
			std::filesystem::directory_iterator{}) == 17,
		"the strict-v2 job directory is missing atomic state, events, receipts, or sources");
	const auto immutable_request = vibris::mcp::test::read_file(root / "request.json");
	vibris::mcp::test::write_file(workspace.live_file(), "mutated-after-queue");

	std::size_t resumed_calls = 0;
	std::optional<std::string> first_resume_id;
	DurableJobWorkflow restarted(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++resumed_calls;
			if (resumed_calls == 1) first_resume_id = execution.resume_request_id;
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			require(completed_ids.insert(id).second, "restart duplicated a completed case");
			return profile_success(execution);
		});
	const auto resumed = std::get<Json>(restarted.control({{"operation", "resume"}, {"job_id", job_id}}));
	require(resumed.at("workflow_state") == "queued" || resumed.at("workflow_state") == "running",
		"resume did not start asynchronously");
	const auto terminal = wait_terminal(restarted, job_id);
	require(terminal.at("workflow_state") == "completed" && resumed_calls == 21 &&
		first_resume_id == "request-18" && completed_ids.size() == 38,
		"restart did not resume accepted case 18 and finish each case exactly once");
	const auto result = std::get<Json>(restarted.control(
		{{"operation", "result"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(result.at("result").at("requested_cases") == 38 &&
		result.at("result").at("completed_cases") == 38 && result.at("result").at("passed") == 38,
		"the final matrix result did not contain all 38 receipts");
	require(vibris::mcp::test::read_file(root / "request.json") == immutable_request,
		"resume modified the immutable request document");
	require(std::filesystem::is_regular_file(root / "result.json") &&
		std::distance(std::filesystem::directory_iterator(root / "receipts"),
			std::filesystem::directory_iterator{}) == 38,
		"the final result or complete receipt set was not published");
	std::uint64_t previous = 0;
	for (const auto& event : result.at("events")) {
		const auto sequence = event.at("sequence").get<std::uint64_t>();
		require(sequence == previous + 1, "event sequence was not monotonic across restart");
		previous = sequence;
	}
	require(previous == result.at("event_cursor"), "event cursor did not match the append-only log tail");
}

void cancellation_is_truthful_and_resumable() {
	WorkspaceFixture workspace;
	std::mutex mutex;
	bool entered = false;
	bool resumed_accepted = false;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			if (execution.resume_request_id) {
				resumed_accepted = *execution.resume_request_id == "accepted-request";
				return profile_success(execution);
			}
			execution.progress("accepted-request", "accepted", true);
			{
				std::scoped_lock lock(mutex);
				entered = true;
			}
			while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
			return ToolFailure{"CANCELLED", "fixture cancellation", false,
				{{"resume_required", true}}};
		});
	const auto started = std::get<Json>(workflow.start("vibris_run_recipe", matrix(1, "async"), config()));
	const auto job_id = started.at("job_id").get<std::string>();
	for (int attempt = 0; attempt < 500; ++attempt) {
		{
			std::scoped_lock lock(mutex);
			if (entered) break;
		}
		std::this_thread::sleep_for(1ms);
	}
	const auto running = std::get<Json>(workflow.control({{"operation", "query"}, {"job_id", job_id}}));
	require(running.at("cancelable") == true && running.at("resumable") == false &&
		running.at("progress").at("eta_ms").is_null(),
		"running job advertised false cancellation/resume/ETA state");
	const auto cancelled = std::get<Json>(workflow.control({{"operation", "cancel"}, {"job_id", job_id}}));
	require(cancelled.at("workflow_state") == "cancelled" && cancelled.at("resumable") == true &&
		cancelled.at("cancelable") == false && cancelled.at("current_request_accepted") == true,
		"cancel did not preserve the accepted request as resumable");
	static_cast<void>(workflow.control({{"operation", "resume"}, {"job_id", job_id}}));
	const auto completed = wait_terminal(workflow, job_id);
	require(completed.at("workflow_state") == "completed" && resumed_accepted,
		"resume blindly resubmitted instead of resuming the accepted request");
}

void finalization_only_resume_reuses_immutable_receipts() {
	WorkspaceFixture workspace;
	std::string job_id;
	std::filesystem::path root;
	{
		std::size_t first_executor_calls = 0;
		DurableJobWorkflow first(workspace.worktree(), std::string(workspace_id),
			[&](DurableJobStepExecution execution) -> ToolOutcome {
				++first_executor_calls;
				const auto id = execution.arguments.at("__vibris_workflow_id").get<std::string>();
				vibris::mcp::test::write_file(
					workspace.worktree() / ".vibris" / "jobs" / id / "result.json", "{}");
				return profile_success(execution);
			});
		const auto paused = std::get<Json>(first.start("vibris_run_recipe", matrix(1), config()));
		job_id = paused.at("job_id").get<std::string>();
		root = workspace.worktree() / ".vibris" / "jobs" / job_id;
		bool completed_before_failure = false;
		for (const auto& event : paused.at("events")) {
			completed_before_failure = completed_before_failure || event.at("type") == "completed";
		}
		require(paused.at("workflow_state") == "paused" && paused.at("resumable") == false &&
			paused.at("progress").at("completed_steps") == 1 && paused.at("progress").at("total_steps") == 1 &&
			paused.at("progress").at("current_step").is_null() &&
			paused.at("last_error").at("retryable") == false && first_executor_calls == 1 &&
			std::filesystem::is_regular_file(root / "receipts" / "00000000.json") &&
			!completed_before_failure,
			"finalization failure did not leave one immutable receipt without a false completion event");
	}
	require(std::filesystem::remove(root / "result.json"),
		"fixture could not remove the mismatched publication obstacle");
	std::size_t replay_executor_calls = 0;
	DurableJobWorkflow restarted(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution) -> ToolOutcome {
			++replay_executor_calls;
			return ToolFailure{"UNEXPECTED_REPLAY", "finalization resume replayed a child step", false};
		});
	const auto resumable = std::get<Json>(restarted.control(
		{{"operation", "query"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(resumable.at("resumable") == true,
		"fully checkpointed finalization failure was not advertised as safely resumable");
	static_cast<void>(restarted.control({{"operation", "resume"}, {"job_id", job_id}}));
	const auto completed = wait_terminal(restarted, job_id);
	require(completed.at("workflow_state") == "completed" && replay_executor_calls == 0 &&
		std::filesystem::is_regular_file(root / "result.json"),
		"finalization-only resume reran an executor step or failed to publish the result");
}

void nonretryable_step_failure_terminalizes_failed() {
	WorkspaceFixture workspace;
	std::size_t executor_calls = 0;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution) -> ToolOutcome {
			++executor_calls;
			return ToolFailure{"INVALID_TEST_STEP", "fixture nonretryable step failure", false};
		});
	const auto failed = std::get<Json>(workflow.start("vibris_run_recipe", matrix(1), config()));
	require(failed.at("workflow_state") == "failed" && failed.at("resumable") == false &&
		failed.at("progress").at("completed_steps") == 0 && executor_calls == 1 &&
		failed.at("result").at("status") == "failed" &&
		failed.at("result").at("error").at("error_code") == "INVALID_TEST_STEP",
		"nonretryable step failure did not publish a truthful terminal result");
	const auto resumed = workflow.control(
		{{"operation", "resume"}, {"job_id", failed.at("job_id")}});
	const auto* failure = std::get_if<ToolFailure>(&resumed);
	require(failure != nullptr && failure->code == "JOB_NOT_RESUMABLE" && executor_calls == 1,
		"unsafe nonretryable step failure resumed or re-executed the step");
}

void semantic_retryable_failure_retries_the_same_step() {
	WorkspaceFixture workspace;
	std::vector<std::string> calls;
	bool first = true;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			calls.push_back(id);
			if (first) {
				first = false;
				execution.progress("terminal-child-request", "accepted", true);
				return Json{{"success", false}, {"status", "completed_with_failures"},
					{"cases", Json::array({{{"status", "failed"},
						{"error", {{"error_code", "server_not_available"},
							{"message", "fixture runtime unavailable"}, {"retryable", false}}}}})}};
			}
			require(!execution.resume_request_id,
				"a terminal semantic failure tried to resume an already terminal child request");
			return profile_success(execution);
		});
	const auto paused = std::get<Json>(workflow.start("vibris_run_recipe", matrix(2), config()));
	require(paused.at("workflow_state") == "paused" && paused.at("resumable") == true &&
		paused.at("progress").at("completed_steps") == 0 &&
		paused.at("current_request_id").is_null() && !paused.at("current_request_accepted"),
		"retryable semantic failure was checkpointed as success or retained a terminal child request");
	static_cast<void>(workflow.control({{"operation", "resume"}, {"job_id", paused.at("job_id")}}));
	const auto completed = wait_terminal(workflow, paused.at("job_id").get<std::string>());
	require(completed.at("workflow_state") == "completed" &&
		calls == std::vector<std::string>({"source--config-1", "source--config-1", "source--config-2"}),
		"resume did not retry exactly the interrupted semantic-failure step");
}

void semantic_nonretryable_failure_stops_following_steps() {
	WorkspaceFixture workspace;
	std::vector<std::string> calls;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			calls.push_back(id);
			if (calls.size() == 2) {
				return Json{{"success", false}, {"status", "failed"},
					{"details", {{"restoration", {{"error", {
						{"code", "ERROR_CODE_RESTORE_FAILED"}, {"message", "fixture heap exhaustion"},
						{"retryable", false}}}}}}}};
			}
			return profile_success(execution);
		});
	const auto failed = std::get<Json>(workflow.start("vibris_run_recipe", matrix(3), config()));
	const auto job_id = failed.at("job_id").get<std::string>();
	require(failed.at("workflow_state") == "failed" && failed.at("resumable") == false &&
		failed.at("progress").at("completed_steps") == 1 && calls.size() == 2 &&
		failed.at("result").at("error").at("error_code") == "ERROR_CODE_RESTORE_FAILED" &&
		failed.at("result").at("receipts").size() == 2,
		"nonretryable semantic child failure did not stop and terminalize the workflow");
	const auto root = workspace.worktree() / ".vibris" / "jobs" / job_id / "receipts";
	require(std::filesystem::is_regular_file(root / "00000000.json") &&
		std::filesystem::is_regular_file(root / "00000001.json") &&
		!std::filesystem::exists(root / "00000002.json"),
		"semantic failure executed or checkpointed a later child step");
	const auto result = std::get<Json>(workflow.control(
		{{"operation", "result"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(result.at("workflow_state") == "failed" && result.contains("result"),
		"vibris_job result did not expose the terminal failure document");
}

void generic_plans_checkpoint_each_case() {
	WorkspaceFixture workspace;
	std::size_t calls = 0;
	std::set<std::string> scheduling_groups;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++calls;
			scheduling_groups.insert(execution.arguments.at("__vibris_workflow_id").get<std::string>());
			return Json{{"success", true}, {"tool", execution.tool_name}, {"case", execution.arguments.at("name")}};
		});
	Json arguments{{"cases", Json::array({{{"name", "compile-a"}}, {{"name", "compile-b"}},
		{{"name", "compile-c"}}})}, {"__vibris_scene_context", scene()}};
	const auto result = std::get<Json>(workflow.start("compile_validate", arguments, config()));
	require(result.at("workflow_state") == "completed" && calls == 3 && scheduling_groups.size() == 1 &&
		*scheduling_groups.begin() == result.at("job_id").get<std::string>() &&
		result.at("progress").at("completed_steps") == 3,
		"generic compile-like cases were not checkpointed under one scheduling group");
	const auto request_path = workspace.worktree() / ".vibris" / "jobs" /
		result.at("job_id").get<std::string>() / "request.json";
	auto request = Json::parse(vibris::mcp::test::read_file(request_path));
	request["schema_version"] = 3 - 1;
	vibris::mcp::test::write_file(request_path, request.dump(2));
	bool rejected = false;
	try {
		static_cast<void>(workflow.control({{"operation", "query"}, {"job_id", result.at("job_id")}}));
	} catch (const vibris::mcp::StateError& error) {
		rejected = error.code() == "UNSUPPORTED_VERSION";
	}
	require(rejected, "a non-v3 durable request was read instead of failing with UNSUPPORTED_VERSION");
}

void compile_matrix_checkpoints_and_aggregates_every_case() {
	WorkspaceFixture workspace;
	std::set<std::string> cases;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			require(execution.tool_name == "vibris_run_recipe" &&
				execution.arguments.at("recipe") == "compile_validate" &&
				!execution.arguments.contains("matrix"),
				"compile matrix step was not reduced to one typed compile_validate case");
			const auto case_id = execution.arguments.at("__vibris_case_id").get<std::string>();
			require(cases.insert(case_id).second, "compile matrix executed a case more than once");
			return Json{{"success", true}, {"kind", "compile_validate"}, {"status", "completed"},
				{"cases", Json::array({{{"case_id", case_id}, {"status", "passed"},
					{"catalog", {{"programs", Json::array()}}}, {"provenance", {{"complete", true}}}}})},
				{"artifacts", Json::array()}};
		});
	Json arguments{{"recipe", "compile_validate"},
		{"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
		{"configs", Json::array({
			{{"id", "low"}, {"values", {{"QUALITY", 1}}}},
			{{"id", "medium"}, {"values", {{"QUALITY", 2}}}},
			{{"id", "high"}, {"values", {{"QUALITY", 3}}}}})},
		{"matrix", {{"sources", Json::array({"source"})},
			{"configs", Json::array({"low", "medium", "high"})}}},
		{"__vibris_scene_context", scene()}, {"execution", "sync"}};
	const auto started = std::get<Json>(workflow.start("vibris_run_recipe", arguments, config()));
	const auto job_id = started.at("job_id").get<std::string>();
	const auto result = std::get<Json>(workflow.control(
		{{"operation", "result"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(cases.size() == 3 && started.at("progress").at("completed_steps") == 3 &&
		result.at("result").at("requested_cases") == 3 &&
		result.at("result").at("passed") == 3 && result.at("result").at("cases").size() == 3,
		"compile validation matrix did not checkpoint and aggregate every selected case");
	const auto receipt_root = workspace.worktree() / ".vibris" / "jobs" / job_id / "receipts";
	require(std::distance(std::filesystem::directory_iterator(receipt_root),
		std::filesystem::directory_iterator{}) == 3,
		"compile validation matrix did not persist one receipt per case");
}

void matrix_and_benchmark_use_step_plans() {
	WorkspaceFixture workspace;
	std::size_t matrix_calls = 0;
	DurableJobWorkflow matrix_workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++matrix_calls;
			require(execution.arguments.at("matrix").at("sources").size() == 1 &&
				execution.arguments.at("matrix").at("configs").size() == 1,
				"durable matrix step was not reduced to one source/config case");
			const auto source_id = execution.arguments.at("matrix").at("sources").front().get<std::string>();
			const auto config_id = execution.arguments.at("matrix").at("configs").front().get<std::string>();
			return Json{{"success", true}, {"cases", Json::array({{{"case_id", source_id + "--" + config_id}}})},
				{"artifacts", Json::array()}};
		});
	Json matrix_arguments{{"sources", Json::array({{{"id", "a"}, {"kind", "workspace"}},
		{{"id", "b"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "low"}}, {{"id", "high"}}})},
		{"matrix", {{"sources", Json::array({"a", "b"})}, {"configs", Json::array({"low", "high"})}}},
		{"actions", Json::array()}, {"__vibris_scene_context", scene()}};
	const auto matrix_result = std::get<Json>(
		matrix_workflow.start("vibris_run_matrix", matrix_arguments, config()));
	require(matrix_result.at("workflow_state") == "completed" && matrix_calls == 4 &&
		matrix_result.at("result").at("cases").size() == 4,
		"durable matrix did not checkpoint and aggregate every selected case");

	std::mutex mutex;
	bool entered = false;
	bool carried_scene = false;
	bool carried_preset = false;
	DurableJobWorkflow benchmark_workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			{
				std::scoped_lock lock(mutex);
				entered = true;
				carried_scene = execution.arguments.contains("__vibris_scene_context");
				carried_preset = execution.arguments.value("preset_id", std::string{}) ==
					"night-gi-1-720p";
			}
			while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
			return ToolFailure{"CANCELLED", "fixture cancellation", false};
		});
	Json benchmark{{"recipe", "benchmark_ab"}, {"baseline", {{"kind", "workspace"}}},
		{"candidate", {{"kind", "workspace"}}}, {"frames", 4}, {"rounds", 2},
		{"control_rounds", 2}, {"visual", {{"pixel_error_threshold", 0.0}}},
		{"metrics", Json::array({{{"metric_id", "composite_total"}, {"role", "target"}}})},
		{"preset_id", "night-gi-1-720p"},
		{"__vibris_preset", {{"preset_id", "night-gi-1-720p"}, {"version", "2"}}},
		{"__vibris_scene_context", scene()}, {"execution", "async"}};
	const auto started = std::get<Json>(
		benchmark_workflow.start("vibris_run_recipe", benchmark, config()));
	for (int attempt = 0; attempt < 500; ++attempt) {
		{
			std::scoped_lock lock(mutex);
			if (entered) break;
		}
		std::this_thread::sleep_for(1ms);
	}
	const auto query = std::get<Json>(benchmark_workflow.control(
		{{"operation", "query"}, {"job_id", started.at("job_id")}}));
	const auto request_root = workspace.worktree() / ".vibris" / "jobs" /
		started.at("job_id").get<std::string>() / "request.json";
	const auto durable_request = Json::parse(vibris::mcp::test::read_file(request_root));
	bool all_steps_preserved_preset = true;
	for (const auto& step : durable_request.at("steps")) {
		all_steps_preserved_preset = all_steps_preserved_preset &&
			step.at("arguments").at("preset_id") == "night-gi-1-720p";
	}
	require(query.at("progress").at("total_steps") == 17 &&
		query.at("progress").at("completed_steps") == 0 && query.at("progress").at("eta_ms").is_null() &&
		carried_scene && carried_preset && all_steps_preserved_preset,
		"paired benchmark did not expose 16 measurement/control plus one visual checkpoint");
	static_cast<void>(benchmark_workflow.control(
		{{"operation", "cancel"}, {"job_id", started.at("job_id")}}));
}

void expired_artifacts_remain_in_durable_results() {
	WorkspaceFixture workspace;
	const auto artifact = workspace.worktree() / ".vibris" / "artifact" / "job" / "request" / "payload.bin";
	std::filesystem::create_directories(artifact.parent_path());
	vibris::mcp::test::write_file(artifact, "payload");
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			auto outcome = std::get<Json>(profile_success(execution));
			outcome["artifacts"] = Json::array({{{"artifact_id", "artifact-1"},
				{"path", artifact.string()}, {"expired", false}}});
			return outcome;
		});
	const auto completed = std::get<Json>(workflow.start("vibris_run_recipe", matrix(1), config()));
	const auto job_id = completed.at("job_id").get<std::string>();
	std::filesystem::remove(artifact);
	const auto result = std::get<Json>(workflow.control(
		{{"operation", "result"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(result.at("result").at("artifacts").front().at("expired").get<bool>(),
		"expired artifacts were removed from or not marked in the durable result projection");
}

void blocking_wait_times_out_compactly_without_polling() {
	WorkspaceFixture workspace;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[](DurableJobStepExecution execution) -> ToolOutcome {
			while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
			return ToolFailure{"CANCELLED", "fixture cancellation", false};
		});
	const auto started = std::get<Json>(workflow.start(
		"vibris_run_recipe", matrix(1, "async"), config()));
	const auto job_id = started.at("job_id").get<std::string>();
	const auto waited = std::get<Json>(workflow.control(
		{{"operation", "wait"}, {"job_id", job_id}, {"timeout_ms", 5}}));
	require(waited.at("wait_timed_out").get<bool>() && !waited.contains("events") &&
		waited.at("next_action").at("arguments").at("operation") == "wait" &&
		waited.at("next_action").at("arguments").at("timeout_ms") == 300'000,
		"bounded durable-job wait did not return one compact retry instruction");
	static_cast<void>(workflow.control({{"operation", "cancel"}, {"job_id", job_id}}));
}

void server_restart_resubmits_current_step_once() {
	WorkspaceFixture workspace;
	std::size_t calls = 0;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++calls;
			if (calls == 1) {
				execution.progress("lost-request", "retrying", false);
				return ToolFailure{"SERVER_RESTARTED", "fixture server restart", true};
			}
			return profile_success(execution);
		});
	const auto started = std::get<Json>(workflow.start(
		"vibris_run_recipe", matrix(1, "async"), config()));
	const auto completed = wait_terminal(workflow, started.at("job_id").get<std::string>());
	require(completed.at("workflow_state") == "completed" && calls == 2,
		"a lost child job was not resubmitted exactly once after server restart");
}

void creation_retains_twenty_inactive_jobs() {
	WorkspaceFixture workspace;
	const auto jobs = workspace.worktree() / ".vibris" / "jobs";
	std::filesystem::create_directories(jobs);
	std::vector<std::string> inactive;
	for (std::size_t index = 0; index < 25; ++index) {
		const auto job_id = vibris::mcp::detail::generate_uuid();
		inactive.push_back(job_id);
		const auto root = jobs / job_id;
		std::filesystem::create_directory(root);
		const auto state = std::array{"completed", "failed", "cancelled", "paused"}[index % 4];
		vibris::mcp::test::write_file(root / "request.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}}.dump());
		vibris::mcp::test::write_file(root / "state.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}, {"workflow_state", state},
			{"updated_unix_ms", static_cast<std::int64_t>(1'000 + index)}}.dump());
	}
	std::vector<std::string> active;
	for (const auto state : {"queued", "running"}) {
		const auto job_id = vibris::mcp::detail::generate_uuid();
		active.push_back(job_id);
		const auto root = jobs / job_id;
		std::filesystem::create_directory(root);
		vibris::mcp::test::write_file(root / "request.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}}.dump());
		vibris::mcp::test::write_file(root / "state.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}, {"workflow_state", state},
			{"updated_unix_ms", 1}}.dump());
	}
	const auto corrupt = vibris::mcp::detail::generate_uuid();
	std::filesystem::create_directory(jobs / corrupt);
	vibris::mcp::test::write_file(jobs / corrupt / "state.json", "{}");

	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[](DurableJobStepExecution execution) -> ToolOutcome { return profile_success(execution); });
	const auto created = std::get<Json>(workflow.start("vibris_run_recipe", matrix(1), config()));

	for (std::size_t index = 0; index < 5; ++index) {
		require(!std::filesystem::exists(jobs / inactive[index]), "An oldest inactive job was retained.");
	}
	for (std::size_t index = 5; index < inactive.size(); ++index) {
		require(std::filesystem::is_directory(jobs / inactive[index]), "A newest inactive job was deleted.");
	}
	for (const auto& job_id : active) {
		require(std::filesystem::is_directory(jobs / job_id), "A queued or running job was deleted.");
	}
	require(std::filesystem::is_directory(jobs / corrupt), "An unverifiable job directory was deleted.");
	std::size_t warnings = 0;
	for (const auto& event : created.at("events")) {
		if (event.at("type") == "cleanup_warning") ++warnings;
	}
	require(warnings == 1, "Cleanup did not record exactly one warning for the corrupt job.");
}

void failed_cleanup_warns_and_retries_on_next_creation() {
	WorkspaceFixture workspace;
	const auto jobs = workspace.worktree() / ".vibris" / "jobs";
	std::filesystem::create_directories(jobs);
	std::string oldest;
	for (std::size_t index = 0; index < 21; ++index) {
		const auto job_id = vibris::mcp::detail::generate_uuid();
		if (index == 0) oldest = job_id;
		const auto root = jobs / job_id;
		std::filesystem::create_directory(root);
		vibris::mcp::test::write_file(root / "request.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}}.dump());
		vibris::mcp::test::write_file(root / "state.json", Json{{"schema_version", 3},
			{"workspace_id", workspace_id}, {"job_id", job_id}, {"workflow_state", "completed"},
			{"updated_unix_ms", static_cast<std::int64_t>(1'000 + index)}}.dump());
	}
	const auto blocked_file = jobs / oldest / "blocked.bin";
	vibris::mcp::test::write_file(blocked_file, "blocked");
	const auto blocker = CreateFileW(blocked_file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
		OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
	require(blocker != INVALID_HANDLE_VALUE, "Could not lock cleanup failure fixture.");
	DurableJobWorkflow first(workspace.worktree(), std::string(workspace_id),
		[](DurableJobStepExecution execution) -> ToolOutcome { return profile_success(execution); });
	const auto warned = std::get<Json>(first.start("vibris_run_recipe", matrix(1), config()));
	require(std::filesystem::is_directory(jobs / oldest), "Failed deletion did not retain the job for retry.");
	require(std::ranges::any_of(warned.at("events"), [](const Json& event) {
		return event.at("type") == "cleanup_warning";
	}), "Failed deletion was not recorded in the new job event log.");
	CloseHandle(blocker);

	DurableJobWorkflow second(workspace.worktree(), std::string(workspace_id),
		[](DurableJobStepExecution execution) -> ToolOutcome { return profile_success(execution); });
	static_cast<void>(second.start("vibris_run_recipe", matrix(1), config()));
	require(!std::filesystem::exists(jobs / oldest), "Next creation did not retry the failed deletion.");
}

} // namespace

int main() {
	try {
		interruption_after_17_resumes_at_18();
		cancellation_is_truthful_and_resumable();
		finalization_only_resume_reuses_immutable_receipts();
		nonretryable_step_failure_terminalizes_failed();
		semantic_retryable_failure_retries_the_same_step();
		semantic_nonretryable_failure_stops_following_steps();
		generic_plans_checkpoint_each_case();
		compile_matrix_checkpoints_and_aggregates_every_case();
		matrix_and_benchmark_use_step_plans();
		expired_artifacts_remain_in_durable_results();
		blocking_wait_times_out_compactly_without_polling();
		server_restart_resubmits_current_step_once();
		creation_retains_twenty_inactive_jobs();
		failed_cleanup_warns_and_retries_on_next_creation();
		std::cout << "PASS DurableWorkflowCheckpointResume\n";
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL DurableWorkflowCheckpointResume: " << error.what() << '\n';
		return 1;
	}
}
