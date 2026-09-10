#include "source_preparer.hpp"

#include <system_error>
#include <utility>

namespace vibris::mcp {

PreparedSource::PreparedSource(control::v2::PreparedSourceRef reference,
    std::filesystem::path owned_path, std::filesystem::path archive_path, std::size_t attempts,
    std::string requested_revision, std::string resolved_revision, ArchiveExtractionStats archive_stats)
    : reference_(std::move(reference)), owned_path_(std::move(owned_path)),
      archive_path_(std::move(archive_path)), attempts_(attempts),
      archive_stats_(archive_stats), requested_revision_(std::move(requested_revision)),
      resolved_revision_(std::move(resolved_revision)),
      owns_path_(true) {}

PreparedSource::PreparedSource(PreparedSource&& other)
    : reference_(std::move(other.reference_)), owned_path_(std::move(other.owned_path_)),
      archive_path_(std::move(other.archive_path_)), attempts_(other.attempts_),
      archive_stats_(other.archive_stats_),
      requested_revision_(std::move(other.requested_revision_)),
      resolved_revision_(std::move(other.resolved_revision_)),
      owns_path_(std::exchange(other.owns_path_, false)) {}

PreparedSource& PreparedSource::operator=(PreparedSource&& other) {
    if (this != &other) {
        cleanup();
        reference_ = std::move(other.reference_);
        owned_path_ = std::move(other.owned_path_);
        archive_path_ = std::move(other.archive_path_);
        attempts_ = other.attempts_;
        archive_stats_ = other.archive_stats_;
        requested_revision_ = std::move(other.requested_revision_);
        resolved_revision_ = std::move(other.resolved_revision_);
        owns_path_ = std::exchange(other.owns_path_, false);
    }
    return *this;
}

PreparedSource::~PreparedSource() { cleanup(); }
const control::v2::PreparedSourceRef& PreparedSource::reference() const noexcept { return reference_; }
const std::filesystem::path& PreparedSource::directory() const noexcept { return owned_path_; }
const std::filesystem::path& PreparedSource::archive() const noexcept { return archive_path_; }
const ArchiveExtractionStats& PreparedSource::archive_stats() const noexcept { return archive_stats_; }
std::size_t PreparedSource::attempts() const noexcept { return attempts_; }
std::string_view PreparedSource::requested_revision() const noexcept { return requested_revision_; }
std::string_view PreparedSource::resolved_revision() const noexcept { return resolved_revision_; }
void PreparedSource::release() noexcept { owns_path_ = false; }

void PreparedSource::cleanup() noexcept {
    if (!owns_path_) return;
    std::error_code ignored;
    std::filesystem::remove_all(owned_path_, ignored);
    owns_path_ = false;
}

} // namespace vibris::mcp
