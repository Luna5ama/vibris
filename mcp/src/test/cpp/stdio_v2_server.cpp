#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <cstdint>
#include <filesystem>
#include <iostream>
#include <memory>
#include <string>
#include <string_view>

namespace {

namespace proto = ::vibris::control::v2;

struct Options final {
    std::uint16_t port = 0;
    std::filesystem::path work_root;
};

Options parse_options(const int argc, char** argv) {
    Options options;
    for (int index = 1; index < argc; ++index) {
        const std::string_view argument(argv[index]);
        if (argument == "--port" && index + 1 < argc) {
            const auto value = std::stoul(argv[++index]);
            if (value == 0 || value > 65'535) throw std::invalid_argument("invalid port");
            options.port = static_cast<std::uint16_t>(value);
        } else if (argument == "--work-root" && index + 1 < argc) {
            options.work_root = std::filesystem::absolute(argv[++index]).lexically_normal();
        } else {
            throw std::invalid_argument("unknown fixture argument");
        }
    }
    if (options.port == 0 || options.work_root.empty()) throw std::invalid_argument("missing fixture argument");
    return options;
}

void version(proto::ProtocolVersion& value) {
    value.set_major(3);
    value.set_minor(0);
}

class FixtureService final : public proto::VibrisControl::Service {
public:
    explicit FixtureService(std::filesystem::path work_root)
        : pending_root_(std::move(work_root) / "pending-sources") {
        std::filesystem::create_directories(pending_root_);
    }

    grpc::Status GetServerInfo(grpc::ServerContext*, const proto::GetServerInfoRequest* request,
        proto::GetServerInfoResponse* response) override {
        if (!supported(request->protocol_version())) return unsupported();
        version(*response->mutable_protocol_version());
        auto* server = response->mutable_server();
        server->set_server_version("2.0.0-stdio-fixture");
        server->set_pending_source_root(pending_root_.string());
        server->mutable_limits()->set_max_source_bytes(64ULL * 1024ULL * 1024ULL);
        server->mutable_limits()->set_max_source_files(100'000);
        fill_status(*server->mutable_status());
        return grpc::Status::OK;
    }

    grpc::Status ListPresets(grpc::ServerContext*, const proto::ListPresetsRequest* request,
        proto::ListPresetsResponse* response) override {
        if (!supported(request->protocol_version())) return unsupported();
        version(*response->mutable_protocol_version());
        if (request->has_preset_id() && request->preset_id() != "default") return grpc::Status::OK;
        auto* preset = response->add_presets();
        preset->set_preset_id("default");
        preset->set_display_name("Default v2 fixture");
        preset->set_version("2");
        preset->set_preset_sha256(std::string(64, 'a'));
        preset->add_tags("fixture");
        auto* context = preset->mutable_context();
        context->set_save_id("test-save");
        context->set_dimension_id("minecraft:overworld");
        context->set_time_preset_id("noon");
        context->set_weather_preset_id("clear");
        context->set_camera_preset_id("camera-a");
        context->set_fov(70.0);
        context->set_settings_preset_id("default");
        context->mutable_resolution()->set_width(1280);
        context->mutable_resolution()->set_height(720);
        return grpc::Status::OK;
    }

    grpc::Status ListResources(grpc::ServerContext*, const proto::ListResourcesRequest* request,
        proto::ListResourcesResponse* response) override {
        if (!supported(request->protocol_version())) return unsupported();
        version(*response->mutable_protocol_version());
        response->mutable_catalog()->set_mapping_sha256(std::string(64, 'b'));
        return grpc::Status::OK;
    }

    grpc::Status ValidateContext(grpc::ServerContext*, const proto::ValidateContextRequest* request,
        proto::ValidateContextResponse* response) override {
        if (!supported(request->protocol_version())) return unsupported();
        version(*response->mutable_protocol_version());
        response->set_valid(request->context().save_id() == "test-save");
        return grpc::Status::OK;
    }

    grpc::Status GetStatus(grpc::ServerContext*, const proto::GetStatusRequest* request,
        proto::GetStatusResponse* response) override {
        if (!supported(request->protocol_version())) return unsupported();
        version(*response->mutable_protocol_version());
        fill_status(*response->mutable_status());
        return grpc::Status::OK;
    }

    grpc::Status Control(grpc::ServerContext*,
        grpc::ServerReaderWriter<proto::ServerMessage, proto::ClientMessage>* stream) override {
        proto::ClientMessage request;
        if (!stream->Read(&request) || !request.has_protocol_version() ||
            !supported(request.protocol_version()) || !request.has_client_hello()) {
            return unsupported();
        }
        proto::ServerMessage hello;
        version(*hello.mutable_protocol_version());
        hello.set_message_id("server-hello");
        hello.set_workspace_id(request.workspace_id());
        hello.mutable_server_hello()->set_server_version("2.0.0-stdio-fixture");
        fill_status(*hello.mutable_server_hello()->mutable_status());
        if (!stream->Write(hello)) return {grpc::StatusCode::UNAVAILABLE, "hello write failed"};

        while (stream->Read(&request)) {
            if (!request.has_protocol_version() || !supported(request.protocol_version())) return unsupported();
            if (!request.has_submit_job()) continue;
            proto::ServerMessage completed;
            version(*completed.mutable_protocol_version());
            completed.set_message_id("completed-" + request.request_id());
            completed.set_request_id(request.request_id());
            completed.set_workspace_id(request.workspace_id());
            auto* terminal = completed.mutable_job_completed();
            terminal->set_job_id(request.submit_job().job().job_id());
            terminal->set_request_id(request.request_id());
            auto* result = terminal->mutable_result();
            result->mutable_timings()->set_total_ms(1);
            for (int index = 0; index < request.submit_job().job().action_sequence().actions_size(); ++index) {
                auto* receipt = result->add_action_receipts();
                receipt->set_action_index(static_cast<std::uint32_t>(index));
                receipt->set_kind(proto::ACTION_KIND_INSPECT_SHADER);
                receipt->set_status(proto::RECEIPT_STATUS_OK);
                receipt->mutable_empty();
            }
            if (!stream->Write(completed)) return {grpc::StatusCode::UNAVAILABLE, "result write failed"};
        }
        return grpc::Status::OK;
    }

private:
    static bool supported(const proto::ProtocolVersion& value) {
        return value.major() == 3;
    }

    static grpc::Status unsupported() {
        return {grpc::StatusCode::FAILED_PRECONDITION, "UNSUPPORTED_VERSION"};
    }

    static void fill_status(proto::ServerStatus& status) {
        status.set_state(proto::SERVER_STATE_AVAILABLE);
        status.set_can_accept_job(true);
        status.set_can_start_job(true);
        auto* readiness = status.mutable_readiness();
        readiness->set_core_online(true);
        readiness->set_minecraft_connected(true);
        readiness->set_world_loaded(true);
        readiness->set_scene_applied(true);
        readiness->set_shader_reload_complete(true);
        readiness->set_gpu_timing_available(true);
        readiness->set_phase(proto::RUNTIME_PHASE_AVAILABLE);
        readiness->set_detail("test-save minecraft:overworld");
    }

    std::filesystem::path pending_root_;
};

} // namespace

int main(const int argc, char** argv) {
    try {
        const auto options = parse_options(argc, argv);
        FixtureService service(options.work_root);
        grpc::ServerBuilder builder;
        builder.AddListeningPort("127.0.0.1:" + std::to_string(options.port), grpc::InsecureServerCredentials());
        builder.RegisterService(&service);
        auto server = builder.BuildAndStart();
        if (!server) throw std::runtime_error("could not start v2 stdio fixture server");
        server->Wait();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
