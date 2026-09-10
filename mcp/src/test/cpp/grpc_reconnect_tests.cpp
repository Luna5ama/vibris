#include "grpc_client.hpp"
#include "grpc_reconnect_fixture.hpp"

#include <grpcpp/grpcpp.h>

#include <algorithm>
#include <charconv>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

using namespace std::chrono_literals;
using vibris::control::v2::ClientMessage;
using vibris::control::v2::ServerMessage;
using vibris::mcp::GrpcClient;
using vibris::mcp::GrpcClientOptions;
using vibris::mcp::test::ReconnectServer;

struct Options {
    std::string scenario;
    std::uint16_t port = 0;
    std::size_t request_count = 0;
    std::size_t drop_after = 0;
    std::size_t registry_limit = 0;
};

std::optional<std::size_t> parse_size(std::string_view value) {
    std::size_t result = 0;
    const auto [end, error] = std::from_chars(value.data(), value.data() + value.size(), result);
    if (error != std::errc{} || end != value.data() + value.size()) {
        return std::nullopt;
    }
    return result;
}

std::optional<Options> parse_options(int argc, char** argv) {
    Options options;
    for (int index = 1; index + 1 < argc; index += 2) {
        const std::string_view name(argv[index]);
        const std::string_view value(argv[index + 1]);
        if (name == "--scenario") {
            options.scenario = value;
        } else {
            const auto number = parse_size(value);
            if (!number) {
                return std::nullopt;
            }
            if (name == "--port" && *number <= std::numeric_limits<std::uint16_t>::max()) {
                options.port = static_cast<std::uint16_t>(*number);
            } else if (name == "--request-count") {
                options.request_count = *number;
            } else if (name == "--drop-after") {
                options.drop_after = *number;
            } else if (name == "--registry-limit") {
                options.registry_limit = *number;
            } else {
                return std::nullopt;
            }
        }
    }
    const bool known_scenario = options.scenario == "async-reconnect-registry" ||
        options.scenario == "no-detached-threads";
    if (argc != 11 || !known_scenario || options.port == 0 || options.request_count == 0 ||
        options.registry_limit == 0 || options.drop_after > options.request_count) {
        return std::nullopt;
    }
    return options;
}

class CompletionTracker final {
public:
    explicit CompletionTracker(std::size_t count) : counts_(count) {
    }

    void record(
        std::size_t index,
        std::string_view expected_id,
        const grpc::Status& status,
        const ServerMessage& message) {
        {
            const std::lock_guard lock(mutex_);
            ++counts_[index];
            failed_ = failed_ || !status.ok() || !message.has_pong() || message.request_id() != expected_id;
            if (counts_[index] == 1) {
                ++resolved_;
            } else {
                failed_ = true;
            }
        }
        completed_.notify_all();
    }

    [[nodiscard]] bool wait(std::chrono::seconds timeout) {
        std::unique_lock lock(mutex_);
        return completed_.wait_for(lock, timeout, [this] { return failed_ || resolved_ == counts_.size(); });
    }

    [[nodiscard]] bool resolved_once() const {
        const std::lock_guard lock(mutex_);
        return !failed_ && std::ranges::all_of(counts_, [](std::size_t count) { return count == 1; });
    }

    [[nodiscard]] std::size_t resolved() const {
        const std::lock_guard lock(mutex_);
        return resolved_;
    }

private:
    mutable std::mutex mutex_;
    std::condition_variable completed_;
    std::vector<std::size_t> counts_;
    std::size_t resolved_ = 0;
    bool failed_ = false;
};

ClientMessage make_ping(std::size_t index) {
    ClientMessage message;
    message.mutable_protocol_version()->set_major(3);
    message.mutable_protocol_version()->set_minor(0);
    message.set_message_id("message-" + std::to_string(index));
    message.set_request_id("request-" + std::to_string(index));
    message.set_workspace_id("grpc-reconnect-test");
    message.mutable_ping()->set_sequence(index);
    return message;
}

int run(const Options& options) {
    ReconnectServer server(options.port, options.drop_after);
    CompletionTracker tracker(options.request_count);
    bool overflow_callback = false;
    bool submissions_valid = true;
    GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(options.port),
        .workspace_id = "grpc-reconnect-test",
        .mcp_version = "reconnect-test",
        .process_instance_uuid = "grpc-reconnect-test",
        .pending_request_limit = options.registry_limit,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    for (std::size_t index = 0; index < options.request_count; ++index) {
        const std::string request_id = "request-" + std::to_string(index);
        if (!client.submit(make_ping(index), [&, index, request_id](const grpc::Status& status,
                                                   const ServerMessage& message) {
                tracker.record(index, request_id, status, message);
            })) {
            submissions_valid = false;
            break;
        }
    }
    if (submissions_valid && options.scenario == "async-reconnect-registry" &&
        client.submit(make_ping(options.request_count), [&](const grpc::Status&, const ServerMessage&) {
            overflow_callback = true;
        })) {
        submissions_valid = false;
    }
    server.release_requests();
    const bool completed = submissions_valid && tracker.wait(15s);
    const auto resolved_before_shutdown = tracker.resolved();
    const auto stats_before_shutdown = client.stats();
    client.shutdown();
    const auto stats = client.stats();
    server.shutdown();
    const std::size_t expected_connections = options.drop_after == 0 ? 1 : 2;
    const bool valid = submissions_valid && completed && tracker.resolved_once() && !overflow_callback &&
        server.connections() == expected_connections && stats.completion_queue_count == 1 &&
        stats.peak_pending_requests <= options.registry_limit && stats.pending_requests == 0 &&
        stats.worker_threads_started == 1 && stats.worker_threads_joined == 1;
    if (!valid) {
        std::cerr << "gRPC reconnect failed: completed=" << completed << " resolved=" << resolved_before_shutdown
                  << " connections=" << server.connections() << " pending=" << stats_before_shutdown.pending_requests
                  << " peak=" << stats.peak_pending_requests << " final=" << stats.pending_requests
                  << " workers=" << stats.worker_threads_started << '/' << stats.worker_threads_joined << '\n';
        return 1;
    }
    std::cout << "{\"scenario\":\"" << options.scenario << "\",\"port\":" << options.port
              << ",\"request_count\":" << options.request_count
              << ",\"unique_request_ids\":" << options.request_count
              << ",\"resolved_once\":" << tracker.resolved()
              << ",\"connections\":" << server.connections()
              << ",\"drop_after\":" << options.drop_after
              << ",\"registry_limit\":" << options.registry_limit
              << ",\"peak_registry\":" << stats.peak_pending_requests
              << ",\"final_registry\":" << stats.pending_requests
              << ",\"completion_queues\":" << stats.completion_queue_count
              << ",\"worker_threads_started\":" << stats.worker_threads_started
              << ",\"worker_threads_joined\":" << stats.worker_threads_joined << "}\n";
    return 0;
}

}

int main(int argc, char** argv) {
    const auto options = parse_options(argc, argv);
    if (!options) {
        std::cerr << "usage: vibris-grpc-tests --scenario SCENARIO --port PORT --request-count COUNT "
                     "--drop-after COUNT --registry-limit COUNT\n";
        return 2;
    }
    try {
        return run(*options);
    } catch (const std::exception& exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}
