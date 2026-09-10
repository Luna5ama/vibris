#include "grpc_client_impl.hpp"

#include <chrono>
#include <utility>

namespace vibris::mcp {
namespace {

constexpr std::uint32_t protocol_major = 3;
constexpr std::uint32_t protocol_minor = 0;

bool is_request_event(const proto::ServerMessage& message) {
    using PayloadCase = proto::ServerMessage::PayloadCase;
    switch (message.payload_case()) {
        case PayloadCase::kJobCompleted:
        case PayloadCase::kJobFailed:
        case PayloadCase::kJobState:
        case PayloadCase::kPong:
        case PayloadCase::kJobAccepted:
        case PayloadCase::kJobProgress:
            return true;
        case PayloadCase::kServerHello:
        case PayloadCase::kServerShuttingDown:
        case PayloadCase::PAYLOAD_NOT_SET:
            return false;
    }
    return false;
}

bool reconnectable(const grpc::Status& status) {
    const bool reset_stream = status.error_code() == grpc::StatusCode::INTERNAL &&
        status.error_message().find("RST_STREAM") != std::string::npos;
    return status.ok() || reset_stream || status.error_code() == grpc::StatusCode::UNAVAILABLE ||
        status.error_code() == grpc::StatusCode::CANCELLED ||
        status.error_code() == grpc::StatusCode::UNKNOWN;
}

}

void GrpcClient::Impl::schedule_alarm_locked(const AlarmKind kind, const std::chrono::milliseconds delay) {
    auto tag = std::make_unique<AlarmTag>(*this, kind);
    AlarmTag* const raw_tag = tag.release();
    raw_tag->alarm.Set(&queue_, std::chrono::system_clock::now() + delay, raw_tag);
}

proto::ClientMessage GrpcClient::Impl::hello() const {
    proto::ClientMessage message;
    message.mutable_protocol_version()->set_major(protocol_major);
    message.mutable_protocol_version()->set_minor(protocol_minor);
    message.set_message_id("hello-" + options_.process_instance_uuid);
    message.set_workspace_id(options_.workspace_id);
    auto* hello = message.mutable_client_hello();
    hello->set_client_version(options_.mcp_version);
    hello->set_process_instance_id(options_.process_instance_uuid);
    hello->add_capabilities(proto::CAPABILITY_CONTROL_STREAM);
    hello->add_capabilities(proto::CAPABILITY_DURABLE_JOBS);
    return message;
}

void GrpcClient::Impl::start_control() {
    std::scoped_lock lock(mutex_);
    if (stopping_ || stream_) {
        return;
    }
    ensure_stub_locked();
    submitted_.clear();
    writes_.clear();
    writes_.push_back(hello());
    for (auto& request : pending_.requests()) {
        writes_.push_back(std::move(request));
    }
    control_context_ = std::make_unique<grpc::ClientContext>();
    stream_ = stub_->AsyncControl(control_context_.get(), &queue_, new ControlTag(*this, ControlKind::start));
    stream_started_ = false;
    stream_failed_ = false;
    finish_in_flight_ = false;
}

void GrpcClient::Impl::handle_alarm(const AlarmKind kind, const bool ok) noexcept {
    if (!ok) return;
    bool start_stream;
    std::deque<proto::ClientMessage> submitted;
    {
        std::scoped_lock lock(mutex_);
        if (stopping_) return;
        start_stream = kind == AlarmKind::reconnect || !stream_;
        if (!start_stream) submitted.swap(submitted_);
    }
    if (start_stream) {
        start_control();
        return;
    }
    {
        std::scoped_lock lock(mutex_);
        for (auto& request : submitted) writes_.push_back(std::move(request));
    }
    begin_write();
}

void GrpcClient::Impl::handle_control(const ControlKind kind, const bool ok) noexcept {
    if (is_stopping()) {
        connected_.store(false, std::memory_order_relaxed);
        return;
    }
    switch (kind) {
        case ControlKind::start:
            if (!ok) {
                fail_stream();
                return;
            }
            stream_started_ = true;
            connected_.store(true, std::memory_order_relaxed);
            begin_read();
            begin_write();
            return;
        case ControlKind::read:
            read_in_flight_ = false;
            if (!ok) {
                fail_stream();
                return;
            }
            if (is_request_event(read_message_)) {
                pending_.resolve(read_message_);
            }
            if (read_message_.has_server_hello()) {
                const auto& status = read_message_.server_hello().status();
                const bool scheduled = status.has_restart();
                restart_scheduled_.store(scheduled, std::memory_order_release);
                restart_retry_after_ms_.store(
                    scheduled ? status.restart().retry_after_ms() : 0,
                    std::memory_order_release);
                restart_changed_.notify_all();
            }
            if (read_message_.has_server_shutting_down()) {
                restart_scheduled_.store(true, std::memory_order_release);
                restart_retry_after_ms_.store(
                    read_message_.server_shutting_down().retry_after_ms(),
                    std::memory_order_release);
                restart_changed_.notify_all();
            }
            if (stream_failed_) return maybe_finish();
            begin_read();
            return;
        case ControlKind::write:
            write_in_flight_ = false;
            write_message_.Clear();
            if (!ok) {
                fail_stream();
                return;
            }
            if (stream_failed_) return maybe_finish();
            begin_write();
            return;
        case ControlKind::finish:
            finish_in_flight_ = false;
            finish_stream();
            return;
    }
}

void GrpcClient::Impl::begin_read() {
    std::scoped_lock lock(mutex_);
    if (stopping_ || !stream_started_ || stream_failed_ || read_in_flight_) {
        return;
    }
    read_message_.Clear();
    read_in_flight_ = true;
    stream_->Read(&read_message_, new ControlTag(*this, ControlKind::read));
}

void GrpcClient::Impl::begin_write() {
    std::scoped_lock lock(mutex_);
    if (stopping_ || !stream_started_ || stream_failed_ || write_in_flight_ || writes_.empty()) {
        return;
    }
    write_message_ = std::move(writes_.front());
    writes_.pop_front();
    write_in_flight_ = true;
    stream_->Write(write_message_, new ControlTag(*this, ControlKind::write));
}

void GrpcClient::Impl::fail_stream() {
    connected_.store(false, std::memory_order_relaxed);
    stream_failed_ = true;
    {
        std::scoped_lock lock(mutex_);
        if (control_context_) {
            control_context_->TryCancel();
        }
    }
    maybe_finish();
}

void GrpcClient::Impl::maybe_finish() {
    std::scoped_lock lock(mutex_);
    if (stopping_ || !stream_ || finish_in_flight_ || read_in_flight_ || write_in_flight_) {
        return;
    }
    finish_in_flight_ = true;
    stream_->Finish(&finish_status_, new ControlTag(*this, ControlKind::finish));
}

void GrpcClient::Impl::finish_stream() {
    const grpc::Status status = finish_status_;
    {
        std::scoped_lock lock(mutex_);
        stream_.reset();
        control_context_.reset();
        writes_.clear();
        stream_started_ = false;
        stream_failed_ = false;
    }
    if (reconnectable(status)) {
        if (pending_.size() != 0 || restart_scheduled_.load(std::memory_order_acquire)) {
            std::scoped_lock lock(mutex_);
            if (!stopping_) {
                schedule_alarm_locked(AlarmKind::reconnect, options_.reconnect_delay);
            }
        }
        return;
    }
    pending_.fail_all(status);
}

bool GrpcClient::Impl::is_stopping() const {
    std::scoped_lock lock(mutex_);
    return stopping_;
}

}
