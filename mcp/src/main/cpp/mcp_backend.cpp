#include "mcp_backend.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <future>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>

#include "config_document.hpp"
#include "name_suggestions.hpp"
#include "artifact_prune.hpp"
#include "preset_previews.hpp"
#include "image_compare.hpp"
#include "profile_matrix_workflow.hpp"
#include "result_mapper.hpp"
#include "scene_context_resolver.hpp"
#include "job_context.hpp"
#include "nsight_analyzer.hpp"
#include "source_handler.hpp"
#include "state_error.hpp"
#include "synchronous_job_runner.hpp"
#include "workspace_artifact_link.hpp"
#include "workspace_binding.hpp"
#include "workspace_identity_store.hpp"

namespace vibris::mcp {
namespace {

namespace control = vibris::control::v2;
namespace fs = std::filesystem;
constexpr std::size_t pending_limit = 256;
constexpr auto unary_wait = std::chrono::minutes(5);

std::string bounded(std::string value) {
    constexpr std::size_t limit = 512;
    if (value.size() > limit) value.resize(limit);
    return value;
}

Json scene_json(const control::SceneContext& context) {
    return {{"save_id", context.save_id()},
            {"dimension_id", context.dimension_id()},
            {"time_preset_id", context.time_preset_id()},
            {"weather_preset_id", context.weather_preset_id()},
            {"camera_preset_id", context.camera_preset_id()},
            {"fov", context.fov()},
            {"resolution", {{"width", context.resolution().width()},
                            {"height", context.resolution().height()}}},
            {"settings_preset_id", context.settings_preset_id()}};
}

control::SceneContext scene_from_json(const Json& value) {
    control::SceneContext context;
    context.set_save_id(value.at("save_id").get<std::string>());
    context.set_dimension_id(value.at("dimension_id").get<std::string>());
    context.set_time_preset_id(value.at("time_preset_id").get<std::string>());
    context.set_weather_preset_id(value.at("weather_preset_id").get<std::string>());
    context.set_camera_preset_id(value.at("camera_preset_id").get<std::string>());
    context.set_fov(value.at("fov").get<double>());
    context.mutable_resolution()->set_width(value.at("resolution").at("width").get<std::uint32_t>());
    context.mutable_resolution()->set_height(value.at("resolution").at("height").get<std::uint32_t>());
    context.set_settings_preset_id(value.at("settings_preset_id").get<std::string>());
    return context;
}

Json preset_json(const control::ScenePreset& preset) {
    Json tags = Json::array();
    for (const auto& tag : preset.tags()) tags.push_back(tag);
    return {{"preset_id", preset.preset_id()},
            {"version", preset.version()},
            {"display_name", preset.display_name()},
            {"tags", std::move(tags)},
            {"preset_sha256", preset.preset_sha256()}};
}

JobContext config_from_preset(const control::ScenePreset& preset, std::string workspace_id) {
    JobContext config;
    config.workspace_id = std::move(workspace_id);
    config.save_id = preset.context().save_id();
    config.dimension_id = preset.context().dimension_id();
    config.time_preset_id = preset.context().time_preset_id();
    config.camera_preset_id = preset.context().camera_preset_id();
    config.fov = preset.context().fov();
    return config;
}

bool has_all_tags(const Json& preset, const Json& requested) {
    const auto tags = preset.find("tags");
    if (tags == preset.end() || !tags->is_array()) return requested.empty();
    return std::all_of(requested.begin(), requested.end(), [&](const Json& requested_tag) {
        const auto expected = requested_tag.get<std::string>();
        return std::any_of(tags->begin(), tags->end(), [&](const Json& tag) {
            return tag.is_string() && tag.get<std::string>() == expected;
        });
    });
}

void merge_stats(GrpcClientStats& aggregate, const GrpcClientStats& current) {
    aggregate.completion_queue_count += current.completion_queue_count;
    aggregate.peak_pending_requests = std::max(aggregate.peak_pending_requests, current.peak_pending_requests);
    aggregate.pending_requests += current.pending_requests;
    aggregate.worker_threads_started += current.worker_threads_started;
    aggregate.worker_threads_joined += current.worker_threads_joined;
    aggregate.control_connected = aggregate.control_connected || current.control_connected;
    aggregate.restart_scheduled = aggregate.restart_scheduled || current.restart_scheduled;
    aggregate.restart_retry_after_ms = std::max(
        aggregate.restart_retry_after_ms, current.restart_retry_after_ms);
}

} // namespace

class McpBackend::Impl final {
private:
    class WorkspaceRuntime final {
    public:
        WorkspaceRuntime(WorkspaceBinding binding, std::string workspace_id, std::string server_address)
            : binding_(std::move(binding)),
              workspace_id_(std::move(workspace_id)),
              server_address_(std::move(server_address)),
              process_id_(detail::generate_uuid()),
              source_handler_(binding_.root),
              artifact_link_(binding_.root, workspace_id_),
              jobs_(binding_.root, workspace_id_,
                  [this](DurableJobStepExecution execution) {
                      return run_durable_step(std::move(execution));
                  }) {}

        ToolOutcome dispatch(std::string_view name, const Json& arguments) {
            if (name == "vibris_preview_presets") return preview_presets(arguments);
            if (name == "vibris_compare") return compare_images(binding_.root, arguments);
            if (name == "mcp_vibiris_nsight_analyze") {
                return analyze_nsight_bundle(
                    binding_.root,
                    fs::path(arguments.at("artifact_path").get<std::string>()),
                    arguments.at("query"));
            }
            if (name == "vibris_restart") return restart(arguments);
            if (name == "vibris_list_presets") return list_presets(arguments);
            if (name == "vibris_list_resources") return list_resources(arguments);
            if (name == "vibris_get_status") return get_status(arguments);
            if (name == "vibris_job") return job(arguments);
            if (name == "vibris_artifacts") return artifacts(arguments);
            if (name == "vibris_run_recipe" || name == "vibris_run_actions" || name == "vibris_run_matrix") {
                if (!wait_for_planned_restart()) {
                    return ToolFailure{"SERVER_RESTARTING",
                        "A planned Minecraft restart did not finish within five minutes. Retry this tool call; "
                        "the runtime is restarting, not failed.", true,
                        {{"next_action", "retry"},
                         {"waited_ms", std::chrono::duration_cast<std::chrono::milliseconds>(unary_wait).count()}}};
                }
                const bool durable = arguments.value("execution", std::string("sync")) == "async" ||
                    arguments.contains("presets") ||
                    (name == "vibris_run_recipe" &&
                        (arguments.value("recipe", std::string{}) == "profile_matrix" ||
                         arguments.value("recipe", std::string{}) == "benchmark_ab" ||
                         arguments.value("recipe", std::string{}) == "compile_validate"));
                if (durable) return start_durable_job(name, arguments);
                if (jobs_.running()) {
                    return ToolFailure{"DURABLE_WORKFLOW_BUSY",
                        "This MCP process already has a durable workflow worker for the worktree. "
                        "Wait once with vibris_job operation=wait; never poll it with query or shell sleep. "
                        "Alternatively combine related captures into one "
                        "vibris_run_actions or vibris_run_matrix call. This is not the Core runtime lease gate.",
                        true};
                }
                auto outcome = run_job(name, arguments);
                Json failed;
                auto* result = std::get_if<Json>(&outcome);
                auto* failure = std::get_if<ToolFailure>(&outcome);
                if (failure) {
                    failed = {{"code", failure->code}, {"message", failure->message},
                        {"retryable", failure->retryable}, {"details", failure->details}};
                    result = &failed;
                }
                if (result && result->is_object()) {
                    auto id = failure ? failure->details.value("job_id", std::string{}) : result->value("job_id", std::string{});
                    if (!detail::is_uuid(id) || fs::exists(binding_.root / ".vibris" / "jobs" / id)) id = detail::generate_uuid();
                    const auto directory = managed_output_directory(binding_.root,
                        binding_.root / ".vibris" / "jobs" / id);
                    const auto path = directory / "result.json";
                    const auto temporary = directory / "result.json.tmp";
                    std::ofstream file(temporary, std::ios::binary);
                    file << result->dump();
                    file.close();
                    if (!file) throw StateError("RESULT_WRITE_FAILED", "Cannot persist the full synchronous result.");
                    fs::rename(temporary, path);
                    if (failure) failure->details["full_result_path"] = path.string();
                    else (*result)["full_result_path"] = path.string();
                }
                return outcome;
            }
            return ToolFailure{"INTERNAL_ERROR", "The validated tool was not dispatched.", false};
        }

        void attach_scope(ToolOutcome& outcome) const {
            const Json scope{{"worktree_root", binding_.root.string()}, {"workspace_id", workspace_id_}};
            if (auto* payload = std::get_if<Json>(&outcome); payload != nullptr && payload->is_object()) {
                payload->update(scope);
                return;
            }
            if (auto* failure = std::get_if<ToolFailure>(&outcome)) {
                if (!failure->details.is_object()) failure->details = Json::object();
                failure->details.update(scope);
            }
        }

        void interrupt_waits() { jobs_.interrupt_waits(); }

        std::optional<GrpcClientStats> shutdown() {
            jobs_.shutdown();
            source_handler_.clear();
            release_client();
            if (!used_client_) return std::nullopt;
            return aggregate_;
        }

    private:
        using SceneContinuation =
            std::function<ToolOutcome(const JobContext&, const control::ScenePreset&)>;

        template <typename Response, typename Start, typename Map>
        ToolOutcome unary(Start&& start, Map&& map,
            const std::chrono::milliseconds wait = unary_wait + std::chrono::seconds(1),
            const bool retry_transport = true) {
            const auto deadline = std::chrono::steady_clock::now() + wait;
            while (true) {
                auto completion = std::make_shared<std::promise<std::pair<grpc::Status, Response>>>();
                auto result = completion->get_future();
                const auto accepted = start(
                    [completion](const grpc::Status& status, const Response& response) {
                        completion->set_value({status, response});
                    });
                if (!accepted) return ToolFailure{"QUEUE_FULL", "The bounded gRPC request registry is full.", true};
                if (result.wait_until(deadline) != std::future_status::ready) {
                    return ToolFailure{"SERVER_OFFLINE",
                        "The local Vibris server remained unreachable for five minutes.", true};
                }
                auto [status, response] = result.get();
                if (status.ok()) return std::forward<Map>(map)(response);

                const bool transient_transport =
                    status.error_code() == grpc::StatusCode::UNAVAILABLE ||
                    status.error_code() == grpc::StatusCode::DEADLINE_EXCEEDED ||
                    status.error_code() == grpc::StatusCode::RESOURCE_EXHAUSTED;
                if (retry_transport && transient_transport &&
                    std::chrono::steady_clock::now() < deadline) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                    continue;
                }
                switch (status.error_code()) {
                case grpc::StatusCode::INVALID_ARGUMENT:
                    return ToolFailure{"INVALID_REQUEST", bounded(status.error_message()), false};
                case grpc::StatusCode::PERMISSION_DENIED:
                    return ToolFailure{"WORKSPACE_MISMATCH", bounded(status.error_message()), false};
                case grpc::StatusCode::NOT_FOUND:
                    return ToolFailure{"ARTIFACT_NOT_FOUND", bounded(status.error_message()), false};
                case grpc::StatusCode::FAILED_PRECONDITION:
                    if (status.error_message().find("RESTART_NOT_CONFIGURED") != std::string::npos) {
                        return ToolFailure{"RESTART_NOT_CONFIGURED",
                            "server.json does not define a usable restart_executable.", false};
                    }
                    return ToolFailure{"ARTIFACT_MANIFEST_CHANGED", bounded(status.error_message()), false};
                default:
                    return ToolFailure{"SERVER_OFFLINE", bounded(status.error_message()), true};
                }
            }
        }

        ToolOutcome list_presets(const Json& arguments) {
            control::ListPresetsRequest request;
            if (arguments.contains("preset_id")) request.set_preset_id(arguments.at("preset_id").get<std::string>());
            for (const auto& tag : arguments.value("tags", Json::array())) {
                request.add_tags(tag.get<std::string>());
            }
            return unary<control::ListPresetsResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_presets(request, std::move(completion));
                },
                  [this, &arguments](const auto& response) -> ToolOutcome {
                    auto result = ResultMapper::list_presets(response);
                    attach_preset_thumbnails(binding_.root, result);
                    if (arguments.contains("preset_id") && result.contains("presets") && result["presets"].is_array()) {
                        const auto filter = arguments["preset_id"].get<std::string>();
                        auto& presets = result["presets"];
                        presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                          return preset.value("preset_id", std::string{}) != filter;
                                      }),
                                      presets.end());
                    }
                    if (arguments.contains("tags") && result.contains("presets") &&
                        result["presets"].is_array()) {
                        auto& presets = result["presets"];
                        presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                          return !has_all_tags(preset, arguments["tags"]);
                                      }),
                                      presets.end());
                    }
                    return result;
                });
        }

        ToolOutcome preview_presets(const Json& arguments) {
            Json filter = Json::object();
            if (arguments.contains("tags")) filter["tags"] = arguments["tags"];
            auto listed = list_presets(filter);
            if (const auto* failure = std::get_if<ToolFailure>(&listed)) return *failure;
            auto presets = std::get<Json>(listed).at("presets");
            const auto version = std::get<Json>(listed).value("vibris_version", std::string{});
            if (version.empty()) return ToolFailure{"PREVIEW_VERSION_MISSING", "Runtime did not report its Vibris version.", false};
            if (arguments.contains("preset_ids")) {
                for (const auto& id : arguments["preset_ids"])
                    if (std::none_of(presets.begin(), presets.end(), [&](const Json& preset) { return preset["preset_id"] == id; })) {
                        std::vector<std::string> available;
                        for (const auto& preset : presets) available.push_back(preset.at("preset_id").get<std::string>());
                        return ToolFailure{"INVALID_PRESET", "Requested preview preset was not found.", false,
                            {{"preset_id", id}, {"suggestions", name_suggestions(id.get<std::string>(), available)}}};
                    }
                const auto& ids = arguments["preset_ids"];
                presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                    return std::find(ids.begin(), ids.end(), preset["preset_id"]) == ids.end(); }), presets.end());
            }
            Json preview{{"presets", presets}, {"vibris_version", version}, {"tile_width", arguments.value("tile_width", 384)}};
            Json missing = Json::array();
            for (const auto& preset : presets) if (arguments.value("refresh", false) ||
                !preset_preview_cached(binding_.root, preset, preview["tile_width"].get<int>(), version)) missing.push_back(preset["preset_id"]);
            if (missing.empty()) return finish_preset_previews(binding_.root, preview, Json::array());
            Json matrix{{"presets", missing}, {"execution", arguments.value("execution", std::string("sync"))},
                {"sources", Json::array({{{"id", "workspace"}, {"kind", "workspace"}}})},
                {"configs", Json::array({{{"id", "default"}, {"values", Json::object()}}})},
                {"matrix", {{"sources", Json::array({"workspace"})}, {"configs", Json::array({"default"})}}},
                {"restore_state", {{"on_success", false}, {"on_error", false}}},
                {"actions", Json::array({{{"type", "wait_frames"}, {"frames", 32}},
                    {{"type", "take_screenshot"}, {"artifact_name", "preview"}, {"format", "png"}}})},
                {"__vibris_preview", preview}};
            return start_durable_job("vibris_run_matrix", matrix);
        }

        ToolOutcome with_scene(const Json& arguments, const SceneContinuation& continuation) {
            control::ListPresetsRequest request;
            request.set_preset_id(arguments.at("preset_id").get<std::string>());
            return unary<control::ListPresetsResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_presets(request, std::move(completion));
                },
                [this, &arguments, &continuation](const auto& presets) -> ToolOutcome {
                    const auto preset = SceneContextResolver::resolve_preset(
                        arguments.at("preset_id").get<std::string>(), presets);
                    const auto config = config_from_preset(preset, workspace_id_);
                    detail::validate_config(config);
                    control::ValidateContextRequest request;
                    *request.mutable_context() = preset.context();
                    return unary<control::ValidateContextResponse>(
                        [this, request = std::move(request)](auto completion) mutable {
                            return client().validate_context(request, std::move(completion));
                        },
                        [&config, &preset, &continuation](const auto& response) -> ToolOutcome {
                            if (!response.valid()) {
                                return ToolFailure{"INVALID_PRESET",
                                                   "The Vibris server rejected the scene context.", false,
                                                   ResultMapper::validation(response)};
                            }
                            return continuation(config, preset);
                        });
                });
        }

        ToolOutcome list_resources(const Json& arguments) {
            control::ListResourcesRequest request;
            auto* filter = request.mutable_filter();
            for (const auto& kind : arguments.value("kinds", Json::array())) {
                const auto value = kind.get<std::string>();
                if (value == "final_framebuffer") filter->add_kinds(control::RESOURCE_KIND_FINAL_FRAMEBUFFER);
                else if (value == "texture") filter->add_kinds(control::RESOURCE_KIND_TEXTURE);
                else if (value == "buffer") filter->add_kinds(control::RESOURCE_KIND_BUFFER);
                else if (value == "patched_shaders") filter->add_kinds(control::RESOURCE_KIND_PATCHED_SHADERS);
            }
            if (arguments.contains("logical_name")) {
                filter->set_logical_name(arguments.at("logical_name").get<std::string>());
            }
            if (arguments.contains("pass_id")) filter->set_pass_id(arguments.at("pass_id").get<std::string>());
            return unary<control::ListResourcesResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_resources(request, std::move(completion));
                },
                [](const auto& response) -> ToolOutcome { return ResultMapper::list_resources(response); });
        }

        ToolOutcome get_status(const Json& arguments) {
            control::GetStatusRequest request;
            const auto detail = arguments.value("detail", std::string("summary"));
            request.set_detail(detail == "full" ? control::STATUS_DETAIL_FULL :
                detail == "jobs" ? control::STATUS_DETAIL_JOBS : control::STATUS_DETAIL_SUMMARY);
            if (arguments.contains("wait_until")) {
                request.set_wait_until(arguments.at("wait_until") == "job_terminal"
                    ? control::STATUS_WAIT_CONDITION_JOB_TERMINAL
                    : control::STATUS_WAIT_CONDITION_CAN_ACCEPT_JOB);
            }
            if (arguments.contains("job_id")) request.set_job_id(arguments.at("job_id").get<std::string>());
            request.set_timeout_ms(arguments.value("timeout_ms", std::uint64_t{0}));
            return unary<control::GetStatusResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().get_status(request, std::move(completion));
                },
                [](const auto& response) -> ToolOutcome {
                    auto mapped = ResultMapper::status(response);
                    return mapped.contains("status") && mapped["status"].is_object() ? mapped["status"] : mapped;
                });
        }

        ToolOutcome restart(const Json& arguments) {
            control::RequestRestartRequest request;
            request.set_reason(arguments.value(
                "reason", std::string("Minecraft restart requested through Vibris MCP.")));
            return unary<control::RequestRestartResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().request_restart(request, std::move(completion));
                },
                [](const auto& response) -> ToolOutcome {
                    if (!response.has_restart()) {
                        return ToolFailure{"SERVER_NOT_READY",
                            "The Vibris server did not return a restart receipt.", true};
                    }
                    const auto& restart = response.restart();
                    std::string phase = restart.phase() == control::RESTART_PHASE_LAUNCHING
                        ? "launching" : "draining";
                    return Json{{"scheduled", true},
                                {"already_scheduled", response.already_scheduled()},
                                {"phase", std::move(phase)},
                                {"remaining_jobs", restart.remaining_jobs()},
                                {"requested_at_unix_ms", restart.requested_at_unix_ms()},
                                {"requested_by_workspace_id", restart.requested_by_workspace_id()},
                                {"reason", restart.reason()},
                                {"retry_after_ms", restart.retry_after_ms()},
                                {"behavior", "New job admission is closed; all accepted jobs drain before restart."},
                                {"next_action", {{"kind", "wait_for_runtime_restart"},
                                                  {"tool", "vibris_get_status"},
                                                  {"retry_after_ms", restart.retry_after_ms()}}}};
                });
        }

        ToolOutcome job(const Json& arguments) {
            return jobs_.control(arguments);
        }

        ToolOutcome artifacts(const Json& arguments) {
            if (arguments.at("operation") == "prune") {
                return prune_artifacts(binding_.root, arguments, [this](const Json& request) {
                    return artifacts(request);
                });
            }
            control::ManageArtifactsRequest request;
            request.set_workspace_id(workspace_id_);
            const auto operation = arguments.at("operation").get<std::string>();
            if (operation == "list") request.set_operation(control::ARTIFACT_OPERATION_LIST);
            else if (operation == "get") request.set_operation(control::ARTIFACT_OPERATION_GET);
            else if (operation == "capacity") request.set_operation(control::ARTIFACT_OPERATION_CAPACITY);
            else if (operation == "delete") request.set_operation(control::ARTIFACT_OPERATION_DELETE);
            if (arguments.contains("manifest_id")) {
                request.set_manifest_id(arguments.at("manifest_id").get<std::string>());
            }
            if (arguments.contains("expected_manifest_sha256")) {
                request.set_expected_manifest_sha256(
                    arguments.at("expected_manifest_sha256").get<std::string>());
            }
            if (arguments.contains("job_id")) request.set_job_id(arguments.at("job_id").get<std::string>());
            if (arguments.contains("request_id")) {
                request.set_request_id(arguments.at("request_id").get<std::string>());
            }
            return unary<control::ManageArtifactsResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().manage_artifacts(request, std::move(completion));
                },
                [this](const auto& response) -> ToolOutcome {
                    ToolOutcome outcome = ResultMapper::artifacts(response);
                    artifact_link_.rewrite(outcome);
                    return outcome;
                }, unary_wait + std::chrono::seconds(1), false);
        }

        ToolOutcome run_durable_step(DurableJobStepExecution execution) {
            if (!wait_for_planned_restart()) {
                return ToolFailure{"SERVER_RESTARTING",
                    "A planned Minecraft restart did not finish within five minutes. Resume this durable job; "
                    "the runtime is restarting, not failed.", true,
                    {{"next_action", "resume"},
                     {"waited_ms", std::chrono::duration_cast<std::chrono::milliseconds>(unary_wait).count()}}};
            }
            const auto context = scene_from_json(execution.arguments.at("__vibris_scene_context"));
            execution.config.save_id = context.save_id();
            execution.config.dimension_id = context.dimension_id();
            execution.config.time_preset_id = context.time_preset_id();
            execution.config.camera_preset_id = context.camera_preset_id();
            execution.config.fov = context.fov();
            return unary<control::GetServerInfoResponse>(
                [this](auto completion) { return client().get_server_info(std::move(completion)); },
                [this, &execution, &context](const auto& response) -> ToolOutcome {
                    if (!response.has_server()) {
                        throw StateError(
                            "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                    }
                    SynchronousJobControl control{
                        .stop = execution.stop,
                        .resume_request_id = execution.resume_request_id,
                        .progress = [progress = execution.progress](
                                        const SynchronousJobProgress& event) {
                            progress(event.request_id, event.stage, event.accepted);
                        },
                    };
                    auto outcome = SynchronousJobRunner(client(), source_handler_, execution.config).run(
                        execution.tool_name, execution.arguments, response.server(), context, control);
                    artifact_link_.rewrite(outcome);
                    compare_recipe_captures(binding_.root, execution.arguments, outcome);
                    return outcome;
                });
        }

        ToolOutcome start_durable_job(std::string_view name, const Json& arguments) {
            if (arguments.contains("presets")) {
                control::ListPresetsRequest request;
                return unary<control::ListPresetsResponse>(
                    [this, request](auto completion) { return client().list_presets(request, std::move(completion)); },
                    [this, name, &arguments](const auto& response) -> ToolOutcome {
                        auto enriched = arguments;
                        enriched["__vibris_presets"] = Json::array();
                        std::optional<JobContext> first_config;
                        for (const auto& id : arguments.at("presets")) {
                            const auto preset = SceneContextResolver::resolve_preset(id.template get<std::string>(), response);
                            auto config = config_from_preset(preset, workspace_id_);
                            detail::validate_config(config);
                            control::ValidateContextRequest validation;
                            *validation.mutable_context() = preset.context();
                            const auto valid = unary<control::ValidateContextResponse>(
                                [this, validation](auto completion) {
                                    return client().validate_context(validation, std::move(completion));
                                }, [](const auto& checked) -> ToolOutcome {
                                    if (!checked.valid()) return ToolFailure{"INVALID_PRESET", "Scene context was rejected.", false,
                                        ResultMapper::validation(checked)};
                                    return Json::object();
                                });
                            if (std::holds_alternative<ToolFailure>(valid)) return valid;
                            enriched["__vibris_presets"].push_back({{"preset_id", preset.preset_id()},
                                {"__vibris_scene_context", scene_json(preset.context())},
                                {"__vibris_preset", preset_json(preset)}});
                            if (!first_config) first_config = std::move(config);
                        }
                        return jobs_.start(name, enriched, *first_config);
                    });
            }
            return with_scene(arguments, [this, name, &arguments](const auto& config, const auto& preset) -> ToolOutcome {
                auto enriched = arguments;
                enriched["__vibris_scene_context"] = scene_json(preset.context());
                enriched["__vibris_preset"] = preset_json(preset);
                return jobs_.start(name, enriched, config);
            });
        }

        ToolOutcome run_job(std::string_view name, const Json& arguments) {
            if (name == "vibris_run_recipe" &&
                arguments.value("recipe", std::string{}) == "recover_runtime") {
                JobContext config;
                config.workspace_id = workspace_id_;
                control::SceneContext context;
                return unary<control::GetServerInfoResponse>(
                    [this](auto completion) { return client().get_server_info(std::move(completion)); },
                    [this, &arguments, &context, &config](const auto& response) -> ToolOutcome {
                        if (!response.has_server()) {
                            throw StateError(
                                "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                        }
                        return SynchronousJobRunner(client(), source_handler_, config).run(
                            "vibris_run_recipe", arguments, response.server(), context);
                    });
            }
            return with_scene(arguments,
                [this, name, &arguments](const auto& config, const auto& preset) -> ToolOutcome {
                    const auto context = preset.context();
                    auto enriched = arguments;
                    enriched["__vibris_preset"] = preset_json(preset);
                    return unary<control::GetServerInfoResponse>(
                        [this](auto completion) { return client().get_server_info(std::move(completion)); },
                        [this, name, &enriched, &context, &config](const auto& response) -> ToolOutcome {
                            if (!response.has_server()) {
                                throw StateError(
                                    "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                            }
                            auto outcome = SynchronousJobRunner(client(), source_handler_, config).run(
                                name, enriched, response.server(), context);
                            artifact_link_.rewrite(outcome);
                            compare_recipe_captures(binding_.root, enriched, outcome);
                            return outcome;
                        });
                });
        }

        GrpcClient& client() {
            if (!grpc_) {
                grpc_ = std::make_unique<GrpcClient>(GrpcClientOptions{
                    .target = server_address_,
                    .workspace_id = workspace_id_,
                    .mcp_version = "0.1.0",
                    .process_instance_uuid = process_id_,
                    .pending_request_limit = pending_limit,
                    .unary_deadline = unary_wait,
                });
                grpc_->start();
                used_client_ = true;
            }
            return *grpc_;
        }

        bool wait_for_planned_restart() {
            auto& current = client();
            return !current.restart_scheduled() || current.wait_for_restart(unary_wait);
        }

        void release_client() {
            if (!grpc_) return;
            grpc_->shutdown();
            const auto stats = grpc_->stats();
            aggregate_.completion_queue_count =
                std::max(aggregate_.completion_queue_count, stats.completion_queue_count);
            aggregate_.peak_pending_requests =
                std::max(aggregate_.peak_pending_requests, stats.peak_pending_requests);
            aggregate_.worker_threads_started += stats.worker_threads_started;
            aggregate_.worker_threads_joined += stats.worker_threads_joined;
            aggregate_.restart_scheduled = aggregate_.restart_scheduled || stats.restart_scheduled;
            aggregate_.restart_retry_after_ms = std::max(
                aggregate_.restart_retry_after_ms, stats.restart_retry_after_ms);
            grpc_.reset();
        }

        WorkspaceBinding binding_;
        std::string workspace_id_;
        std::string server_address_;
        std::string process_id_;
        SourceHandler source_handler_;
        WorkspaceArtifactLink artifact_link_;
        std::unique_ptr<GrpcClient> grpc_;
        DurableJobWorkflow jobs_;
        GrpcClientStats aggregate_{};
        bool used_client_ = false;
    };

public:
    explicit Impl(std::string server_address) : server_address_(std::move(server_address)) {}

    ToolOutcome dispatch(std::string_view name, const Json& arguments) {
        try {
            const auto root = arguments.find("worktree_root");
            if (root == arguments.end() || !root->is_string()) {
                throw StateError(kInvalidWorktreeCode, "Every Vibris request requires worktree_root.");
            }
            const auto binding = resolve_workspace(fs::path(root->get<std::string>()));
            auto scoped = arguments;
            scoped.erase("worktree_root");
            auto& runtime = workspace(binding);
            auto outcome = runtime.dispatch(name, scoped);
            runtime.attach_scope(outcome);
            return outcome;
        } catch (const StateError& error) {
            return ToolFailure{std::string(error.code()), error.what(), error.retryable(), error.details()};
        } catch (const std::exception& error) {
            std::cerr << "vibris-mcp tool failure: " << bounded(error.what()) << '\n';
            return ToolFailure{"INTERNAL_ERROR", "Tool execution failed.", false};
        }
    }

    void interrupt_waits() {
        std::scoped_lock lock(workspace_mutex_);
        waits_interrupted_ = true;
        for (auto& [root, runtime] : workspaces_) runtime->interrupt_waits();
    }

    std::optional<GrpcClientStats> shutdown() {
        if (shutdown_) return std::nullopt;
        shutdown_ = true;
        std::optional<GrpcClientStats> result;
        for (auto& [root, runtime] : workspaces_) {
            static_cast<void>(root);
            if (const auto stats = runtime->shutdown()) {
                if (!result) {
                    result.emplace();
                    result->completion_queue_count = 0;
                }
                merge_stats(*result, *stats);
            }
        }
        workspaces_.clear();
        return result;
    }

private:
    WorkspaceRuntime& workspace(const WorkspaceBinding& binding) {
        std::scoped_lock lock(workspace_mutex_);
        for (auto& [root, runtime] : workspaces_) {
            std::error_code error;
            if (fs::equivalent(root, binding.root, error) && !error) return *runtime;
        }
        auto runtime = std::make_unique<WorkspaceRuntime>(
            binding, WorkspaceIdentityStore(binding.identity_path).load_or_create(), server_address_);
        if (waits_interrupted_) runtime->interrupt_waits();
        auto* const result = runtime.get();
        workspaces_.emplace(binding.root, std::move(runtime));
        return *result;
    }

    std::string server_address_;
    std::mutex workspace_mutex_;
    std::map<fs::path, std::unique_ptr<WorkspaceRuntime>> workspaces_;
    bool waits_interrupted_ = false; // Protected by workspace_mutex_.
    bool shutdown_ = false;
};

McpBackend::McpBackend(std::string server_address)
    : impl_(std::make_unique<Impl>(std::move(server_address))) {}

McpBackend::~McpBackend() = default;

ToolOutcome McpBackend::dispatch(std::string_view name, const Json& arguments) {
    return impl_->dispatch(name, arguments);
}

void McpBackend::interrupt_waits() { impl_->interrupt_waits(); }

std::optional<GrpcClientStats> McpBackend::shutdown() {
    return impl_->shutdown();
}

} // namespace vibris::mcp
