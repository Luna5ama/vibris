#include "grpc_client.hpp"
#include "grpc_reconnect_fixture.hpp"

#include <grpcpp/grpcpp.h>

#include <chrono>
#include <condition_variable>
#include <exception>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string_view>
#include <vector>

namespace {

using namespace std::chrono_literals;
namespace proto = ::vibris::control::v2;
using vibris::mcp::GrpcClient;
using vibris::mcp::test::ReconnectServer;

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

void accepted_request_resumes_after_disconnect() {
    // Given: a server that accepts one SubmitJob and immediately drops its first control stream.
    constexpr std::uint16_t port = 55065;
    ReconnectServer server(port, 0,
        {grpc::StatusCode::INTERNAL, "Received RST_STREAM with error code 8"});
    std::mutex mutex;
    std::condition_variable completed;
    std::vector<proto::ServerMessage::PayloadCase> events;
    bool callback_failed = false;
    GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(port),
        .workspace_id = "accepted-resume-test",
        .mcp_version = "accepted-resume-test",
        .process_instance_uuid = "accepted-resume-test",
        .pending_request_limit = 4,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    proto::ClientMessage request;
    request.mutable_protocol_version()->set_major(3);
    request.set_message_id("message-job-resume");
    request.set_request_id("job-resume");
    request.set_workspace_id("accepted-resume-test");
    request.mutable_submit_job()->mutable_job()->set_job_id("job-resume");

    // When: the async client receives acceptance, reconnects, and receives the resumed terminal result.
    require(client.submit(std::move(request), [&](const grpc::Status& status, const proto::ServerMessage& response) {
        {
            const std::lock_guard lock(mutex);
            callback_failed = callback_failed || !status.ok();
            events.push_back(response.payload_case());
        }
        completed.notify_all();
    }), "SubmitJob was not accepted by the bounded client registry.");
    bool finished = false;
    {
        std::unique_lock lock(mutex);
        finished = completed.wait_for(lock, 10s, [&] {
            return callback_failed || (!events.empty() && events.back() == proto::ServerMessage::kJobCompleted);
        });
    }
    client.shutdown();
    server.shutdown();

    // Then: the event stream is surfaced and reconnect uses ResumeJob without duplicating SubmitJob.
    require(finished, "Accepted request did not complete after reconnect: submit_jobs=" +
            std::to_string(server.submit_jobs()) + " resume_requests=" + std::to_string(server.resume_requests()) +
            " duplicate_submits=" + std::to_string(server.duplicate_submits()));
    const std::vector expected{
        proto::ServerMessage::kJobAccepted,
        proto::ServerMessage::kJobState,
        proto::ServerMessage::kJobCompleted,
    };
    require(!callback_failed && events == expected, "Accepted/resumed request events were not surfaced in order.");
    require(server.submit_jobs() == 1 && server.resume_requests() == 1 && server.duplicate_submits() == 0,
        "Reconnect duplicated SubmitJob instead of sending one ResumeJob.");
}

}

int main() {
    try {
        accepted_request_resumes_after_disconnect();
        std::cout << "PASS AcceptedRequestResumesAfterDisconnect\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL AcceptedRequestResumesAfterDisconnect: " << error.what() << '\n';
        return 1;
    }
}
