#include "source_handler.hpp"

#include "config_document.hpp"
#include "source_delivery.hpp"
#include "state_error.hpp"

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <list>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace control = ::vibris::control::v2;

void prepare_one(SourcePreparer& preparer, const SourceDelivery& delivery,
    const std::filesystem::path& workspace_root,
    const Json* source, std::list<PreparedSource>& prepared) {
    const auto kind = source == nullptr ? std::string("workspace") : source->at("kind").get<std::string>();
    if (kind == "workspace") {
        prepared.emplace_back(preparer.prepare_workspace());
        return;
    }
    if (kind == "snapshot") {
        const auto job_id = source->at("job_id").get<std::string>();
        const auto snapshot_uuid = source->at("snapshot_uuid").get<std::string>();
        if (!detail::is_uuid(job_id) || !detail::is_uuid(snapshot_uuid)) {
            throw StateError("JOB_CHECKPOINT_ERROR", "Queued source snapshot identity is invalid.", false);
        }
        control::PreparedSourceRef provenance;
        provenance.set_file_count(source->at("file_count").get<std::uint64_t>());
        provenance.set_total_bytes(source->at("total_bytes").get<std::uint64_t>());
        provenance.set_requested_revision(source->at("requested_revision").get<std::string>());
        provenance.set_resolved_revision(source->at("resolved_revision").get<std::string>());
        provenance.set_snapshot_sha256(source->at("snapshot_sha256").get<std::string>());
        control::SourceArchiveFormat archive_format;
        if (!control::SourceArchiveFormat_Parse(
                source->at("archive_format").get<std::string>(), &archive_format) ||
            archive_format != control::SOURCE_ARCHIVE_FORMAT_TAR_ZSTD) {
            throw StateError("JOB_CHECKPOINT_ERROR", "Queued source archive format is invalid.", false);
        }
        provenance.set_archive_format(archive_format);
        provenance.set_compressed_bytes(source->at("compressed_bytes").get<std::uint64_t>());
        control::VcsCheckoutState checkout_state;
        if (!control::VcsCheckoutState_Parse(
                source->at("vcs_checkout_state").get<std::string>(), &checkout_state) ||
            checkout_state == control::VCS_CHECKOUT_STATE_UNSPECIFIED) {
            throw StateError("JOB_CHECKPOINT_ERROR", "Queued source checkout state is invalid.", false);
        }
        provenance.set_vcs_checkout_state(checkout_state);
        provenance.set_branch(source->at("branch").get<std::string>());
        provenance.set_start_head(source->at("start_head").get<std::string>());
        provenance.set_shader_tree_id(source->at("shader_tree_id").get<std::string>());
        provenance.set_dirty_shader_delta_sha256(
            source->at("dirty_shader_delta_sha256").get<std::string>());
        if (source->at("origin_kind") == "commit") {
            auto* origin = provenance.mutable_origin()->mutable_commit();
            origin->set_repository_id(source->at("origin_name").get<std::string>());
            origin->set_revision(provenance.resolved_revision());
            origin->set_worktree_root(source->at("worktree_root").get<std::string>());
        } else {
            auto* origin = provenance.mutable_origin()->mutable_workspace();
            origin->set_display_name(source->at("origin_name").get<std::string>());
            origin->set_worktree_root(source->at("worktree_root").get<std::string>());
        }
        const auto snapshot = workspace_root / ".vibris" / "jobs" /
            job_id / "sources" / (snapshot_uuid + ".tar.zst");
        prepared.emplace_back(delivery.deliver(snapshot, provenance));
        return;
    }
    prepared.emplace_back(preparer.prepare_commit(source->at("revision").get<std::string>()));
}

[[nodiscard]] SourceLimits server_limits(const control::ServerHello& server) {
    if (server.pending_source_root().empty() || !std::filesystem::path(server.pending_source_root()).is_absolute() ||
        server.limits().max_source_bytes() == 0 || server.limits().max_source_files() == 0) {
        throw StateError("SERVER_NOT_READY", "The local Vibris server did not advertise a usable source root.", true);
    }
    return {.max_total_bytes = server.limits().max_source_bytes(),
            .max_files = server.limits().max_source_files()};
}

} // namespace

SourceHandler::SourceHandler(std::filesystem::path workspace_root)
    : workspace_root_(std::move(workspace_root)) {}

SourceHandler::~SourceHandler() {
    clear();
}

void SourceHandler::prepare(
    std::string_view tool_name, const Json& arguments, const control::ServerHello& server) {
    if (tool_name == "vibris_run_recipe" &&
        arguments.value("recipe", std::string{}) == "recover_runtime") {
        source_batches_.emplace_back();
        return;
    }
    SourcePreparer preparer(
        workspace_root_, std::filesystem::path(server.pending_source_root()), server_limits(server));
    LocalSourceDelivery delivery(std::filesystem::path(server.pending_source_root()));
    std::list<PreparedSource> prepared;
    const auto recipe = arguments.value("recipe", std::string{});
    if (tool_name == "vibris_run_matrix" ||
        (tool_name == "vibris_run_recipe" && recipe == "profile_matrix") ||
        (tool_name == "vibris_run_recipe" && recipe == "compile_validate" && arguments.contains("sources")) ||
        (tool_name == "vibris_run_actions" && arguments.contains("sources"))) {
        for (const auto& source : arguments.at("sources")) {
            prepare_one(preparer, delivery, workspace_root_, &source, prepared);
        }
        if (recipe == "compile_validate" && arguments.contains("baseline")) {
            prepare_one(preparer, delivery, workspace_root_, &arguments.at("baseline"), prepared);
        }
    } else if (tool_name == "vibris_run_recipe" && recipe == "compile_validate") {
        const auto source = arguments.find("source");
        prepare_one(preparer, delivery, workspace_root_, source == arguments.end() ? nullptr : &*source, prepared);
        if (arguments.contains("baseline")) {
            prepare_one(preparer, delivery, workspace_root_, &arguments.at("baseline"), prepared);
        }
    } else if (tool_name == "vibris_run_recipe" && recipe == "ab_compare") {
        prepare_one(preparer, delivery, workspace_root_, &arguments.at("a").at("source"), prepared);
        prepare_one(preparer, delivery, workspace_root_, &arguments.at("b").at("source"), prepared);
    } else {
        const auto source = arguments.find("source");
        if (tool_name != "vibris_run_actions" || source != arguments.end()) {
            prepare_one(preparer, delivery, workspace_root_, source == arguments.end() ? nullptr : &*source, prepared);
        }
    }

    source_batches_.emplace_back();
    source_batches_.back().sources.splice(source_batches_.back().sources.end(), prepared);
}

std::vector<control::PreparedSourceRef> SourceHandler::bind_latest(std::string request_id) {
    if (request_id.empty()) throw std::invalid_argument("source request ID must not be empty");
    if (source_batches_.empty() || !source_batches_.back().request_id.empty()) {
        throw std::logic_error("no unbound prepared source batch");
    }
    if (std::any_of(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& batch) {
            return batch.request_id == request_id;
        })) {
        throw std::invalid_argument("source request ID is already bound");
    }
    std::vector<control::PreparedSourceRef> references;
    try {
        references.reserve(source_batches_.back().sources.size());
        for (const auto& source : source_batches_.back().sources) references.push_back(source.reference());
    } catch (...) {
        source_batches_.pop_back();
        throw;
    }
    source_batches_.back().request_id = std::move(request_id);
    return references;
}

void SourceHandler::observe(const control::ServerMessage& message) noexcept {
    const auto transfer = [](SourceBatch& batch) {
        if (!batch.released) {
            for (auto& source : batch.sources) source.release();
            batch.released = true;
        }
        batch.server_owned = true;
    };
    if (message.has_job_accepted()) {
        const auto& id = message.request_id().empty() ? message.job_accepted().request_id() : message.request_id();
        const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
            return item.request_id == id;
        });
        if (batch != source_batches_.end()) transfer(*batch);
        return;
    }
    if (!message.has_job_state()) return;

    const auto& job = message.job_state().summary();
    const auto& id = !message.request_id().empty() ? message.request_id() :
        !job.request_id().empty() ? job.request_id() : job.job_id();
    if (id.empty()) return;
    const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
        return item.request_id == id;
    });
    if (batch == source_batches_.end()) return;
    if (job.state() == control::JOB_STATE_UNSPECIFIED) batch->server_owned = false;
    else transfer(*batch);
}

void SourceHandler::retire(std::string_view request_id) noexcept {
    const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
        return item.request_id == request_id;
    });
    if (batch == source_batches_.end()) return;
    if (!batch->server_owned && batch->released) {
        for (const auto& source : batch->sources) {
            std::error_code ignored;
            std::filesystem::remove_all(source.directory(), ignored);
        }
    }
    source_batches_.erase(batch);
}

void SourceHandler::clear() noexcept {
    for (const auto& batch : source_batches_) {
        if (!batch.server_owned && batch.released) {
            for (const auto& source : batch.sources) {
                std::error_code ignored;
                std::filesystem::remove_all(source.directory(), ignored);
            }
        }
    }
    source_batches_.clear();
}

} // namespace vibris::mcp
