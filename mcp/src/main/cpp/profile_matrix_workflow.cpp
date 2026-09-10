#include "profile_matrix_workflow.hpp"

#include "config_document.hpp"
#include "paired_benchmark.hpp"
#include "source_preparer.hpp"
#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <limits>
#include <numeric>
#include <sstream>
#include <system_error>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::uintmax_t maximum_document_bytes = 64ULL * 1024ULL * 1024ULL;
constexpr std::size_t maximum_steps = 4096;
constexpr std::size_t maximum_inactive_jobs = 20;
constexpr int durable_schema_version = 3;

[[noreturn]] void checkpoint_error(std::string message, bool retryable = false) {
	throw StateError("JOB_CHECKPOINT_ERROR", std::move(message), retryable);
}

[[noreturn]] void invalid_job() {
	throw StateError("INVALID_JOB", "The durable job is invalid.");
}

std::int64_t unix_ms() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::system_clock::now().time_since_epoch()).count();
}

bool reparse_point(const fs::path& path) {
	const auto attributes = GetFileAttributesW(path.c_str());
	return attributes != INVALID_FILE_ATTRIBUTES &&
		(attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
}

void ensure_directory(const fs::path& path);

class WorkspaceJobsLock final {
public:
	explicit WorkspaceJobsLock(const fs::path& state_directory) {
		ensure_directory(state_directory.parent_path());
		const auto path = state_directory.parent_path() / "jobs.lock";
		handle_ = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE,
			FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
			FILE_ATTRIBUTE_NORMAL, nullptr);
		if (handle_ == INVALID_HANDLE_VALUE) checkpoint_error("Unable to open the durable jobs lock.", true);
		OVERLAPPED overlapped{};
		if (!LockFileEx(handle_, LOCKFILE_EXCLUSIVE_LOCK, 0, MAXDWORD, MAXDWORD, &overlapped)) {
			CloseHandle(handle_);
			handle_ = INVALID_HANDLE_VALUE;
			checkpoint_error("Unable to acquire the durable jobs lock.", true);
		}
	}

	~WorkspaceJobsLock() {
		if (handle_ == INVALID_HANDLE_VALUE) return;
		OVERLAPPED overlapped{};
		UnlockFileEx(handle_, 0, MAXDWORD, MAXDWORD, &overlapped);
		CloseHandle(handle_);
	}

	WorkspaceJobsLock(const WorkspaceJobsLock&) = delete;
	WorkspaceJobsLock& operator=(const WorkspaceJobsLock&) = delete;

private:
	HANDLE handle_ = INVALID_HANDLE_VALUE;
};

void refresh_artifact_expiry(Json& value) {
	if (value.is_array()) {
		for (auto& item : value) refresh_artifact_expiry(item);
		return;
	}
	if (!value.is_object()) return;
	if (value.contains("artifact_id")) {
		const auto path = value.value("path", value.value("relative_path", std::string{}));
		if (!path.empty()) {
			std::error_code error;
			value["expired"] = !fs::is_regular_file(fs::path(path), error) || error;
		}
	}
	for (auto& [key, item] : value.items()) {
		if (key != "expired") refresh_artifact_expiry(item);
	}
}

void ensure_directory(const fs::path& path) {
	std::error_code error;
	fs::create_directories(path, error);
	if (error || !fs::is_directory(path, error) || error || reparse_point(path)) {
		checkpoint_error("The durable job directory is unavailable or unsafe.", true);
	}
	const auto parent = path.parent_path();
	if (!parent.empty() && reparse_point(parent)) {
		checkpoint_error("A durable job parent directory must not be a reparse point.");
	}
}

void write_all(HANDLE output, std::string_view value) {
	std::size_t offset = 0;
	while (offset < value.size()) {
		const auto requested = static_cast<DWORD>(std::min<std::size_t>(
			value.size() - offset, std::numeric_limits<DWORD>::max()));
		DWORD written = 0;
		if (!WriteFile(output, value.data() + offset, requested, &written, nullptr) || written == 0) {
			checkpoint_error("Unable to write durable job state.", true);
		}
		offset += written;
	}
}

void atomic_write(const fs::path& path, std::string value, const bool replace) {
	ensure_directory(path.parent_path());
	const auto temporary = path.parent_path() /
		(path.filename().string() + ".tmp-" + detail::generate_uuid());
	HANDLE output = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
		FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr);
	if (output == INVALID_HANDLE_VALUE) checkpoint_error("Unable to create durable job state.", true);
	try {
		write_all(output, value);
		if (!FlushFileBuffers(output)) {
			CloseHandle(output);
			output = INVALID_HANDLE_VALUE;
			checkpoint_error("Unable to flush durable job state.", true);
		}
		if (!CloseHandle(output)) {
			output = INVALID_HANDLE_VALUE;
			checkpoint_error("Unable to close durable job state.", true);
		}
		output = INVALID_HANDLE_VALUE;
		const auto flags = MOVEFILE_WRITE_THROUGH | (replace ? MOVEFILE_REPLACE_EXISTING : 0);
		if (!MoveFileExW(temporary.c_str(), path.c_str(), flags)) {
			checkpoint_error("Unable to publish durable job state.", true);
		}
	} catch (...) {
		if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
		DeleteFileW(temporary.c_str());
		throw;
	}
}

void append_line(const fs::path& path, std::string value) {
	ensure_directory(path.parent_path());
	HANDLE output = CreateFileW(path.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ, nullptr,
		OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr);
	if (output == INVALID_HANDLE_VALUE) checkpoint_error("Unable to open the durable event log.", true);
	try {
		write_all(output, value);
		if (!FlushFileBuffers(output)) {
			CloseHandle(output);
			output = INVALID_HANDLE_VALUE;
			checkpoint_error("Unable to flush the durable event log.", true);
		}
		if (!CloseHandle(output)) {
			output = INVALID_HANDLE_VALUE;
			checkpoint_error("Unable to close the durable event log.", true);
		}
	} catch (...) {
		if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
		throw;
	}
}

std::string read_file(const fs::path& path, const bool allow_empty = false) {
	std::error_code error;
	const auto status = fs::symlink_status(path, error);
	if (error || !fs::is_regular_file(status) || fs::is_symlink(status) || reparse_point(path)) {
		checkpoint_error("The requested durable job document does not exist or is unsafe.");
	}
	const auto size = fs::file_size(path, error);
	if (error || (!allow_empty && size == 0) || size > maximum_document_bytes) invalid_job();
	std::ifstream input(path, std::ios::binary);
	if (!input) checkpoint_error("Unable to read durable job state.", true);
	std::string value(static_cast<std::size_t>(size), '\0');
	input.read(value.data(), static_cast<std::streamsize>(value.size()));
	if ((!value.empty() && !input) || input.gcount() != static_cast<std::streamsize>(value.size())) {
		checkpoint_error("Unable to read complete durable job state.", true);
	}
	return value;
}

bool inactive_state(std::string_view state) {
	return state == "completed" || state == "failed" || state == "cancelled" || state == "paused";
}

struct CleanupJob final {
	std::string job_id;
	std::string workflow_state;
	std::int64_t updated_unix_ms;
};

void cleanup_warning(std::vector<std::string>& warnings, std::string message) {
	std::cerr << "vibris: durable job cleanup warning: " << message << '\n';
	warnings.push_back(std::move(message));
}

std::optional<CleanupJob> inspect_cleanup_job(
	const fs::path& directory, std::string_view workspace_id) {
	try {
		std::error_code error;
		const auto status = fs::symlink_status(directory, error);
		if (error || !fs::is_directory(status) || fs::is_symlink(status) || reparse_point(directory)) {
			return std::nullopt;
		}
		const auto job_id = directory.filename().string();
		if (!detail::is_uuid(job_id)) return std::nullopt;
		const auto request = Json::parse(read_file(directory / "request.json"));
		const auto state = Json::parse(read_file(directory / "state.json"));
		if (!request.is_object() || !state.is_object() ||
			request.value("schema_version", 0) != durable_schema_version ||
			state.value("schema_version", 0) != durable_schema_version ||
			request.value("workspace_id", std::string{}) != workspace_id ||
			state.value("workspace_id", std::string{}) != workspace_id ||
			request.value("job_id", std::string{}) != job_id ||
			state.value("job_id", std::string{}) != job_id ||
			!state.contains("updated_unix_ms") || !state.at("updated_unix_ms").is_number_integer()) {
			return std::nullopt;
		}
		const auto workflow_state = state.value("workflow_state", std::string{});
		if (!inactive_state(workflow_state) && workflow_state != "queued" && workflow_state != "running") {
			return std::nullopt;
		}
		return CleanupJob{job_id, workflow_state, state.at("updated_unix_ms").get<std::int64_t>()};
	} catch (...) {
		return std::nullopt;
	}
}

bool ordinary_job_tree(const fs::path& root) {
	std::error_code error;
	const auto root_status = fs::symlink_status(root, error);
	if (error || !fs::is_directory(root_status) || fs::is_symlink(root_status) || reparse_point(root)) return false;
	fs::recursive_directory_iterator iterator(root, fs::directory_options::none, error);
	const fs::recursive_directory_iterator end;
	while (!error && iterator != end) {
		const auto& path = iterator->path();
		const auto status = iterator->symlink_status(error);
		if (error || fs::is_symlink(status) || reparse_point(path) ||
			(!fs::is_directory(status) && !fs::is_regular_file(status))) return false;
		iterator.increment(error);
	}
	return !error;
}

bool remove_job_tree(const fs::path& root) {
	std::error_code error;
	std::vector<fs::path> entries;
	for (fs::recursive_directory_iterator iterator(root, fs::directory_options::none, error), end;
		!error && iterator != end; iterator.increment(error)) {
		entries.push_back(iterator->path());
	}
	if (error) return false;
	std::ranges::sort(entries, [](const fs::path& left, const fs::path& right) {
		return std::distance(left.begin(), left.end()) > std::distance(right.begin(), right.end());
	});
	const auto request = root / "request.json";
	const auto state = root / "state.json";
	for (const auto& path : entries) {
		if (path == request || path == state) continue;
		if (!fs::remove(path, error) || error) return false;
	}
	if (!fs::remove(request, error) || error || !fs::remove(state, error) || error ||
		!fs::remove(root, error) || error) return false;
	return true;
}

std::vector<std::string> prune_inactive_jobs(
	const fs::path& state_directory, std::string_view workspace_id) {
	std::vector<std::string> warnings;
	std::error_code error;
	if (!fs::exists(state_directory, error)) return warnings;
	if (error || !fs::is_directory(state_directory, error) || error || reparse_point(state_directory)) {
		cleanup_warning(warnings, "The durable jobs directory is unavailable or unsafe; cleanup was skipped.");
		return warnings;
	}
	std::vector<CleanupJob> inactive;
	fs::directory_iterator iterator(state_directory, fs::directory_options::none, error);
	const fs::directory_iterator end;
	while (!error && iterator != end) {
		const auto path = iterator->path();
		std::error_code status_error;
		const auto status = iterator->symlink_status(status_error);
		if (!status_error && fs::is_directory(status)) {
			if (auto job = inspect_cleanup_job(path, workspace_id)) {
				if (inactive_state(job->workflow_state)) inactive.push_back(std::move(*job));
			} else {
				cleanup_warning(warnings, "Skipped unverifiable durable job directory " + path.filename().string() + ".");
			}
		}
		iterator.increment(error);
	}
	if (error) cleanup_warning(warnings, "Could not enumerate every durable job; cleanup continued with verified jobs.");
	std::ranges::sort(inactive, [](const CleanupJob& left, const CleanupJob& right) {
		if (left.updated_unix_ms != right.updated_unix_ms) return left.updated_unix_ms > right.updated_unix_ms;
		return left.job_id < right.job_id;
	});
	for (std::size_t index = maximum_inactive_jobs; index < inactive.size(); ++index) {
		const auto& selected = inactive[index];
		const auto path = state_directory / selected.job_id;
		const auto current = inspect_cleanup_job(path, workspace_id);
		if (!current || current->workflow_state != selected.workflow_state ||
			current->updated_unix_ms != selected.updated_unix_ms || !ordinary_job_tree(path)) {
			cleanup_warning(warnings, "Skipped changed or unsafe durable job " + selected.job_id + ".");
			continue;
		}
		if (!remove_job_tree(path)) {
			cleanup_warning(warnings, "Could not delete inactive durable job " + selected.job_id + ".");
		}
	}
	return warnings;
}

std::string receipt_name(const std::size_t index) {
	auto value = std::to_string(index);
	value.insert(value.begin(), 8 - std::min<std::size_t>(8, value.size()), '0');
	return value + ".json";
}

Json stored_config(const JobContext& config) {
	return Json::parse(detail::serialize_config(config));
}

JobContext parsed_config(const Json& value, std::string_view workspace_id) {
	auto config = detail::parse_config(value.dump(), detail::ConfigDocumentKind::persisted);
	if (config.workspace_id != workspace_id) invalid_job();
	return config;
}

const Json& named_value(const Json& values, std::string_view id, std::string_view kind) {
	const auto found = std::ranges::find_if(values, [id](const Json& value) {
		return value.at("id").get_ref<const std::string&>() == id;
	});
	if (found == values.end()) throw std::invalid_argument(std::string(kind) + " ID is not declared");
	return *found;
}

Json freeze_source(SourcePreparer& preparer, std::vector<PreparedSource>& snapshots,
	const Json& source, const std::string& job_id) {
	const auto kind = source.at("kind").get<std::string>();
	snapshots.emplace_back(kind == "commit"
		? preparer.prepare_commit(source.at("revision").get<std::string>())
		: preparer.prepare_workspace());
	const auto& reference = snapshots.back().reference();
	const bool commit = reference.origin().has_commit();
	return {{"kind", "snapshot"},
		{"job_id", job_id},
		{"snapshot_uuid", reference.source_uuid()},
		{"origin_kind", commit ? "commit" : "workspace"},
		{"origin_name", commit ? reference.origin().commit().repository_id()
			: reference.origin().workspace().display_name()},
		{"worktree_root", commit ? reference.origin().commit().worktree_root()
			: reference.origin().workspace().worktree_root()},
		{"requested_revision", reference.requested_revision()},
		{"resolved_revision", reference.resolved_revision()},
		{"snapshot_sha256", reference.snapshot_sha256()},
		{"archive_format", ::vibris::control::v2::SourceArchiveFormat_Name(reference.archive_format())},
		{"compressed_bytes", reference.compressed_bytes()},
		{"vcs_checkout_state", ::vibris::control::v2::VcsCheckoutState_Name(reference.vcs_checkout_state())},
		{"branch", reference.branch()},
		{"start_head", reference.start_head()},
		{"shader_tree_id", reference.shader_tree_id()},
		{"dirty_shader_delta_sha256", reference.dirty_shader_delta_sha256()},
		{"file_count", reference.file_count()},
		{"total_bytes", reference.total_bytes()}};
}

Json freeze_arguments(const fs::path& workspace_root, const fs::path& state_directory,
	const std::string& job_id, Json arguments) {
	const auto snapshot_root = state_directory / job_id / "sources";
	ensure_directory(snapshot_root);
	SourcePreparer preparer(workspace_root, snapshot_root,
		{.max_total_bytes = 512ULL * 1024ULL * 1024ULL, .max_files = 100'000},
		SourceDestinationLayout::archive_file);
	std::vector<PreparedSource> snapshots;
	auto freeze_field = [&](const char* name) {
		if (arguments.contains(name)) arguments[name] = freeze_source(preparer, snapshots, arguments.at(name), job_id);
	};
	freeze_field("source");
	freeze_field("baseline");
	freeze_field("candidate");
	for (const auto* variant : {"a", "b"}) {
		if (arguments.contains(variant) && arguments.at(variant).contains("source")) {
			arguments[variant]["source"] = freeze_source(
				preparer, snapshots, arguments.at(variant).at("source"), job_id);
		}
	}
	if (arguments.contains("sources")) {
		for (auto& source : arguments.at("sources")) {
			auto id = source.at("id");
			source = freeze_source(preparer, snapshots, source, job_id);
			source["id"] = std::move(id);
		}
	}
	for (auto& snapshot : snapshots) snapshot.release();
	return arguments;
}

Json profile_matrix_steps(const Json& arguments, std::string_view job_id) {
	Json result = Json::array();
	for (const auto& source_axis : arguments.at("matrix").at("sources")) {
		const auto source_id = source_axis.get<std::string>();
		auto source = named_value(arguments.at("sources"), source_id, "source");
		source.erase("id");
		for (const auto& config_axis : arguments.at("matrix").at("configs")) {
			const auto config_id = config_axis.get<std::string>();
			const auto& config = named_value(arguments.at("configs"), config_id, "config");
			Json nested{{"recipe", "profile"}, {"frames", arguments.at("frames")}, {"source", source},
				{"__vibris_case_id", source_id + "--" + config_id}, {"__vibris_source_id", source_id},
				{"__vibris_config_id", config_id}, {"__vibris_workflow_id", job_id},
				{"__vibris_result_kind", "profile_matrix"}};
			for (const auto* field : {"warmup_frames", "result_detail", "metric_filter", "statistics",
				"converted_units", "max_retries", "result_csv", "preset_id", "__vibris_scene_context",
				"__vibris_preset"}) {
				if (arguments.contains(field)) nested[field] = arguments.at(field);
			}
			if (config.contains("values")) nested["config"] = config.at("values");
			result.push_back({{"id", source_id + "--" + config_id}, {"kind", "case"},
				{"tool_name", "vibris_run_recipe"}, {"arguments", std::move(nested)}});
		}
	}
	return result;
}

Json compile_validation_steps(const Json& arguments, std::string_view job_id) {
	if (!arguments.contains("matrix")) return Json::array({{{"id", "compile"}, {"kind", "case"},
		{"tool_name", "vibris_run_recipe"}, {"arguments", arguments}}});
	Json result = Json::array();
	for (const auto& source_axis : arguments.at("matrix").at("sources")) {
		const auto source_id = source_axis.get<std::string>();
		auto source = named_value(arguments.at("sources"), source_id, "source");
		source.erase("id");
		for (const auto& config_axis : arguments.at("matrix").at("configs")) {
			const auto config_id = config_axis.get<std::string>();
			const auto& config = named_value(arguments.at("configs"), config_id, "config");
			Json nested{{"recipe", "compile_validate"}, {"source", source},
				{"__vibris_case_id", source_id + "--" + config_id}, {"__vibris_source_id", source_id},
				{"__vibris_config_id", config_id}, {"__vibris_workflow_id", job_id},
				{"__vibris_result_kind", "compile_validate"}};
			for (const auto* field : {"baseline", "baseline_config", "result_csv", "converted_units",
				"preset_id", "__vibris_scene_context", "__vibris_preset"}) {
				if (arguments.contains(field)) nested[field] = arguments.at(field);
			}
			if (config.contains("values")) nested["config"] = config.at("values");
			result.push_back({{"id", source_id + "--" + config_id}, {"kind", "case"},
				{"tool_name", "vibris_run_recipe"}, {"arguments", std::move(nested)}});
		}
	}
	return result;
}

Json matrix_steps(const Json& arguments) {
	Json result = Json::array();
	for (const auto& source_axis : arguments.at("matrix").at("sources")) {
		const auto source_id = source_axis.get<std::string>();
		for (const auto& config_axis : arguments.at("matrix").at("configs")) {
			const auto config_id = config_axis.get<std::string>();
			Json nested = arguments;
			nested["sources"] = Json::array({named_value(arguments.at("sources"), source_id, "source")});
			nested["configs"] = Json::array({named_value(arguments.at("configs"), config_id, "config")});
			nested["matrix"] = {{"sources", Json::array({source_id})}, {"configs", Json::array({config_id})}};
			result.push_back({{"id", source_id + "--" + config_id}, {"kind", "case"},
				{"tool_name", "vibris_run_matrix"}, {"arguments", std::move(nested)}});
		}
	}
	return result;
}

Json benchmark_steps(const Json& arguments, std::string_view job_id, const std::size_t warmup) {
	Json result = Json::array();
	for (const auto& item : paired_benchmark_plan(arguments)) {
		auto nested = paired_benchmark_profile_arguments(arguments, item, job_id, warmup);
		if (arguments.contains("__vibris_scene_context")) {
			nested["__vibris_scene_context"] = arguments.at("__vibris_scene_context");
		}
		result.push_back({{"id", item.case_id}, {"kind", item.phase},
			{"tool_name", "vibris_run_recipe"},
			{"arguments", std::move(nested)}});
	}
	if (arguments.contains("visual")) {
		auto nested = paired_benchmark_visual_arguments(arguments, warmup);
		if (arguments.contains("__vibris_scene_context")) {
			nested["__vibris_scene_context"] = arguments.at("__vibris_scene_context");
		}
		result.push_back({{"id", "visual"}, {"kind", "visual"},
			{"tool_name", "vibris_run_recipe"},
			{"arguments", std::move(nested)}});
	}
	return result;
}

Json plan_steps(std::string_view tool_name, const Json& arguments,
	std::string_view job_id, const std::size_t warmup) {
	if (tool_name == "vibris_run_recipe" && arguments.value("recipe", std::string{}) == "profile_matrix") {
		return profile_matrix_steps(arguments, job_id);
	}
	if (tool_name == "vibris_run_recipe" && arguments.value("recipe", std::string{}) == "compile_validate") {
		return compile_validation_steps(arguments, job_id);
	}
	if (tool_name == "vibris_run_recipe" && arguments.value("recipe", std::string{}) == "benchmark_ab") {
		return benchmark_steps(arguments, job_id, warmup);
	}
	if (tool_name == "vibris_run_matrix") return matrix_steps(arguments);
	if (arguments.contains("cases") && arguments.at("cases").is_array() && !arguments.at("cases").empty()) {
		Json result = Json::array();
		std::size_t index = 0;
		for (const auto& item : arguments.at("cases")) {
			auto nested = arguments;
			nested.erase("cases");
			if (item.is_object()) nested.update(item);
			result.push_back({{"id", "case-" + std::to_string(index++)}, {"kind", "case"},
				{"tool_name", tool_name}, {"arguments", std::move(nested)}});
		}
		return result;
	}
	return Json::array({{{"id", "step-0"}, {"kind", "job"},
		{"tool_name", tool_name}, {"arguments", arguments}}});
}

Json failure_json(const ToolFailure& failure) {
	return {{"success", false}, {"error_code", failure.code}, {"message", failure.message},
		{"retryable", failure.retryable}, {"details", failure.details}};
}

const Json* nested_error(const Json& value, const std::size_t depth = 0) {
	if (depth > 8 || !value.is_object()) return nullptr;
	if ((value.contains("error_code") || value.contains("code")) && value.contains("message")) {
		return &value;
	}
	if (const auto error = value.find("error"); error != value.end() && error->is_object()) {
		if (const auto* found = nested_error(*error, depth + 1)) return found;
	}
	for (const auto* key : {"details", "restoration", "cases", "job_attempts", "result"}) {
		const auto nested = value.find(key);
		if (nested == value.end()) continue;
		if (nested->is_array()) {
			for (const auto& item : *nested) {
				if (const auto* found = nested_error(item, depth + 1)) return found;
			}
		} else if (const auto* found = nested_error(*nested, depth + 1)) {
			return found;
		}
	}
	return nullptr;
}

bool known_retryable_child_error(std::string code) {
	std::ranges::transform(code, code.begin(), [](const unsigned char value) {
		return static_cast<char>(std::tolower(value));
	});
	for (const auto suffix : {"server_not_available", "server_offline", "server_restarted",
		"queue_timeout", "execution_timeout", "durable_workflow_busy", "transport_error",
		"grpc_unavailable"}) {
		if (code == suffix || code.ends_with(std::string("error_code_") + suffix)) return true;
	}
	return false;
}

std::optional<ToolFailure> semantic_failure(const Json& result) {
	if (!result.is_object() || result.value("success", true)) return std::nullopt;
	const auto* error = nested_error(result);
	const auto& source = error == nullptr ? result : *error;
	auto code = source.value("error_code", source.value("code", std::string{}));
	if (code.empty()) code = result.value("error_code", result.value("code", std::string("CHILD_JOB_FAILED")));
	auto message = source.value("message", std::string{});
	if (message.empty()) message = result.value("message", std::string("A durable child step reported failure."));
	const auto retryable = known_retryable_child_error(code) ||
		(source.contains("retryable") ? source.at("retryable").get<bool>() : result.value("retryable", false));
	auto details = source.value("details", Json::object());
	if (!details.is_object()) details = Json{{"reported_details", std::move(details)}};
	if (result.contains("status")) details["child_status"] = result.at("status");
	return ToolFailure{std::move(code), std::move(message), retryable, std::move(details)};
}

ToolOutcome receipt_outcome(const Json& receipt) {
	if (receipt.at("success").get<bool>()) return receipt.at("result");
	const auto& error = receipt.at("error");
	return ToolFailure{error.at("error_code").get<std::string>(), error.at("message").get<std::string>(),
		error.value("retryable", false), error.value("details", Json::object())};
}

bool terminal_state(std::string_view state) {
	return state == "completed" || state == "failed" || state == "cancelled";
}

bool wait_complete_state(std::string_view state) {
	return terminal_state(state) || state == "paused";
}

} // namespace

DurableJobWorkflow::DurableJobWorkflow(
	fs::path workspace_root, std::string workspace_id, DurableJobStepExecutor executor)
	: workspace_root_(fs::absolute(workspace_root).lexically_normal()),
	  state_directory_(workspace_root_ / ".vibris" / "jobs"),
	  workspace_id_(std::move(workspace_id)), executor_(std::move(executor)) {
	if (!detail::is_uuid(workspace_id_) || !executor_) {
		throw std::invalid_argument("invalid durable job workflow configuration");
	}
}

DurableJobWorkflow::~DurableJobWorkflow() {
	shutdown();
}

DurableJobWorkflow::Record DurableJobWorkflow::create_record(
	std::string_view tool_name, const Json& supplied_arguments, const JobContext& config) const {
	const auto job_id = detail::generate_uuid();
	const auto execution_mode = supplied_arguments.value("execution", std::string("sync"));
	auto arguments = supplied_arguments;
	arguments.erase("execution");
	arguments = freeze_arguments(workspace_root_, state_directory_, job_id, std::move(arguments));
	auto steps = plan_steps(tool_name, arguments, job_id, config.default_warmup_frames);
	for (auto& step : steps) step["arguments"]["__vibris_workflow_id"] = job_id;
	if (steps.empty() || steps.size() > maximum_steps) {
		throw StateError("INVALID_JOB", "A durable job must contain between 1 and 4096 steps.");
	}
	const auto created = unix_ms();
	Json request{{"schema_version", durable_schema_version}, {"workspace_id", workspace_id_}, {"job_id", job_id},
		{"kind", arguments.value("recipe", std::string(tool_name))}, {"tool_name", tool_name},
		{"created_unix_ms", created}, {"config", stored_config(config)},
		{"arguments", std::move(arguments)}, {"steps", std::move(steps)}};
	Json state{{"schema_version", durable_schema_version}, {"workspace_id", workspace_id_}, {"job_id", job_id},
		{"kind", request.at("kind")}, {"workflow_state", "queued"}, {"stage", "queued"},
		{"next_step", 0}, {"completed_steps", 0}, {"total_steps", request.at("steps").size()},
		{"current_step", nullptr}, {"current_request_id", nullptr}, {"current_request_accepted", false},
		{"restart_resubmissions", 0},
		{"cancel_requested", false}, {"last_error", nullptr}, {"event_sequence", 0},
		{"execution_mode", execution_mode},
		{"created_unix_ms", created}, {"updated_unix_ms", created}, {"step_started_unix_ms", nullptr},
		{"duration_samples_ms", Json::array()}, {"eta_ms", nullptr}};
	return {std::move(request), std::move(state)};
}

DurableJobWorkflow::Record DurableJobWorkflow::load(std::string_view job_id) const {
	if (!detail::is_uuid(job_id)) invalid_job();
	std::scoped_lock lock(store_mutex_);
	try {
		const auto root = state_directory_ / std::string(job_id);
		auto request = Json::parse(read_file(root / "request.json"));
		auto state = Json::parse(read_file(root / "state.json"));
		if (!request.is_object() || !state.is_object()) invalid_job();
		if (request.value("schema_version", 0) != durable_schema_version ||
			state.value("schema_version", 0) != durable_schema_version) {
			throw StateError("UNSUPPORTED_VERSION", "Only durable job schema version 3 is supported.");
		}
		if (request.value("workspace_id", std::string{}) != workspace_id_ ||
			state.value("workspace_id", std::string{}) != workspace_id_ ||
			request.value("job_id", std::string{}) != job_id || state.value("job_id", std::string{}) != job_id ||
			!request.contains("steps") || !request.at("steps").is_array() || request.at("steps").empty() ||
			request.at("steps").size() > maximum_steps ||
			state.value("total_steps", std::size_t{}) != request.at("steps").size() ||
			state.value("next_step", maximum_steps + 1) > request.at("steps").size()) invalid_job();
		static_cast<void>(parsed_config(request.at("config"), workspace_id_));
		const auto event_path = root / "events.jsonl";
		std::error_code event_error;
		if (fs::exists(event_path, event_error)) {
			std::istringstream input(read_file(event_path, true));
			std::string line;
			std::uint64_t tail = 0;
			while (std::getline(input, line)) {
				if (line.empty()) continue;
				const auto sequence = Json::parse(line).at("sequence").get<std::uint64_t>();
				if (sequence <= tail) invalid_job();
				tail = sequence;
			}
			state["event_sequence"] = std::max(state.value("event_sequence", std::uint64_t{}), tail);
		}
		return {std::move(request), std::move(state)};
	} catch (const StateError&) {
		throw;
	} catch (const Json::exception&) {
		invalid_job();
	}
}

void DurableJobWorkflow::save_state(const Json& state) const {
	const auto job_id = state.value("job_id", std::string{});
	if (!detail::is_uuid(job_id) || state.value("workspace_id", std::string{}) != workspace_id_) invalid_job();
	auto text = state.dump(2);
	if (text.size() > maximum_document_bytes) checkpoint_error("Durable job state exceeded 64 MiB.");
	text.push_back('\n');
	{
		std::scoped_lock lock(store_mutex_);
		atomic_write(state_directory_ / job_id / "state.json", std::move(text), true);
	}
	state_generation_.fetch_add(1, std::memory_order_release);
	state_changed_.notify_all();
}

void DurableJobWorkflow::publish_request(const Json& request) const {
	auto text = request.dump(2);
	text.push_back('\n');
	std::scoped_lock lock(store_mutex_);
	atomic_write(state_directory_ / request.at("job_id").get<std::string>() / "request.json",
		std::move(text), false);
}

void DurableJobWorkflow::append_event(Json& state, std::string_view type, std::string_view stage,
	const Json& step, std::string_view request_id, const bool accepted) const {
	const auto sequence = state.value("event_sequence", std::uint64_t{}) + 1;
	Json event{{"sequence", sequence}, {"unix_ms", unix_ms()}, {"type", type}, {"stage", stage},
		{"workflow_state", state.at("workflow_state")}, {"step", step}, {"accepted", accepted}};
	if (!request_id.empty()) event["request_id"] = request_id;
	auto line = event.dump();
	line.push_back('\n');
	{
		std::scoped_lock lock(store_mutex_);
		append_line(state_directory_ / state.at("job_id").get<std::string>() / "events.jsonl", std::move(line));
	}
	state["event_sequence"] = sequence;
	state["updated_unix_ms"] = unix_ms();
}

Json DurableJobWorkflow::events(std::string_view job_id, const std::uint64_t cursor) const {
	Json result = Json::array();
	const auto path = state_directory_ / std::string(job_id) / "events.jsonl";
	std::error_code error;
	if (!fs::exists(path, error)) return result;
	std::scoped_lock lock(store_mutex_);
	std::istringstream input(read_file(path, true));
	std::string line;
	std::uint64_t previous = 0;
	while (std::getline(input, line)) {
		if (line.empty()) continue;
		auto event = Json::parse(line);
		const auto sequence = event.at("sequence").get<std::uint64_t>();
		if (sequence <= previous) invalid_job();
		previous = sequence;
		if (sequence > cursor) result.push_back(std::move(event));
	}
	return result;
}

std::optional<Json> DurableJobWorkflow::load_receipt(
	std::string_view job_id, const std::size_t index) const {
	const auto path = state_directory_ / std::string(job_id) / "receipts" / receipt_name(index);
	std::error_code error;
	if (!fs::exists(path, error)) return std::nullopt;
	std::scoped_lock lock(store_mutex_);
	return Json::parse(read_file(path));
}

void DurableJobWorkflow::publish_receipt(
	std::string_view job_id, const std::size_t index, const Json& receipt) const {
	auto text = receipt.dump(2);
	text.push_back('\n');
	std::scoped_lock lock(store_mutex_);
	atomic_write(state_directory_ / std::string(job_id) / "receipts" / receipt_name(index),
		std::move(text), false);
}

void DurableJobWorkflow::publish_result(std::string_view job_id, const Json& result) const {
	auto text = result.dump(2);
	text.push_back('\n');
	std::scoped_lock lock(store_mutex_);
	const auto path = state_directory_ / std::string(job_id) / "result.json";
	std::error_code error;
	if (fs::exists(path, error)) {
		if (Json::parse(read_file(path)) != result) invalid_job();
		return;
	}
	atomic_write(path, std::move(text), false);
}

bool DurableJobWorkflow::finalization_resume_safe(const Record& record) const {
	try {
		const auto& state = record.state;
		const auto& steps = record.request.at("steps");
		const auto total = steps.size();
		if (state.at("workflow_state") != "paused" ||
			state.at("next_step").get<std::size_t>() != total ||
			state.at("completed_steps").get<std::size_t>() != total ||
			state.at("total_steps").get<std::size_t>() != total ||
			!state.at("current_step").is_null() || !state.at("current_request_id").is_null() ||
			state.at("current_request_accepted").get<bool>()) return false;

		const auto job_id = record.request.at("job_id").get<std::string>();
		std::error_code result_error;
		if (fs::exists(state_directory_ / job_id / "result.json", result_error) || result_error) return false;
		for (std::size_t index = 0; index < total; ++index) {
			const auto receipt = load_receipt(job_id, index);
			if (!receipt || !receipt->is_object() ||
				receipt->value("schema_version", 0) != durable_schema_version ||
				receipt->value("job_id", std::string{}) != job_id ||
				receipt->value("step_index", total) != index ||
				receipt->value("step_id", std::string{}) != steps.at(index).at("id").get<std::string>() ||
				!receipt->contains("success") || !receipt->at("success").is_boolean() ||
				!receipt->at("success").get<bool>() || !receipt->contains("result")) return false;
		}
		return true;
	} catch (...) {
		return false;
	}
}

Json DurableJobWorkflow::final_result(const Record& record) const {
	const auto& request = record.request;
	const auto& state = record.state;
	Json receipts = Json::array();
	for (std::size_t index = 0; index < state.at("completed_steps").get<std::size_t>(); ++index) {
		const auto receipt = load_receipt(request.at("job_id").get<std::string>(), index);
		if (!receipt) invalid_job();
		receipts.push_back(*receipt);
	}
	const auto kind = request.at("kind").get<std::string>();
	if (kind == "benchmark_ab") {
		std::size_t next = 0;
		const auto outcome = run_paired_benchmark(request.at("arguments"), request.at("job_id").get<std::string>(),
			parsed_config(request.at("config"), workspace_id_).default_warmup_frames,
			[&](const Json&) { return receipt_outcome(receipts.at(next++)); },
			request.at("arguments").contains("visual")
				? PairedVisualExecutor([&](const Json&) { return receipt_outcome(receipts.at(next++)); })
				: PairedVisualExecutor{});
		return std::get<Json>(outcome);
	}
	if (kind == "profile_matrix") {
		Json cases = Json::array();
		Json artifacts = Json::array();
		std::size_t passed = 0;
		std::size_t failed = 0;
		for (const auto& receipt : receipts) {
			const auto outcome = receipt_outcome(receipt);
			if (const auto* value = std::get_if<Json>(&outcome)) {
				for (const auto& item : value->value("cases", Json::array())) {
					if (item.value("status", std::string{}) == "passed") ++passed;
					else ++failed;
					cases.push_back(item);
				}
				for (const auto& item : value->value("artifacts", Json::array())) artifacts.push_back(item);
			} else ++failed;
		}
		return {{"success", failed == 0 && passed == request.at("steps").size()}, {"kind", kind},
			{"job_id", request.at("job_id")}, {"status", failed == 0 ? "completed" : "completed_with_failures"},
			{"requested_cases", request.at("steps").size()}, {"completed_cases", receipts.size()},
			{"passed", passed}, {"failed", failed}, {"cases", std::move(cases)},
			{"artifacts", std::move(artifacts)}};
	}
	if (kind == "compile_validate") {
		Json cases = Json::array();
		Json artifacts = Json::array();
		std::size_t passed = 0;
		for (const auto& receipt : receipts) {
			const auto outcome = receipt_outcome(receipt);
			if (const auto* value = std::get_if<Json>(&outcome)) {
				for (const auto& item : value->value("cases", Json::array())) {
					if (item.value("status", std::string{}) == "passed") ++passed;
					cases.push_back(item);
				}
				for (const auto& item : value->value("artifacts", Json::array())) artifacts.push_back(item);
			}
		}
		const auto failures = request.at("steps").size() - passed;
		return {{"success", failures == 0}, {"kind", kind},
			{"job_id", request.at("job_id")}, {"status", failures == 0 ? "completed" : "completed_with_failures"},
			{"requested_cases", request.at("steps").size()}, {"completed_cases", receipts.size()},
			{"passed", passed}, {"failed", failures}, {"cases", std::move(cases)},
			{"artifacts", std::move(artifacts)}};
	}
	if (request.at("tool_name") == "vibris_run_matrix") {
		Json cases = Json::array();
		Json artifacts = Json::array();
		bool success = true;
		for (const auto& receipt : receipts) {
			success = success && receipt.at("success").get<bool>();
			if (!receipt.at("success").get<bool>()) continue;
			const auto& value = receipt.at("result");
			success = success && value.value("success", true);
			for (const auto& item : value.value("cases", Json::array())) cases.push_back(item);
			for (const auto& item : value.value("artifacts", Json::array())) artifacts.push_back(item);
		}
		return {{"success", success}, {"kind", "matrix"}, {"job_id", request.at("job_id")},
			{"status", success ? "completed" : "completed_with_failures"},
			{"cases", std::move(cases)}, {"artifacts", std::move(artifacts)}};
	}
	if (receipts.size() == 1 && receipts.front().at("success").get<bool>()) {
		auto result = receipts.front().at("result");
		if (result.is_object()) result["job_id"] = request.at("job_id");
		return result;
	}
	Json results = Json::array();
	bool success = true;
	for (const auto& receipt : receipts) {
		success = success && receipt.at("success").get<bool>() &&
			(!receipt.at("result").is_object() || receipt.at("result").value("success", true));
		results.push_back(receipt);
	}
	return {{"success", success}, {"kind", kind}, {"job_id", request.at("job_id")},
		{"status", "completed"}, {"results", std::move(results)}};
}

Json DurableJobWorkflow::snapshot(
	const Record& record, const std::uint64_t event_cursor, const bool include_result) const {
	const auto& state = record.state;
	const auto workflow_state = state.at("workflow_state").get<std::string>();
	const bool retryable_pause = workflow_state == "paused" &&
		(state.at("current_request_accepted").get<bool>() ||
			(state.at("last_error").is_object() && state.at("last_error").value("retryable", false)));
	const bool finalization_resume = workflow_state == "paused" && finalization_resume_safe(record);
	Json result{{"schema_version", durable_schema_version}, {"job_id", state.at("job_id")}, {"kind", state.at("kind")},
		{"workflow_state", workflow_state}, {"stage", state.at("stage")},
		{"resumable", retryable_pause || finalization_resume || workflow_state == "cancelled"},
		{"cancelable", (workflow_state == "queued" || workflow_state == "running") &&
			state.value("execution_mode", std::string("sync")) == "async"},
		{"progress", {{"completed_steps", state.at("completed_steps")}, {"total_steps", state.at("total_steps")},
			{"current_step", state.at("current_step")}, {"eta_ms", state.at("eta_ms")}}},
		{"current_request_id", state.at("current_request_id")},
		{"current_request_accepted", state.at("current_request_accepted")},
		{"last_error", state.at("last_error")}, {"event_cursor", state.at("event_sequence")},
		{"events", events(state.at("job_id").get<std::string>(), event_cursor)}};
	if (include_result && (workflow_state == "completed" || workflow_state == "failed")) {
		auto durable_result = Json::parse(read_file(
			state_directory_ / state.at("job_id").get<std::string>() / "result.json"));
		refresh_artifact_expiry(durable_result);
		result["result"] = std::move(durable_result);
	}
	if (workflow_state == "queued" || workflow_state == "running") {
		result["next_action"] = {{"tool", "vibris_job"},
			{"arguments", {{"worktree_root", workspace_root_.string()}, {"operation", "wait"},
				{"job_id", state.at("job_id")}, {"timeout_ms", 300'000}}},
			{"instruction", "Call this blocking wait once. Never poll with query or shell sleep."}};
	} else if ((workflow_state == "paused" || workflow_state == "cancelled") && result.at("resumable").get<bool>()) {
		result["next_action"] = {{"tool", "vibris_job"},
			{"arguments", {{"worktree_root", workspace_root_.string()}, {"operation", "resume"},
				{"job_id", state.at("job_id")}}}};
	}
	return result;
}

ToolOutcome DurableJobWorkflow::start(
	std::string_view tool_name, const Json& arguments, const JobContext& config) {
	reap_finished();
	{
		std::scoped_lock lock(worker_mutex_);
		if (worker_running_) return ToolFailure{"DURABLE_WORKFLOW_BUSY",
			"This MCP process already has a durable workflow worker for the worktree; wait once with "
			"vibris_job operation=wait and never poll it with query or shell sleep.", true,
			{{"job_id", active_job_id_}}};
	}
	std::string job_id;
	{
		WorkspaceJobsLock workspace_lock(state_directory_);
		const auto cleanup_warnings = prune_inactive_jobs(state_directory_, workspace_id_);
		auto record = create_record(tool_name, arguments, config);
		job_id = record.request.at("job_id").get<std::string>();
		publish_request(record.request);
		append_event(record.state, "created", "queued");
		for (const auto& warning : cleanup_warnings) {
			append_event(record.state, "cleanup_warning", "queued", {{"message", warning}});
		}
		save_state(record.state);
	}
	return begin(job_id, arguments.value("execution", std::string("sync")) == "async");
}

ToolOutcome DurableJobWorkflow::control(const Json& arguments) {
	reap_finished();
	const auto operation = arguments.at("operation").get<std::string>();
	const auto job_id = arguments.at("job_id").get<std::string>();
	const auto cursor = arguments.value("event_cursor", std::uint64_t{});
	if (operation == "query") return snapshot(load(job_id), cursor, false);
	if (operation == "wait") {
		const auto timeout = std::chrono::milliseconds(arguments.value("timeout_ms", 300'000));
		const auto deadline = std::chrono::steady_clock::now() + timeout;
		while (true) {
			const auto observed_generation = state_generation_.load(std::memory_order_acquire);
			auto record = load(job_id);
			const auto state = record.state.at("workflow_state").get<std::string>();
			if (wait_complete_state(state)) {
				auto value = snapshot(record, cursor, state == "completed" || state == "failed");
				value.erase("events");
				value["wait_timed_out"] = false;
				return value;
			}
			if (std::chrono::steady_clock::now() >= deadline) {
				auto value = snapshot(record, cursor, false);
				value.erase("events");
				value["wait_timed_out"] = true;
				return value;
			}
			std::unique_lock lock(wait_mutex_);
			state_changed_.wait_until(lock, deadline, [&] {
				return state_generation_.load(std::memory_order_acquire) != observed_generation;
			});
		}
	}
	if (operation == "result") {
		auto record = load(job_id);
		if (record.state.at("workflow_state") == "cancelled") {
			auto value = snapshot(record, cursor, false);
			Json receipts = Json::array();
			for (std::size_t index = 0;
				index < record.state.at("completed_steps").get<std::size_t>(); ++index) {
				if (const auto receipt = load_receipt(job_id, index)) receipts.push_back(*receipt);
			}
			value["result"] = {{"success", false}, {"kind", record.request.at("kind")},
				{"job_id", job_id}, {"status", "cancelled"},
				{"completed_steps", record.state.at("completed_steps")},
				{"total_steps", record.state.at("total_steps")}, {"receipts", std::move(receipts)}};
			return value;
		}
		if (record.state.at("workflow_state") != "completed" &&
			record.state.at("workflow_state") != "failed") {
			return ToolFailure{"JOB_NOT_TERMINAL", "The durable job has not completed.", true,
				{{"job_id", job_id}, {"workflow_state", record.state.at("workflow_state")}}};
		}
		return snapshot(record, cursor, true);
	}
	if (operation == "cancel") {
		std::jthread cancelling;
		{
			std::scoped_lock lock(worker_mutex_);
			if (worker_running_ && active_job_id_ == job_id && !worker_.joinable()) {
				return ToolFailure{"DURABLE_WORKFLOW_BUSY",
					"A synchronous durable job cannot be cancelled from another request.", true,
					{{"job_id", job_id}}};
			}
			if (worker_running_ && active_job_id_ == job_id && worker_.joinable()) {
				worker_.request_stop();
				cancelling = std::move(worker_);
			}
		}
		if (cancelling.joinable()) cancelling.join();
		auto record = load(job_id);
		if (!terminal_state(record.state.at("workflow_state").get<std::string>())) {
			record.state["workflow_state"] = "cancelled";
			record.state["stage"] = "cancelled";
			record.state["cancel_requested"] = true;
			append_event(record.state, "cancelled", "cancelled", record.state.at("current_step"),
				record.state.at("current_request_id").is_string()
					? record.state.at("current_request_id").get<std::string>() : std::string{},
				record.state.at("current_request_accepted").get<bool>());
			save_state(record.state);
		}
		return snapshot(record, cursor, false);
	}
	if (operation != "resume") invalid_job();
	{
		std::scoped_lock lock(worker_mutex_);
		if (worker_running_) return ToolFailure{"DURABLE_WORKFLOW_BUSY",
			"This MCP process already has a durable workflow worker for the worktree; wait once with "
			"vibris_job operation=wait, or cancel it before resuming another workflow.", true,
			{{"job_id", active_job_id_}}};
	}
	{
		WorkspaceJobsLock workspace_lock(state_directory_);
		auto record = load(job_id);
		const auto state = record.state.at("workflow_state").get<std::string>();
		if (state == "completed") return snapshot(record, cursor, true);
		if (state == "failed") {
			return ToolFailure{"JOB_NOT_RESUMABLE", "The durable job has failed terminally.", false,
				{{"job_id", job_id}, {"workflow_state", state}}};
		}
		if (state != "paused" && state != "cancelled") {
			return ToolFailure{"JOB_NOT_RESUMABLE", "The durable job is not paused or cancelled.", false,
				{{"job_id", job_id}, {"workflow_state", state}}};
		}
		const bool retryable = state == "cancelled" || record.state.at("current_request_accepted").get<bool>() ||
			finalization_resume_safe(record) ||
			(record.state.at("last_error").is_object() &&
				record.state.at("last_error").value("retryable", false));
		if (!retryable) {
			return ToolFailure{"JOB_NOT_RESUMABLE",
				"The durable job has no safe retry or accepted request to resume.", false,
				{{"job_id", job_id}, {"workflow_state", state}}};
		}
		record.state["workflow_state"] = "queued";
		record.state["stage"] = "queued";
		record.state["cancel_requested"] = false;
		record.state["last_error"] = nullptr;
		record.state["restart_resubmissions"] = 0;
		record.state["execution_mode"] = "async";
		record.state["step_started_unix_ms"] = nullptr;
		append_event(record.state, "resumed", "queued", record.state.at("current_step"));
		save_state(record.state);
	}
	return begin(job_id, true);
}

Json DurableJobWorkflow::active_status() const {
	std::string job_id;
	{
		std::scoped_lock lock(worker_mutex_);
		if (!worker_running_) return {{"active", false}};
		job_id = active_job_id_;
	}
	auto value = snapshot(load(job_id), 0, false);
	value["active"] = true;
	return value;
}

bool DurableJobWorkflow::running() const {
	std::scoped_lock lock(worker_mutex_);
	return worker_running_;
}

ToolOutcome DurableJobWorkflow::begin(std::string job_id, const bool asynchronous) {
	{
		std::scoped_lock lock(worker_mutex_);
		if (worker_running_) return ToolFailure{"DURABLE_WORKFLOW_BUSY",
			"This MCP process already has a durable workflow worker for the worktree; wait once with "
			"vibris_job operation=wait and never poll it with query or shell sleep.", true,
			{{"job_id", active_job_id_}}};
		worker_running_ = true;
		active_job_id_ = job_id;
	}
	if (asynchronous) {
		worker_ = std::jthread([this, job_id](const std::stop_token stop) { execute(job_id, stop); });
		return snapshot(load(job_id), 0, false);
	}
	execute(job_id, {});
	auto record = load(job_id);
	return record.state.at("workflow_state") == "completed" ||
		record.state.at("workflow_state") == "failed"
		? ToolOutcome(snapshot(record, 0, true)) : ToolOutcome(snapshot(record, 0, false));
}

void DurableJobWorkflow::execute(std::string job_id, const std::stop_token stop) noexcept {
	try {
		auto record = load(job_id);
		auto& state = record.state;
		state["workflow_state"] = "running";
		state["stage"] = "running";
		append_event(state, "started", "running", state.at("current_step"));
		save_state(state);
		const auto config = parsed_config(record.request.at("config"), workspace_id_);
		const auto& steps = record.request.at("steps");
		while (state.at("next_step").get<std::size_t>() < steps.size()) {
			const auto index = state.at("next_step").get<std::size_t>();
			const auto& step = steps.at(index);
			if (const auto receipt = load_receipt(job_id, index)) {
				state["next_step"] = index + 1;
				state["completed_steps"] = index + 1;
				state["current_step"] = nullptr;
				state["current_request_id"] = nullptr;
				state["current_request_accepted"] = false;
				state["restart_resubmissions"] = 0;
				state["step_started_unix_ms"] = nullptr;
				append_event(state, "receipt_recovered", "checkpointed", step);
				save_state(state);
				continue;
			}
			if (stop.stop_requested()) {
				state["workflow_state"] = "cancelled";
				state["stage"] = "cancelled";
				state["cancel_requested"] = true;
				append_event(state, "cancelled", "cancelled", step);
				save_state(state);
				finish_active(job_id);
				return;
			}
			state["current_step"] = index;
			state["stage"] = "executing";
			if (state.at("step_started_unix_ms").is_null()) state["step_started_unix_ms"] = unix_ms();
			append_event(state, "step_started", "executing", step,
				state.at("current_request_id").is_string()
					? state.at("current_request_id").get<std::string>() : std::string{},
				state.at("current_request_accepted").get<bool>());
			save_state(state);
			DurableJobStepExecution execution{
				.tool_name = step.at("tool_name").get<std::string>(),
				.arguments = step.at("arguments"),
				.config = config,
				.resume_request_id = state.at("current_request_accepted").get<bool>() &&
					state.at("current_request_id").is_string()
						? std::optional<std::string>(state.at("current_request_id").get<std::string>())
						: std::nullopt,
				.stop = stop,
			};
			execution.progress = [this, &state, &step](std::string_view request_id,
				std::string_view stage, const bool accepted) {
				if (!request_id.empty()) state["current_request_id"] = request_id;
				state["current_request_accepted"] = accepted;
				state["stage"] = stage;
				append_event(state, "progress", stage, step, request_id, accepted);
				save_state(state);
			};
			auto outcome = executor_(std::move(execution));
			std::optional<ToolFailure> reported_failure;
			const Json* child_result = std::get_if<Json>(&outcome);
			const bool child_terminal = child_result != nullptr;
			if (const auto* failure = std::get_if<ToolFailure>(&outcome)) {
				reported_failure = *failure;
			} else {
				reported_failure = semantic_failure(*child_result);
			}
			if (reported_failure) {
				const auto& failure = *reported_failure;
				if (stop.stop_requested() || failure.code == "CANCELLED") {
					state["workflow_state"] = "cancelled";
					state["stage"] = "cancelled";
					state["cancel_requested"] = true;
					state["last_error"] = failure_json(failure);
					append_event(state, "cancelled", "cancelled", step,
						state.at("current_request_id").is_string()
							? state.at("current_request_id").get<std::string>() : std::string{},
						state.at("current_request_accepted").get<bool>());
					save_state(state);
					finish_active(job_id);
					return;
				}
				std::string normalized_code = failure.code;
				std::ranges::transform(normalized_code, normalized_code.begin(), [](const unsigned char value) {
					return static_cast<char>(std::tolower(value));
				});
				if (failure.retryable && normalized_code == "server_restarted" &&
					state.value("restart_resubmissions", 0) == 0) {
					state["current_request_id"] = nullptr;
					state["current_request_accepted"] = false;
					state["restart_resubmissions"] = 1;
					state["last_error"] = failure_json(failure);
					append_event(state, "server_restart_resubmit", "retrying", step);
					save_state(state);
					continue;
				}
				if (failure.retryable) {
					state["workflow_state"] = "paused";
					state["stage"] = "paused";
					state["last_error"] = failure_json(failure);
					if (child_terminal) {
						state["current_request_id"] = nullptr;
						state["current_request_accepted"] = false;
						state["step_started_unix_ms"] = nullptr;
					} else if (!state.at("current_request_accepted").get<bool>()) {
						state["current_request_id"] = nullptr;
					}
					append_event(state, "interrupted", "paused", step,
						state.at("current_request_id").is_string()
							? state.at("current_request_id").get<std::string>() : std::string{},
						state.at("current_request_accepted").get<bool>());
					save_state(state);
					finish_active(job_id);
					return;
				}

				Json receipt{{"schema_version", durable_schema_version}, {"job_id", job_id}, {"step_index", index},
					{"step_id", step.at("id")}, {"success", false},
					{"result", child_result == nullptr ? Json(nullptr) : *child_result},
					{"error", failure_json(failure)}, {"completed_unix_ms", unix_ms()}};
				publish_receipt(job_id, index, receipt);
				Json receipts = Json::array();
				for (std::size_t receipt_index = 0; receipt_index <= index; ++receipt_index) {
					if (const auto stored = load_receipt(job_id, receipt_index)) receipts.push_back(*stored);
				}
				Json result{{"success", false}, {"kind", record.request.at("kind")},
					{"job_id", job_id}, {"status", "failed"}, {"completed_steps", index},
					{"total_steps", steps.size()},
					{"failed_step", {{"index", index}, {"id", step.at("id")}, {"kind", step.at("kind")}}},
					{"error", failure_json(failure)}, {"receipts", std::move(receipts)}};
				if (child_result != nullptr) result["child_result"] = *child_result;
				publish_result(job_id, result);
				state["workflow_state"] = "failed";
				state["stage"] = "failed";
				state["last_error"] = failure_json(failure);
				state["current_request_accepted"] = false;
				state["eta_ms"] = nullptr;
				append_event(state, "failed", "failed", step,
					state.at("current_request_id").is_string()
						? state.at("current_request_id").get<std::string>() : std::string{}, false);
				save_state(state);
				finish_active(job_id);
				return;
			}
			Json receipt{{"schema_version", durable_schema_version}, {"job_id", job_id}, {"step_index", index},
				{"step_id", step.at("id")}, {"success", true}, {"result", std::get<Json>(outcome)},
				{"error", nullptr}, {"completed_unix_ms", unix_ms()}};
			publish_receipt(job_id, index, receipt);
			const auto duration = unix_ms() - state.at("step_started_unix_ms").get<std::int64_t>();
			state["duration_samples_ms"].push_back(std::max<std::int64_t>(0, duration));
			state["next_step"] = index + 1;
			state["completed_steps"] = index + 1;
			state["current_step"] = nullptr;
			state["current_request_id"] = nullptr;
			state["current_request_accepted"] = false;
			state["restart_resubmissions"] = 0;
			state["step_started_unix_ms"] = nullptr;
			state["last_error"] = nullptr;
			state["stage"] = "checkpointed";
			if (state.at("duration_samples_ms").size() >= 2) {
				const auto& samples = state.at("duration_samples_ms");
				const auto total = std::accumulate(samples.begin(), samples.end(), std::int64_t{},
					[](const std::int64_t sum, const Json& value) { return sum + value.get<std::int64_t>(); });
				state["eta_ms"] = total / static_cast<std::int64_t>(samples.size()) *
					static_cast<std::int64_t>(steps.size() - index - 1);
			}
			append_event(state, "step_checkpointed", "checkpointed", step);
			save_state(state);
		}
		state["stage"] = "finalizing";
		append_event(state, "finalizing", "finalizing");
		save_state(state);
		const auto result = final_result(record);
		publish_result(job_id, result);
		state["workflow_state"] = "completed";
		state["stage"] = "completed";
		state["eta_ms"] = 0;
		append_event(state, "completed", "completed");
		save_state(state);
	} catch (const StateError& error) {
		try {
			auto record = load(job_id);
			record.state["workflow_state"] = "paused";
			record.state["stage"] = "paused";
			record.state["last_error"] = failure_json(
				ToolFailure{std::string(error.code()), error.what(), error.retryable()});
			append_event(record.state, "failed", "paused", record.state.at("current_step"));
			save_state(record.state);
		} catch (...) {}
	} catch (const std::exception& error) {
		try {
			auto record = load(job_id);
			record.state["workflow_state"] = "paused";
			record.state["stage"] = "paused";
			record.state["last_error"] = failure_json(ToolFailure{"INTERNAL_ERROR", error.what(), false});
			append_event(record.state, "failed", "paused", record.state.at("current_step"));
			save_state(record.state);
		} catch (...) {}
	}
	finish_active(job_id);
}

void DurableJobWorkflow::finish_active(std::string_view job_id) noexcept {
	std::scoped_lock lock(worker_mutex_);
	if (active_job_id_ == job_id) {
		worker_running_ = false;
		active_job_id_.clear();
	}
}

void DurableJobWorkflow::reap_finished() {
	std::jthread completed;
	{
		std::scoped_lock lock(worker_mutex_);
		if (!worker_running_ && worker_.joinable()) completed = std::move(worker_);
	}
	if (completed.joinable()) completed.join();
}

void DurableJobWorkflow::shutdown() {
	std::jthread active;
	{
		std::scoped_lock lock(worker_mutex_);
		if (worker_.joinable()) {
			worker_.request_stop();
			active = std::move(worker_);
		}
	}
	if (active.joinable()) active.join();
}

} // namespace vibris::mcp
