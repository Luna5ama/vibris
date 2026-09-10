#pragma once

#include "git_repository.hpp"
#include "source_types.hpp"
#include "workspace_copier.hpp"

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <string>

namespace vibris::mcp {

[[nodiscard]] std::uint64_t maximum_source_archive_bytes(SourceLimits limits);

struct SourceArchiveResult final {
    WorkspaceMetadata metadata;
    std::string source_sha256;
    std::uint64_t compressed_bytes;
    std::uint64_t input_archive_bytes = 0;
    std::size_t largest_input_read_bytes = 0;
};

[[nodiscard]] SourceArchiveResult write_workspace_source_archive(
    const std::filesystem::path& source,
    const std::filesystem::path& archive_path,
    SourceLimits limits);

[[nodiscard]] SourceArchiveResult write_commit_source_archive(
    GitArchivePipe archive,
    const std::filesystem::path& archive_path,
    SourceLimits limits);

[[nodiscard]] std::string hash_workspace_source(
    const std::filesystem::path& source,
    SourceLimits limits);

} // namespace vibris::mcp
