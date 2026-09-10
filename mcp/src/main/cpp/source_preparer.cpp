#include "source_preparer.hpp"

#include "config_document.hpp"
#include "git_repository.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <bcrypt.h>

#include <array>
#include <system_error>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

class Sha256 final {
public:
    Sha256() {
        if (BCryptOpenAlgorithmProvider(&algorithm_, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) {
            throw StateError("INTERNAL_ERROR", "Could not initialize provenance hashing.", true);
        }
        DWORD object_bytes = 0, copied = 0;
        if (BCryptGetProperty(algorithm_, BCRYPT_OBJECT_LENGTH,
                reinterpret_cast<PUCHAR>(&object_bytes), sizeof(object_bytes), &copied, 0) < 0 ||
            copied != sizeof(object_bytes)) {
            throw StateError("INTERNAL_ERROR", "Could not size provenance hashing.", true);
        }
        object_.resize(object_bytes);
        if (BCryptCreateHash(algorithm_, &hash_, object_.data(), object_bytes, nullptr, 0, 0) < 0) {
            throw StateError("INTERNAL_ERROR", "Could not create provenance hash.", true);
        }
    }
    ~Sha256() {
        if (hash_ != nullptr) BCryptDestroyHash(hash_);
        if (algorithm_ != nullptr) BCryptCloseAlgorithmProvider(algorithm_, 0);
    }
    void update(std::string_view value) {
        if (!value.empty() && BCryptHashData(hash_,
                reinterpret_cast<PUCHAR>(const_cast<char*>(value.data())),
                static_cast<ULONG>(value.size()), 0) < 0) {
            throw StateError("INTERNAL_ERROR", "Could not update provenance hash.", true);
        }
    }
    std::string finish() {
        std::array<unsigned char, 32> bytes{};
        if (BCryptFinishHash(hash_, bytes.data(), static_cast<ULONG>(bytes.size()), 0) < 0) {
            throw StateError("INTERNAL_ERROR", "Could not finish provenance hash.", true);
        }
        static constexpr char digits[] = "0123456789abcdef";
        std::string result(64, '0');
        for (std::size_t i = 0; i < bytes.size(); ++i) {
            result[i * 2] = digits[bytes[i] >> 4];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return result;
    }
private:
    BCRYPT_ALG_HANDLE algorithm_ = nullptr;
    BCRYPT_HASH_HANDLE hash_ = nullptr;
    std::vector<UCHAR> object_;
};

struct GitSnapshot final {
    control::v2::VcsCheckoutState checkout_state;
    std::string branch;
    std::string head;
    std::string shader_tree_id;
    bool dirty;
    [[nodiscard]] bool operator==(const GitSnapshot&) const = default;
};

GitSnapshot git_snapshot(const fs::path& root) {
    GitRepository repository(root);
    const auto head = repository.resolve_commit("HEAD");
    auto branch = repository.current_branch();
    return {branch.empty() ? control::v2::VCS_CHECKOUT_STATE_DETACHED :
        control::v2::VCS_CHECKOUT_STATE_ATTACHED, std::move(branch), head,
        repository.shader_tree_id(head), repository.shader_worktree_dirty()};
}

std::string provenance_delta_sha256(std::string_view domain, std::string_view first, std::string_view second) {
    Sha256 digest;
    digest.update(domain);
    digest.update(std::string_view("\0", 1));
    digest.update(first);
    digest.update(std::string_view("\0", 1));
    digest.update(second);
    return digest.finish();
}

struct Destination final { std::string uuid; fs::path owned; fs::path archive; };

Destination create_destination(const fs::path& root, SourceDestinationLayout layout) {
    const auto uuid = detail::generate_uuid();
    if (layout == SourceDestinationLayout::archive_file) {
        const auto path = root / (uuid + ".tar.zst");
        return {uuid, path, path};
    }
    const auto directory = root / uuid;
    std::error_code error;
    if (!fs::create_directory(directory, error) || error) {
        throw StateError("INTERNAL_ERROR", "Could not reserve a pending source directory.", true);
    }
    return {uuid, directory, directory / "source.tar.zst"};
}

void set_archive_fields(control::v2::PreparedSourceRef& reference, const SourceArchiveResult& result) {
    reference.set_file_count(result.metadata.file_count);
    reference.set_total_bytes(result.metadata.total_bytes);
    reference.set_snapshot_sha256(result.source_sha256);
    reference.set_archive_format(control::v2::SOURCE_ARCHIVE_FORMAT_TAR_ZSTD);
    reference.set_compressed_bytes(result.compressed_bytes);
}

} // namespace

WorkspaceProvenance capture_workspace_provenance(const fs::path& root, SourceLimits limits) {
    for (std::size_t attempt = 0; attempt < 2; ++attempt) {
        const auto before = git_snapshot(root);
        const auto hash = hash_workspace_source(root / "shaders", limits);
        const auto after = git_snapshot(root);
        if (before == after) {
            return {after.checkout_state, after.branch, after.head, after.shader_tree_id, hash,
                after.dirty ? provenance_delta_sha256(
                    "vibris-dirty-shader-delta-v1", after.shader_tree_id, hash) : std::string{}};
        }
    }
    throw StateError("SOURCE_CHANGED_DURING_SNAPSHOT",
        "Git shader provenance changed during both fingerprint attempts.", true);
}

std::string shader_content_delta_sha256(std::string_view measured, std::string_view completion) {
    if (measured == completion) return {};
    return provenance_delta_sha256("vibris-shader-content-delta-v1", measured, completion);
}

SourcePreparer::SourcePreparer(fs::path workspace_root, fs::path destination_root,
    SourceLimits limits, SourceDestinationLayout layout, std::function<void()> after_workspace_archive)
    : workspace_root_(std::move(workspace_root)), destination_root_(std::move(destination_root)),
      limits_(limits), layout_(layout), after_workspace_archive_(std::move(after_workspace_archive)) {
    std::error_code error;
    if (!fs::is_directory(destination_root_, error) || error) {
        throw StateError("SERVER_NOT_READY", "The source destination root is not accessible.", true);
    }
}

PreparedSource SourcePreparer::prepare_workspace() const {
    for (std::size_t attempt = 1; attempt <= 2; ++attempt) {
        auto target = create_destination(destination_root_, layout_);
        try {
            const auto before = git_snapshot(workspace_root_);
            auto result = write_workspace_source_archive(
                workspace_root_ / "shaders", target.archive, limits_);
            if (after_workspace_archive_) after_workspace_archive_();
            const auto current_hash = hash_workspace_source(workspace_root_ / "shaders", limits_);
            const auto after = git_snapshot(workspace_root_);
            if (before != after || result.source_sha256 != current_hash) {
                throw StateError("SOURCE_CHANGED_DURING_SNAPSHOT",
                    "The workspace source changed while it was archived.", true);
            }
            const WorkspaceProvenance provenance{after.checkout_state, after.branch, after.head,
                after.shader_tree_id, result.source_sha256,
                after.dirty ? provenance_delta_sha256("vibris-dirty-shader-delta-v1",
                    after.shader_tree_id, result.source_sha256) : std::string{}};
            control::v2::PreparedSourceRef reference;
            reference.set_source_uuid(target.uuid);
            set_archive_fields(reference, result);
            reference.set_requested_revision("workspace");
            reference.set_resolved_revision(after.head);
            reference.set_vcs_checkout_state(after.checkout_state);
            reference.set_branch(after.branch);
            reference.set_start_head(after.head);
            reference.set_shader_tree_id(after.shader_tree_id);
            reference.set_dirty_shader_delta_sha256(provenance.dirty_shader_delta_sha256);
            auto* origin = reference.mutable_origin()->mutable_workspace();
            origin->set_worktree_root(workspace_root_.string());
            origin->set_display_name(workspace_root_.filename().string());
            return PreparedSource(std::move(reference), std::move(target.owned),
                std::move(target.archive), attempt, {}, after.head);
        } catch (const StateError& failure) {
            std::error_code ignored;
            fs::remove_all(target.owned, ignored);
            if (attempt == 1 && failure.code() == "SOURCE_CHANGED_DURING_SNAPSHOT") continue;
            throw;
        } catch (...) {
            std::error_code ignored;
            fs::remove_all(target.owned, ignored);
            throw;
        }
    }
    throw StateError("INTERNAL_ERROR", "Workspace source preparation exhausted its retry boundary.");
}

PreparedSource SourcePreparer::prepare_commit(std::string_view revision) const {
    GitRepository repository(workspace_root_);
    const auto resolved = repository.resolve_commit(revision);
    auto target = create_destination(destination_root_, layout_);
    try {
        const auto maximum_tar = maximum_source_archive_bytes(limits_);
        auto result = write_commit_source_archive(
            repository.open_shader_archive(resolved, maximum_tar), target.archive, limits_);
        const auto workspace = git_snapshot(workspace_root_);
        control::v2::PreparedSourceRef reference;
        reference.set_source_uuid(target.uuid);
        set_archive_fields(reference, result);
        reference.set_requested_revision(std::string(revision));
        reference.set_resolved_revision(resolved);
        reference.set_vcs_checkout_state(workspace.checkout_state);
        reference.set_branch(workspace.branch);
        reference.set_start_head(workspace.head);
        reference.set_shader_tree_id(repository.shader_tree_id(resolved));
        auto* origin = reference.mutable_origin()->mutable_commit();
        origin->set_repository_id(workspace_root_.filename().string());
        origin->set_revision(resolved);
        origin->set_worktree_root(workspace_root_.string());
        return PreparedSource(std::move(reference), std::move(target.owned),
            std::move(target.archive), 1, std::string(revision), resolved,
            {result.input_archive_bytes, result.largest_input_read_bytes,
                result.metadata.file_count, result.metadata.total_bytes});
    } catch (...) {
        std::error_code ignored;
        fs::remove_all(target.owned, ignored);
        throw;
    }
}

} // namespace vibris::mcp
