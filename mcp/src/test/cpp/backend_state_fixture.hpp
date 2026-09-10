#pragma once

#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp::test {

namespace proto = ::vibris::control::v2;

enum class PresetCatalogKind {
    process_local,
    benchmark_19,
};

struct BackendHello final {
    std::string envelope_workspace_id;
    std::string nested_workspace_id;
    std::string process_id;
    std::string message_id;
};

struct BackendJob final {
    std::string envelope_workspace_id;
    std::string nested_workspace_id;
    std::string process_id;
    proto::SceneContext context;
};

class BackendStateService final : public proto::VibrisControl::Service {
public:
    explicit BackendStateService(
        std::filesystem::path pending_root, PresetCatalogKind preset_catalog = PresetCatalogKind::process_local)
        : pending_root_(std::move(pending_root)), preset_catalog_(preset_catalog) {
    }

    grpc::Status GetServerInfo(
        grpc::ServerContext*, const proto::GetServerInfoRequest*, proto::GetServerInfoResponse* response) override {
        response->mutable_protocol_version()->set_major(3);
        auto* server = response->mutable_server();
        server->set_server_version("2.0.0-test");
        server->set_pending_source_root(pending_root_.string());
        server->mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
        server->mutable_status()->set_can_accept_job(true);
        server->mutable_status()->set_can_start_job(true);
        server->mutable_limits()->set_max_source_bytes(1024 * 1024);
        server->mutable_limits()->set_max_source_files(128);
        return grpc::Status::OK;
    }

    grpc::Status ListPresets(
        grpc::ServerContext*, const proto::ListPresetsRequest*, proto::ListPresetsResponse* response) override {
        response->mutable_protocol_version()->set_major(3);
        if (preset_catalog_ == PresetCatalogKind::benchmark_19) {
            add_benchmark_presets(*response);
        } else {
            add_preset(*response, "scene-a", "save-a", "dimension-a", "time-a", "camera-a", 61.0);
            add_preset(*response, "scene-b", "save-b", "dimension-b", "time-b", "camera-b", 89.0);
            add_preset(*response, "scene-c", "save-c", "dimension-c", "time-c", "camera-c", 73.0);
        }
        return grpc::Status::OK;
    }

    grpc::Status ListResources(
        grpc::ServerContext*, const proto::ListResourcesRequest*, proto::ListResourcesResponse* response) override {
        response->mutable_protocol_version()->set_major(3);
        return grpc::Status::OK;
    }

    grpc::Status ValidateContext(grpc::ServerContext*, const proto::ValidateContextRequest* request,
        proto::ValidateContextResponse* response) override {
        const std::lock_guard lock(mutex_);
        validated_.push_back(request->context());
        response->mutable_protocol_version()->set_major(3);
        response->set_valid(true);
        return grpc::Status::OK;
    }

    grpc::Status GetStatus(
        grpc::ServerContext*, const proto::GetStatusRequest*, proto::GetStatusResponse* response) override {
        response->mutable_protocol_version()->set_major(3);
        response->mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
        response->mutable_status()->set_can_accept_job(true);
        response->mutable_status()->set_can_start_job(true);
        auto* readiness = response->mutable_status()->mutable_readiness();
        readiness->set_core_online(true);
        readiness->set_minecraft_connected(true);
        readiness->set_world_loaded(true);
        readiness->set_scene_applied(true);
        readiness->set_shader_reload_complete(true);
        readiness->set_phase(proto::RUNTIME_PHASE_AVAILABLE);
        return grpc::Status::OK;
    }

    grpc::Status Control(grpc::ServerContext*,
        grpc::ServerReaderWriter<proto::ServerMessage, proto::ClientMessage>* stream) override {
        proto::ClientMessage request;
        if (!stream->Read(&request) || !request.has_client_hello()) {
            return {grpc::StatusCode::INVALID_ARGUMENT, "CLIENT_HELLO_REQUIRED"};
        }
        const auto process_id = request.client_hello().process_instance_id();
        {
            const std::lock_guard lock(mutex_);
            hellos_.push_back({request.workspace_id(), request.workspace_id(), process_id,
                request.message_id()});
        }
        proto::ServerMessage hello;
        hello.mutable_protocol_version()->set_major(3);
        hello.set_workspace_id(request.workspace_id());
        hello.mutable_server_hello()->set_server_version("2.0.0-test");
        hello.mutable_server_hello()->mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
        if (!stream->Write(hello)) {
            return {grpc::StatusCode::UNAVAILABLE, "hello write failed"};
        }

        while (stream->Read(&request)) {
            if (!request.has_submit_job()) {
                continue;
            }
            {
                const std::lock_guard lock(mutex_);
                jobs_.push_back({request.workspace_id(), request.workspace_id(), process_id,
                    request.submit_job().job().context()});
            }
            proto::ServerMessage completed;
            completed.mutable_protocol_version()->set_major(3);
            completed.set_request_id(request.request_id());
            completed.set_workspace_id(request.workspace_id());
            auto* terminal = completed.mutable_job_completed();
            terminal->set_job_id(request.submit_job().job().job_id());
            terminal->set_request_id(request.request_id());
            auto* result = terminal->mutable_result();
            const auto& sequence = request.submit_job().job().action_sequence();
            for (int index = 0; index < sequence.actions_size(); ++index) {
                auto* action = result->add_action_receipts();
                action->set_action_index(static_cast<std::uint32_t>(index));
                action->set_kind(proto::ACTION_KIND_INSPECT_SHADER);
                action->set_status(proto::RECEIPT_STATUS_OK);
                action->mutable_empty();
            }
            if (!stream->Write(completed)) {
                return {grpc::StatusCode::UNAVAILABLE, "completion write failed"};
            }
        }
        return grpc::Status::OK;
    }

    [[nodiscard]] std::vector<BackendHello> hellos() const {
        const std::lock_guard lock(mutex_);
        return hellos_;
    }

    [[nodiscard]] std::vector<BackendJob> jobs() const {
        const std::lock_guard lock(mutex_);
        return jobs_;
    }

    [[nodiscard]] std::size_t validation_count() const {
        const std::lock_guard lock(mutex_);
        return validated_.size();
    }

    [[nodiscard]] std::vector<proto::SceneContext> validated() const {
        const std::lock_guard lock(mutex_);
        return validated_;
    }

private:
    static void add_preset(proto::ListPresetsResponse& response, std::string id, std::string save,
        std::string dimension, std::string time, std::string camera, double fov,
        std::vector<std::string> tags = {}) {
        auto* preset = response.add_presets();
        preset->set_preset_id(id);
        preset->set_display_name(id);
        preset->set_version("2");
        preset->set_preset_sha256(std::string(64, 'a'));
        for (auto& tag : tags) preset->add_tags(std::move(tag));
        auto* context = preset->mutable_context();
        context->set_save_id(save);
        context->set_dimension_id(dimension);
        context->set_time_preset_id(time);
        context->set_weather_preset_id("clear");
        context->set_camera_preset_id(camera);
        context->set_fov(fov);
        context->set_settings_preset_id("default");
        context->mutable_resolution()->set_width(64);
        context->mutable_resolution()->set_height(64);
    }

    static void add_benchmark_presets(proto::ListPresetsResponse& response) {
        constexpr std::array ids{
            "aerial-perspective-1", "aerial-perspective-2", "aerial-perspective-3", "aerial-perspective-4",
            "frutiger-1", "mirror-room-1", "mirror-room-2", "night-gi-1", "non-cube-1", "parallax-1",
            "raster-jungle-1", "shadow-forest-1", "sky-afternoon-1", "sky-dusk-1", "sky-midnight-1",
            "sky-morning-1", "sky-noon-1", "sky-sunset-1", "spawn",
        };
        for (const std::string_view id : ids) {
            std::vector<std::string> tags;
            if (id.starts_with("aerial-perspective-")) tags.emplace_back("aerial-perspective");
            if (id.starts_with("raster-")) tags.emplace_back("raster");
            if (id.starts_with("shadow-")) tags.emplace_back("shadow");
            if (id.starts_with("sky-")) tags.emplace_back("sky");
            const auto value = std::string(id);
            add_preset(response, value, "save-" + value, "minecraft:overworld", value, value, 70.0,
                std::move(tags));
        }
    }

    std::filesystem::path pending_root_;
    PresetCatalogKind preset_catalog_;
    mutable std::mutex mutex_;
    std::vector<BackendHello> hellos_;
    std::vector<BackendJob> jobs_;
    std::vector<proto::SceneContext> validated_;
};

class BackendStateServer final {
public:
    explicit BackendStateServer(const std::filesystem::path& pending_root,
        PresetCatalogKind preset_catalog = PresetCatalogKind::process_local)
        : service_(pending_root, preset_catalog) {
        grpc::ServerBuilder builder;
        builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port_);
        builder.RegisterService(&service_);
        server_ = builder.BuildAndStart();
        if (!server_ || port_ == 0) {
            throw std::runtime_error("failed to bind backend-state fixture server");
        }
    }

    BackendStateServer(const BackendStateServer&) = delete;
    BackendStateServer& operator=(const BackendStateServer&) = delete;

    ~BackendStateServer() {
        server_->Shutdown();
        server_->Wait();
    }

    [[nodiscard]] std::string target() const {
        return "127.0.0.1:" + std::to_string(port_);
    }

    [[nodiscard]] const BackendStateService& service() const noexcept {
        return service_;
    }

private:
    BackendStateService service_;
    int port_ = 0;
    std::unique_ptr<grpc::Server> server_;
};

}
