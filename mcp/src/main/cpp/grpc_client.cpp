#include "grpc_client_impl.hpp"

#include <algorithm>
#include <chrono>
#include <stdexcept>
#include <utility>

namespace vibris::mcp {
namespace {

constexpr std::uint32_t protocol_major = 3;
constexpr std::uint32_t protocol_minor = 0;

bool is_loopback_target(const std::string& target) {
    return target.starts_with("127.0.0.1:") || target.starts_with("localhost:") ||
        target.starts_with("[::1]:");
}

}

bool GrpcClient::Impl::submit(proto::ClientMessage message, GrpcCompletion completion) {
    std::scoped_lock lock(mutex_);
    if (!started_ || stopping_ || pending_.size() + unary_in_flight_ >= options_.pending_request_limit) {
        return false;
    }
    if (message.protocol_version().major() != protocol_major || message.workspace_id() != options_.workspace_id) {
        return false;
    }
    if (!pending_.add(message, std::move(completion))) {
        return false;
    }
    peak_pending_ = std::max(peak_pending_, pending_.size() + unary_in_flight_);
    submitted_.push_back(std::move(message));
    schedule_alarm_locked(AlarmKind::wake, std::chrono::milliseconds::zero());
    return true;
}

bool GrpcClient::Impl::resume(std::string request_id, GrpcCompletion completion) {
    std::scoped_lock lock(mutex_);
    if (!started_ || stopping_ || pending_.size() != 0 ||
        pending_.size() + unary_in_flight_ >= options_.pending_request_limit) {
        return false;
    }
    proto::ClientMessage request;
    request.mutable_protocol_version()->set_major(protocol_major);
    request.mutable_protocol_version()->set_minor(protocol_minor);
    request.set_message_id("resume-" + request_id);
    request.set_request_id(request_id);
    request.set_workspace_id(options_.workspace_id);
    request.mutable_resume_job()->set_job_id(request_id);
    if (!pending_.add_resume(request_id, options_.workspace_id, std::move(completion))) return false;
    peak_pending_ = std::max(peak_pending_, pending_.size() + unary_in_flight_);
    submitted_.push_back(std::move(request));
    schedule_alarm_locked(AlarmKind::wake, std::chrono::milliseconds::zero());
    return true;
}

bool GrpcClient::Impl::cancel(const std::string_view request_id, std::string reason) {
    if (request_id.empty()) return false;
    const grpc::Status deadline(grpc::StatusCode::DEADLINE_EXCEEDED, reason);
    if (!pending_.cancel(request_id, deadline)) return false;
    std::scoped_lock lock(mutex_);
    if (!started_ || stopping_ || !stream_) return true;
    proto::ClientMessage request;
    request.mutable_protocol_version()->set_major(protocol_major);
    request.mutable_protocol_version()->set_minor(protocol_minor);
    request.set_message_id("cancel-" + std::string(request_id));
    request.set_request_id(std::string(request_id));
    request.set_workspace_id(options_.workspace_id);
    request.mutable_cancel_job()->set_job_id(std::string(request_id));
    request.mutable_cancel_job()->set_reason(std::move(reason));
    submitted_.push_back(std::move(request));
    schedule_alarm_locked(AlarmKind::wake, std::chrono::milliseconds::zero());
    return true;
}

bool GrpcClient::Impl::restart_scheduled() const noexcept {
    return restart_scheduled_.load(std::memory_order_acquire);
}

bool GrpcClient::Impl::wait_for_restart(const std::chrono::milliseconds timeout) {
    if (!restart_scheduled()) return true;
    std::unique_lock lock(mutex_);
    if (!stream_ && started_ && !stopping_) {
        schedule_alarm_locked(AlarmKind::reconnect, std::chrono::milliseconds::zero());
    }
    return restart_changed_.wait_for(lock, timeout, [this] {
        return stopping_ || !restart_scheduled_.load(std::memory_order_acquire);
    }) && !stopping_;
}

GrpcClient::Impl::Impl(GrpcClientOptions options)
    : options_(std::move(options)), pending_(options_.pending_request_limit) {
    if (!is_loopback_target(options_.target) || options_.workspace_id.empty() || options_.mcp_version.empty() ||
        options_.process_instance_uuid.empty() || options_.reconnect_delay.count() < 0 ||
        options_.unary_deadline.count() <= 0) {
        throw std::invalid_argument("invalid gRPC client options");
    }
}

GrpcClient::Impl::~Impl() {
    shutdown();
}

void GrpcClient::Impl::start() {
    std::scoped_lock lock(mutex_);
    if (started_) {
        return;
    }
    started_ = true;
    worker_ = std::jthread([this](const std::stop_token stop) { run(stop); });
    workers_started_.fetch_add(1, std::memory_order_relaxed);
}

void GrpcClient::Impl::shutdown() {
    {
        std::scoped_lock lock(mutex_);
        if (!started_ || stopping_) {
            return;
        }
        stopping_ = true;
        restart_changed_.notify_all();
        worker_.request_stop();
        if (control_context_) {
            control_context_->TryCancel();
        }
        queue_.Shutdown();
    }
    if (worker_.joinable()) {
        worker_.join();
        workers_joined_.fetch_add(1, std::memory_order_relaxed);
    }
    pending_.fail_all(grpc::Status(grpc::StatusCode::CANCELLED, "gRPC client stopped"));
    std::scoped_lock lock(mutex_);
    stream_.reset();
    control_context_.reset();
    channel_.reset();
    stub_.reset();
}

GrpcClientStats GrpcClient::Impl::stats() const {
    std::size_t unary_in_flight;
    std::size_t peak_pending;
    {
        std::scoped_lock lock(mutex_);
        unary_in_flight = unary_in_flight_;
        peak_pending = peak_pending_;
    }
    return {
        .completion_queue_count = 1,
        .peak_pending_requests = peak_pending,
        .pending_requests = pending_.size() + unary_in_flight,
        .worker_threads_started = workers_started_.load(std::memory_order_relaxed),
        .worker_threads_joined = workers_joined_.load(std::memory_order_relaxed),
        .control_connected = connected_.load(std::memory_order_relaxed),
        .restart_scheduled = restart_scheduled_.load(std::memory_order_relaxed),
        .restart_retry_after_ms = restart_retry_after_ms_.load(std::memory_order_relaxed),
    };
}

void GrpcClient::Impl::run(const std::stop_token stop) noexcept {
    void* raw_tag = nullptr;
    bool ok = false;
    while (queue_.Next(&raw_tag, &ok)) {
        std::unique_ptr<Tag> tag(static_cast<Tag*>(raw_tag));
        tag->complete(ok);
        if (stop.stop_requested()) {
            continue;
        }
    }
}

void GrpcClient::Impl::ensure_stub_locked() {
    if (stub_) {
        return;
    }
    channel_ = grpc::CreateChannel(options_.target, grpc::InsecureChannelCredentials());
    stub_ = proto::VibrisControl::NewStub(channel_);
}

GrpcClient::GrpcClient(GrpcClientOptions options) : impl_(std::make_unique<Impl>(std::move(options))) {}
GrpcClient::~GrpcClient() = default;
void GrpcClient::start() { impl_->start(); }
bool GrpcClient::list_presets(
    ::vibris::control::v2::ListPresetsRequest request,
    ListPresetsCompletion completion) {
    return impl_->list_presets(std::move(request), std::move(completion));
}
bool GrpcClient::list_resources(
    ::vibris::control::v2::ListResourcesRequest request,
    ListResourcesCompletion completion) {
    return impl_->list_resources(std::move(request), std::move(completion));
}
bool GrpcClient::validate_context(::vibris::control::v2::ValidateContextRequest request,
    ValidateContextCompletion completion) {
    return impl_->validate_context(std::move(request), std::move(completion));
}
bool GrpcClient::get_status(
    ::vibris::control::v2::GetStatusRequest request,
    GetStatusCompletion completion) {
    return impl_->get_status(std::move(request), std::move(completion));
}
bool GrpcClient::request_restart(
    ::vibris::control::v2::RequestRestartRequest request,
    RequestRestartCompletion completion) {
    return impl_->request_restart(std::move(request), std::move(completion));
}
bool GrpcClient::manage_artifacts(
    ::vibris::control::v2::ManageArtifactsRequest request,
    ManageArtifactsCompletion completion) {
    return impl_->manage_artifacts(std::move(request), std::move(completion));
}
bool GrpcClient::submit(::vibris::control::v2::ClientMessage message, GrpcCompletion completion) {
    return impl_->submit(std::move(message), std::move(completion));
}
bool GrpcClient::resume(std::string request_id, GrpcCompletion completion) {
    return impl_->resume(std::move(request_id), std::move(completion));
}
bool GrpcClient::cancel(std::string_view request_id, std::string reason) {
    return impl_->cancel(request_id, std::move(reason));
}
bool GrpcClient::restart_scheduled() const noexcept { return impl_->restart_scheduled(); }
bool GrpcClient::wait_for_restart(const std::chrono::milliseconds timeout) {
    return impl_->wait_for_restart(timeout);
}
void GrpcClient::shutdown() { impl_->shutdown(); }
GrpcClientStats GrpcClient::stats() const { return impl_->stats(); }

}
