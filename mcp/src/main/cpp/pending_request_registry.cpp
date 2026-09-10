#include "pending_request_registry.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace vibris::mcp {
namespace proto = ::vibris::control::v2;

PendingRequestRegistry::PendingRequestRegistry(const std::size_t capacity) : capacity_(capacity) {
    if (capacity == 0) {
        throw std::invalid_argument("pending request capacity must be positive");
    }
}

bool PendingRequestRegistry::add(proto::ClientMessage request, GrpcCompletion completion) {
    const std::string_view key = request_key(request);
    if (key.empty() || !completion) {
        return false;
    }

    std::scoped_lock lock(mutex_);
    if (entries_.size() >= capacity_ || entries_.contains(std::string(key))) {
        return false;
    }
    entries_.emplace(std::string(key),
        Entry{std::move(request), std::make_shared<CallbackSlot>(std::move(completion))});
    peak_size_ = std::max(peak_size_, entries_.size());
    return true;
}

bool PendingRequestRegistry::add_resume(
    std::string request_id, std::string workspace_id, GrpcCompletion completion) {
    if (request_id.empty() || workspace_id.empty() || !completion) return false;
    proto::ClientMessage request;
    request.mutable_protocol_version()->set_major(3);
    request.mutable_protocol_version()->set_minor(0);
    request.set_message_id("resume-" + request_id);
    request.set_request_id(request_id);
    request.set_workspace_id(std::move(workspace_id));
    request.mutable_resume_job()->set_job_id(request_id);

    std::scoped_lock lock(mutex_);
    if (entries_.size() >= capacity_ || entries_.contains(request_id)) return false;
    auto [entry, inserted] = entries_.emplace(request_id,
        Entry{std::move(request), std::make_shared<CallbackSlot>(std::move(completion)), true});
    static_cast<void>(entry);
    if (!inserted) return false;
    peak_size_ = std::max(peak_size_, entries_.size());
    return true;
}

bool PendingRequestRegistry::resolve(const proto::ServerMessage& response) {
    const std::string_view key = response_key(response);
    if (key.empty()) {
        return false;
    }

    std::shared_ptr<CallbackSlot> callback;
    std::unique_lock<std::mutex> claim;
    bool terminal = false;
    {
        std::scoped_lock lock(mutex_);
        const auto entry = entries_.find(std::string(key));
        if (entry == entries_.end()) {
            return false;
        }
        if (response.has_job_accepted()) {
            if (entry->second.accepted) return true;
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
            entry->second.accepted = true;
        } else if (response.has_job_progress() || response.has_job_state()) {
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
        } else {
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
            terminal = true;
            entries_.erase(entry);
        }
    }
    complete_claimed(*callback, grpc::Status::OK, response, terminal);
    return true;
}

bool PendingRequestRegistry::cancel(const std::string_view request_id, const grpc::Status& status) {
    std::shared_ptr<CallbackSlot> callback;
    {
        std::scoped_lock lock(mutex_);
        const auto entry = entries_.find(std::string(request_id));
        if (entry == entries_.end()) return false;
        callback = entry->second.callback;
        entries_.erase(entry);
    }
    const proto::ServerMessage response;
    return complete(callback, status, response, true);
}

void PendingRequestRegistry::fail_all(const grpc::Status& status) {
    std::vector<std::shared_ptr<CallbackSlot>> callbacks;
    {
        std::scoped_lock lock(mutex_);
        callbacks.reserve(entries_.size());
        for (auto& [id, entry] : entries_) {
            callbacks.push_back(entry.callback);
        }
        entries_.clear();
    }

    const proto::ServerMessage response;
    for (const auto& callback : callbacks) {
        complete(callback, status, response, true);
    }
}

std::vector<proto::ClientMessage> PendingRequestRegistry::requests() const {
    std::scoped_lock lock(mutex_);
    std::vector<proto::ClientMessage> requests;
    requests.reserve(entries_.size());
    for (const auto& [id, entry] : entries_) {
        if (!entry.accepted) {
            requests.push_back(entry.request);
            continue;
        }
        proto::ClientMessage resume;
        resume.mutable_protocol_version()->CopyFrom(entry.request.protocol_version());
        resume.set_message_id("resume-" + id);
        resume.set_request_id(id);
        resume.set_workspace_id(entry.request.workspace_id());
        resume.mutable_resume_job()->set_job_id(id);
        requests.push_back(std::move(resume));
    }
    return requests;
}

std::size_t PendingRequestRegistry::size() const {
    std::scoped_lock lock(mutex_);
    return entries_.size();
}

std::size_t PendingRequestRegistry::capacity() const noexcept {
    return capacity_;
}

std::size_t PendingRequestRegistry::peak_size() const {
    std::scoped_lock lock(mutex_);
    return peak_size_;
}

std::string_view PendingRequestRegistry::request_key(const proto::ClientMessage& request) {
    return request.request_id().empty() ? request.message_id() : request.request_id();
}

std::string_view PendingRequestRegistry::response_key(const proto::ServerMessage& response) {
    if (!response.request_id().empty()) {
        return response.request_id();
    }
    if (response.has_job_accepted() && !response.job_accepted().request_id().empty()) {
        return response.job_accepted().request_id();
    }
    if (response.has_job_progress() && !response.job_progress().request_id().empty()) {
        return response.job_progress().request_id();
    }
    if (response.has_job_state()) {
        const auto& summary = response.job_state().summary();
        return summary.request_id().empty() ? summary.job_id() : summary.request_id();
    }
    if (response.has_job_completed() && !response.job_completed().request_id().empty()) {
        return response.job_completed().request_id();
    }
    if (response.has_job_failed() && !response.job_failed().request_id().empty()) {
        return response.job_failed().request_id();
    }
    return response.message_id();
}

bool PendingRequestRegistry::complete_claimed(CallbackSlot& callback, const grpc::Status& status,
    const proto::ServerMessage& response, const bool terminal) noexcept {
    if (callback.terminal) return false;
    callback.terminal = terminal;
    try {
        callback.completion(status, response);
    } catch (...) {
        // User callbacks must not terminate the completion-queue worker.
    }
    return true;
}

bool PendingRequestRegistry::complete(const std::shared_ptr<CallbackSlot>& callback, const grpc::Status& status,
    const proto::ServerMessage& response, const bool terminal) noexcept {
    std::scoped_lock lock(callback->mutex);
    return complete_claimed(*callback, status, response, terminal);
}

}
