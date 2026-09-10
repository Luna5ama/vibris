#include "grpc_client.hpp"
#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <chrono>
#include <condition_variable>
#include <future>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {

using namespace std::chrono_literals;
namespace proto = ::vibris::control::v2;

class PlannedRestartService final : public proto::VibrisControl::Service {
public:
    grpc::Status Control(grpc::ServerContext*,
        grpc::ServerReaderWriter<proto::ServerMessage, proto::ClientMessage>* stream) override {
        proto::ClientMessage request;
        if (!stream->Read(&request) || !request.has_client_hello()) {
            return {grpc::StatusCode::INVALID_ARGUMENT, "CLIENT_HELLO_REQUIRED"};
        }
        proto::ServerMessage hello;
        hello.mutable_protocol_version()->set_major(3);
        hello.set_workspace_id(request.workspace_id());
        hello.mutable_server_hello()->set_server_version("planned-restart-fixture");
        hello.mutable_server_hello()->mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
        if (!stream->Write(hello)) return {grpc::StatusCode::UNAVAILABLE, "hello write failed"};

        proto::ServerMessage notice;
        notice.mutable_protocol_version()->set_major(3);
        notice.set_workspace_id(request.workspace_id());
        notice.mutable_server_shutting_down()->set_reason("planned test restart");
        notice.mutable_server_shutting_down()->set_retry_after_ms(25);
        if (!stream->Write(notice)) return {grpc::StatusCode::UNAVAILABLE, "notice write failed"};
        {
            const std::lock_guard lock(mutex_);
            notified_ = true;
        }
        changed_.notify_all();

        {
            std::unique_lock lock(mutex_);
            changed_.wait(lock, [this] { return completed_; });
        }
        proto::ServerMessage replacement;
        replacement.mutable_protocol_version()->set_major(3);
        replacement.set_workspace_id(request.workspace_id());
        replacement.mutable_server_hello()->set_server_version("replacement-fixture");
        replacement.mutable_server_hello()->mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
        if (!stream->Write(replacement)) return {grpc::StatusCode::UNAVAILABLE, "replacement write failed"};
        while (stream->Read(&request)) {
            if (!request.has_ping()) continue;
            proto::ServerMessage pong;
            pong.mutable_protocol_version()->set_major(3);
            pong.set_message_id(request.message_id());
            pong.set_request_id(request.request_id());
            pong.set_workspace_id(request.workspace_id());
            pong.mutable_pong()->set_sequence(request.ping().sequence());
            if (!stream->Write(pong)) return {grpc::StatusCode::UNAVAILABLE, "pong write failed"};
        }
        return grpc::Status::OK;
    }

    bool wait_for_notice() {
        std::unique_lock lock(mutex_);
        return changed_.wait_for(lock, 5s, [this] { return notified_; });
    }

    void complete_restart() {
        {
            const std::lock_guard lock(mutex_);
            completed_ = true;
        }
        changed_.notify_all();
    }

private:
    std::mutex mutex_;
    std::condition_variable changed_;
    bool notified_ = false;
    bool completed_ = false;
};

int run() {
    PlannedRestartService service;
    grpc::ServerBuilder builder;
    int port = 0;
    builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port);
    builder.RegisterService(&service);
    auto server = builder.BuildAndStart();
    if (!server || port == 0) throw std::runtime_error("failed to start planned-restart fixture");

    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(port),
        .workspace_id = "planned-restart-test",
        .mcp_version = "planned-restart-test",
        .process_instance_uuid = "planned-restart-test",
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    std::promise<bool> ping_completion;
    auto ping_result = ping_completion.get_future();
    proto::ClientMessage ping;
    ping.mutable_protocol_version()->set_major(3);
    ping.set_message_id("planned-restart-ping");
    ping.set_request_id("planned-restart-ping");
    ping.set_workspace_id("planned-restart-test");
    ping.mutable_ping()->set_sequence(1);
    if (!client.submit(std::move(ping), [&ping_completion](
            const grpc::Status& status, const proto::ServerMessage& message) {
            ping_completion.set_value(status.ok() && message.has_pong());
        })) {
        throw std::runtime_error("failed to start control stream");
    }
    if (!service.wait_for_notice()) throw std::runtime_error("client did not receive restart notice");
    const auto noticed = std::chrono::steady_clock::now() + 5s;
    while (!client.restart_scheduled() && std::chrono::steady_clock::now() < noticed) {
        std::this_thread::yield();
    }
    if (!client.restart_scheduled() || client.stats().restart_retry_after_ms != 25) {
        throw std::runtime_error("client did not retain planned restart state");
    }

    auto waiter = std::async(std::launch::async, [&client] { return client.wait_for_restart(5s); });
    if (waiter.wait_for(50ms) != std::future_status::timeout) {
        throw std::runtime_error("planned restart wait returned before replacement runtime");
    }
    service.complete_restart();
    if (!waiter.get() || client.restart_scheduled()) {
        throw std::runtime_error("replacement ServerHello did not release planned restart wait");
    }
    if (ping_result.wait_for(5s) != std::future_status::ready || !ping_result.get()) {
        throw std::runtime_error("control stream did not remain usable across the planned restart notice");
    }

    client.shutdown();
    server->Shutdown();
    server->Wait();
    std::cout << "planned restart notification waited for replacement runtime\n";
    return 0;
}

}

int main() {
    try {
        return run();
    } catch (const std::exception& exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}
