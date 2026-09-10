#include "grpc_client_impl.hpp"

#include <algorithm>
#include <chrono>
#include <utility>

namespace vibris::mcp {

template <typename Request, typename Response, typename Completion>
void GrpcClient::Impl::UnaryTag<Request, Response, Completion>::complete(const bool ok) noexcept {
    owner.finish_unary(*this, ok);
}

template <typename Request, typename Response, typename Completion, typename StartCall>
bool GrpcClient::Impl::start_unary(Request request, Completion completion, StartCall start_call,
    const std::chrono::milliseconds deadline) {
    std::scoped_lock lock(mutex_);
    if (!started_ || stopping_ || !completion ||
        pending_.size() + unary_in_flight_ >= options_.pending_request_limit) {
        return false;
    }
    ensure_stub_locked();
    using Call = UnaryTag<Request, Response, Completion>;
    auto call = std::make_unique<Call>(*this, std::move(request), std::move(completion));
    call->context.set_deadline(std::chrono::system_clock::now() +
        (deadline.count() == 0 ? options_.unary_deadline : deadline));
    call->reader = start_call(*stub_, call->context, call->request, queue_);
    Call* const tag = call.release();
    ++unary_in_flight_;
    peak_pending_ = std::max(peak_pending_, pending_.size() + unary_in_flight_);
    tag->reader->Finish(&tag->response, &tag->status, tag);
    return true;
}

template <typename Call>
void GrpcClient::Impl::finish_unary(Call& call, const bool ok) noexcept {
    {
        std::scoped_lock lock(mutex_);
        --unary_in_flight_;
    }
    if (!ok && call.status.ok()) {
        call.status = grpc::Status(grpc::StatusCode::CANCELLED, "gRPC completion queue stopped");
    }
    try {
        call.completion(call.status, call.response);
    } catch (...) {
        // User callbacks must not terminate the completion-queue worker.
    }
}

bool GrpcClient::Impl::get_server_info(GetServerInfoCompletion completion) {
    proto::GetServerInfoRequest request;
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::GetServerInfoRequest, proto::GetServerInfoResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::GetServerInfoRequest& request, grpc::CompletionQueue& queue) {
            return stub.AsyncGetServerInfo(&context, request, &queue);
        });
}

bool GrpcClient::Impl::list_presets(proto::ListPresetsRequest request, ListPresetsCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::ListPresetsRequest, proto::ListPresetsResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::ListPresetsRequest& request, grpc::CompletionQueue& queue) {
            return stub.AsyncListPresets(&context, request, &queue);
        });
}

bool GrpcClient::Impl::validate_context(proto::ValidateContextRequest request,
    ValidateContextCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::ValidateContextRequest, proto::ValidateContextResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::ValidateContextRequest& value, grpc::CompletionQueue& queue) {
            return stub.AsyncValidateContext(&context, value, &queue);
        });
}

bool GrpcClient::Impl::get_status(proto::GetStatusRequest request, GetStatusCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::GetStatusRequest, proto::GetStatusResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::GetStatusRequest& request, grpc::CompletionQueue& queue) {
            return stub.AsyncGetStatus(&context, request, &queue);
        });
}

bool GrpcClient::Impl::request_restart(
    proto::RequestRestartRequest request, RequestRestartCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    request.set_workspace_id(options_.workspace_id);
    RequestRestartCompletion observe_restart = [this, completion = std::move(completion)](
                                                   const grpc::Status& status,
                                                   const proto::RequestRestartResponse& response) mutable {
        if (status.ok() && response.has_restart()) {
            restart_scheduled_.store(true, std::memory_order_release);
            restart_retry_after_ms_.store(response.restart().retry_after_ms(), std::memory_order_release);
            restart_changed_.notify_all();
        }
        completion(status, response);
    };
    return start_unary<proto::RequestRestartRequest, proto::RequestRestartResponse>(
        std::move(request), std::move(observe_restart),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::RequestRestartRequest& request, grpc::CompletionQueue& queue) {
            return stub.AsyncRequestRestart(&context, request, &queue);
        });
}

bool GrpcClient::Impl::manage_artifacts(
    proto::ManageArtifactsRequest request, ManageArtifactsCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::ManageArtifactsRequest, proto::ManageArtifactsResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::ManageArtifactsRequest& value, grpc::CompletionQueue& queue) {
            return stub.AsyncManageArtifacts(&context, value, &queue);
        });
}

bool GrpcClient::Impl::list_resources(
    proto::ListResourcesRequest request, ListResourcesCompletion completion) {
    request.mutable_protocol_version()->set_major(3);
    return start_unary<proto::ListResourcesRequest, proto::ListResourcesResponse>(
        std::move(request), std::move(completion),
        [](proto::VibrisControl::Stub& stub, grpc::ClientContext& context,
            const proto::ListResourcesRequest& value, grpc::CompletionQueue& queue) {
            return stub.AsyncListResources(&context, value, &queue);
        });
}

bool GrpcClient::get_server_info(GetServerInfoCompletion completion) {
    return impl_->get_server_info(std::move(completion));
}

}
