#pragma once

#include "job_context.hpp"
#include "tool_registry.hpp"

#include <filesystem>
#include <functional>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <optional>
#include <stop_token>
#include <string>
#include <string_view>
#include <thread>

namespace vibris::mcp {

struct DurableJobStepExecution final {
    std::string tool_name;
    Json arguments;
    JobContext config;
    std::optional<std::string> resume_request_id;
    std::stop_token stop;
    std::function<void(std::string_view request_id, std::string_view stage, bool accepted)> progress;
};

using DurableJobStepExecutor = std::function<ToolOutcome(DurableJobStepExecution)>;

class DurableJobWorkflow final {
public:
    DurableJobWorkflow(
        std::filesystem::path workspace_root,
        std::string workspace_id,
        DurableJobStepExecutor executor);
    ~DurableJobWorkflow();

    DurableJobWorkflow(const DurableJobWorkflow&) = delete;
    DurableJobWorkflow& operator=(const DurableJobWorkflow&) = delete;

    [[nodiscard]] ToolOutcome start(
        std::string_view tool_name,
        const Json& arguments,
        const JobContext& config);
    [[nodiscard]] ToolOutcome control(const Json& arguments);
    [[nodiscard]] Json active_status() const;
    [[nodiscard]] bool running() const;
    // Stops client observers only; accepted GPU work retains its existing lifecycle.
    void interrupt_waits();
    void shutdown();

private:
    struct Record final {
        Json request;
        Json state;
    };

    [[nodiscard]] Record create_record(
        std::string_view tool_name,
        const Json& arguments,
        const JobContext& config) const;
    [[nodiscard]] Record load(std::string_view job_id) const;
    void save_state(const Json& state) const;
    void publish_request(const Json& request) const;
    void append_event(Json& state, std::string_view type, std::string_view stage,
        const Json& step = nullptr, std::string_view request_id = {}, bool accepted = false) const;
    [[nodiscard]] Json events(std::string_view job_id, std::uint64_t cursor = 0) const;
    [[nodiscard]] std::optional<Json> load_receipt(std::string_view job_id, std::size_t index) const;
    void publish_receipt(std::string_view job_id, std::size_t index, const Json& receipt) const;
    void publish_result(std::string_view job_id, const Json& result) const;
    [[nodiscard]] bool finalization_resume_safe(const Record& record) const;
    [[nodiscard]] Json snapshot(const Record& record, std::uint64_t event_cursor, bool include_result) const;
    [[nodiscard]] Json final_result(const Record& record) const;
    [[nodiscard]] ToolOutcome begin(std::string job_id, bool asynchronous);
    void execute(std::string job_id, std::stop_token stop) noexcept;
    void finish_active(std::string_view job_id) noexcept;
    void reap_finished();

    std::filesystem::path workspace_root_;
    std::filesystem::path state_directory_;
    std::string workspace_id_;
    DurableJobStepExecutor executor_;
    mutable std::mutex store_mutex_;
    mutable std::mutex wait_mutex_;
    mutable std::condition_variable state_changed_;
    mutable std::atomic<std::uint64_t> state_generation_{0};
    std::atomic<bool> waits_interrupted_{false};
    mutable std::mutex worker_mutex_;
    std::jthread worker_;
    std::string active_job_id_;
    bool worker_running_ = false;
};

}
