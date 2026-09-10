#include "pending_request_registry.hpp"

#include <grpcpp/grpcpp.h>

#include <array>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace proto = ::vibris::control::v2;
using vibris::mcp::PendingRequestRegistry;

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

proto::ClientMessage submit(std::string_view id) {
    proto::ClientMessage message;
    message.mutable_protocol_version()->set_major(3);
    message.mutable_protocol_version()->set_minor(0);
    message.set_message_id("message-" + std::string(id));
    message.set_request_id(id);
    message.set_workspace_id("ownership-test");
    message.mutable_submit_job()->mutable_job()->set_job_id(id);
    return message;
}

proto::ServerMessage accepted(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    message.mutable_job_accepted()->set_job_id(id);
    message.mutable_job_accepted()->set_request_id(id);
    return message;
}

proto::ServerMessage completed(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    message.mutable_job_completed()->set_job_id(id);
    message.mutable_job_completed()->set_request_id(id);
    return message;
}

proto::ServerMessage running(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    auto* summary = message.mutable_job_state()->mutable_summary();
    summary->set_job_id(id);
    summary->set_request_id(id);
    summary->set_state(proto::JOB_STATE_RUNNING);
    return message;
}

struct Event {
    grpc::StatusCode status;
    proto::ServerMessage::PayloadCase payload;
};

void accepted_request_reconnects_with_resume() {
    // Given: one pending SubmitJob with an event callback.
    PendingRequestRegistry registry(4);
    std::vector<Event> events;
    require(registry.add(submit("job-a"), [&](const grpc::Status& status, const proto::ServerMessage& response) {
        events.push_back({status.error_code(), response.payload_case()});
    }), "SubmitJob was not registered.");

    // When: the server accepts the job twice before the stream reconnects.
    require(registry.resolve(accepted("job-a")), "JobAccepted did not match its pending request.");
    require(registry.resolve(accepted("job-a")), "Duplicate JobAccepted was not recognized.");
    const auto reconnect = registry.requests();

    // Then: acceptance is surfaced once, the request remains pending, and reconnect sends only ResumeJob.
    require(events.size() == 1 && events.front().payload == proto::ServerMessage::kJobAccepted,
        "JobAccepted was not surfaced exactly once.");
    require(registry.size() == 1, "JobAccepted incorrectly resolved the pending request.");
    require(reconnect.size() == 1 && reconnect.front().has_resume_job(),
        "Accepted request did not reconnect with ResumeJob.");
    require(reconnect.front().resume_job().job_id() == "job-a",
        "ResumeJob did not contain the accepted job ID.");
    require(!reconnect.front().has_submit_job(), "Reconnect duplicated an accepted SubmitJob.");

    require(registry.resolve(completed("job-a")), "JobCompleted did not resolve the resumed request.");
    require(events.size() == 2 && events.back().payload == proto::ServerMessage::kJobCompleted,
        "Terminal completion was not surfaced after acceptance.");
    require(registry.size() == 0, "Terminal completion did not drain the request registry.");
}

void job_state_snapshot_remains_pending() {
    // Given: a request whose JobAccepted event transferred job ownership to the server.
    PendingRequestRegistry registry(4);
    std::vector<Event> events;
    require(registry.add(submit("job-state"),
        [&](const grpc::Status& status, const proto::ServerMessage& response) {
            events.push_back({status.error_code(), response.payload_case()});
        }), "SubmitJob was not registered.");
    require(registry.resolve(accepted("job-state")), "JobAccepted did not match its pending request.");

    // When: strict v2 reports a non-terminal JobStateSnapshot.
    require(registry.resolve(running("job-state")), "JobStateSnapshot did not match its pending request.");

    // Then: the snapshot is surfaced, ownership remains pending, and only a terminal payload drains it.
    require(events.size() == 2 && events.back().status == grpc::StatusCode::OK &&
            events.back().payload == proto::ServerMessage::kJobState,
        "JobStateSnapshot was not surfaced as a non-terminal strict v2 event.");
    require(registry.size() == 1 && registry.requests().front().has_resume_job(),
        "JobStateSnapshot incorrectly released request ownership.");
    require(registry.resolve(completed("job-state")), "JobCompleted did not resolve the request.");
    require(registry.size() == 0, "Terminal completion did not release request ownership.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 2> test_cases{{
    {"AcceptedRequestOwnershipReconnectsWithResumeJob", accepted_request_reconnects_with_resume},
    {"JobStateOwnershipSnapshotRemainsPending", job_state_snapshot_remains_pending},
}};

}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-grpc-request-ownership-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown request ownership scenario: " << argv[1] << '\n';
    return 2;
}
