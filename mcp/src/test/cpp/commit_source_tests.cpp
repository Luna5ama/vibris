#include "commit_extractor.hpp"
#include "git_repository.hpp"
#include "source_path_policy.hpp"
#include "source_preparer.hpp"
#include "state_error.hpp"

#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace fs = std::filesystem;
using vibris::mcp::GitRepository;
using vibris::mcp::SourceEntry;
using vibris::mcp::SourceEntryKind;
using vibris::mcp::SourceLimits;
using vibris::mcp::SourcePathPolicy;
using vibris::mcp::SourcePreparer;
using vibris::mcp::StateError;
namespace {
constexpr std::uint64_t kMiB = 1024ULL * 1024ULL;

void require(bool condition, std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

class TempDirectory final {
public:
    explicit TempDirectory(std::string_view label)
        : path_(fs::temp_directory_path() /
            ("vibris-commit-" + std::string(label) + "-" +
                std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_);
    }

    TempDirectory(const TempDirectory&) = delete;
    TempDirectory& operator=(const TempDirectory&) = delete;

    ~TempDirectory() {
        std::error_code ignored;
        fs::remove_all(path_, ignored);
    }

    [[nodiscard]] const fs::path& path() const noexcept {
        return path_;
    }
private:
    fs::path path_;
};

void run_git(const fs::path& repository, std::string_view arguments) {
    const std::string command = "git -C \"" + repository.string() + "\" " + std::string(arguments);
    require(std::system(command.c_str()) == 0, "Git fixture command failed: " + command);
}

void initialize_repository(const fs::path& repository) {
    run_git(repository, "init -q");
    run_git(repository, "config user.email vibris-test@example.invalid");
    run_git(repository, "config user.name VibrisTest");
}

void commit_all(const fs::path& repository, std::string_view message) {
    run_git(repository, "add -A");
    run_git(repository, "commit -q -m " + std::string(message));
}

void write_text(const fs::path& path, std::string_view text) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary);
    output.write(text.data(), static_cast<std::streamsize>(text.size()));
    require(output.good(), "Could not write Git fixture text.");
}

void write_sized_file(const fs::path& path, std::uint64_t size) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary);
    output.seekp(static_cast<std::streamoff>(size - 1));
    output.put('\0');
    require(output.good(), "Could not write the 50 MiB Git fixture.");
}

std::string read_text(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Expected extracted fixture file to exist.");
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::string capture_git(const fs::path& repository, std::string_view arguments) {
    const auto output_path = repository / ".git" / "vibris-test-output";
    const std::string command = "git -C \"" + repository.string() + "\" " + std::string(arguments) +
        " > \"" + output_path.string() + "\"";
    require(std::system(command.c_str()) == 0, "Git fixture command failed: " + command);
    auto output = read_text(output_path);
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n')) {
        output.pop_back();
    }
    require(!output.empty(), "Git fixture command produced no output.");
    return output;
}

bool contains_tar(const fs::path& root) {
    for (const auto& entry : fs::recursive_directory_iterator(root)) {
        if (entry.is_regular_file() && entry.path().extension() == ".tar") {
            return true;
        }
    }
    return false;
}

template <typename Action>
std::string capture_error_code(Action&& action) {
    try {
        action();
    } catch (const StateError& error) {
        return std::string(error.code());
    }
    throw std::runtime_error("Expected StateError, but the source operation succeeded.");
}

void valid_relative_archive_entry() {
    // Given: a regular archive member below the required shaders root prefix.
    const SourceEntry entry{"shaders/lib/noise.glsl", SourceEntryKind::regular_file, false};

    // When: the archive path policy removes and validates the root prefix.
    const auto relative = SourcePathPolicy{}.archive_relative_path(entry);

    // Then: extraction receives only the safe relative destination.
    require(relative == fs::path("lib") / "noise.glsl", "A valid archive entry changed its relative path.");
}

void reject_archive_traversal() {
    // Given: parent traversal and absolute archive members disguised beneath shader input.
    const SourcePathPolicy policy;

    // When: both untrusted member names reach the shared archive path policy.
    const auto parent = capture_error_code([&policy] {
        static_cast<void>(policy.archive_relative_path(
            {"shaders/lib/../../escape.glsl", SourceEntryKind::regular_file, false}));
    });
    const auto absolute = capture_error_code([&policy] {
        static_cast<void>(policy.archive_relative_path(
            {"C:/escape.glsl", SourceEntryKind::regular_file, false}));
    });

    // Then: neither path can become an extraction destination.
    require(parent == "SOURCE_CONTAINS_REPARSE_POINT", "Parent traversal returned the wrong error code.");
    require(absolute == "SOURCE_CONTAINS_REPARSE_POINT", "Absolute traversal returned the wrong error code.");
}

void reject_symlink_or_reparse() {
    // Given: one symbolic-link entry and one regular entry carrying the Windows reparse attribute.
    const SourcePathPolicy policy;

    // When: both non-ordinary entries reach the shared archive path policy.
    const auto symlink = capture_error_code([&policy] {
        static_cast<void>(policy.archive_relative_path(
            {"shaders/link", SourceEntryKind::symlink, false}));
    });
    const auto reparse = capture_error_code([&policy] {
        static_cast<void>(policy.archive_relative_path(
            {"shaders/cache.bin", SourceEntryKind::regular_file, true}));
    });

    // Then: neither link-like entry is followed or extracted.
    require(symlink == "SOURCE_CONTAINS_REPARSE_POINT", "Symlink input returned the wrong error code.");
    require(reparse == "SOURCE_CONTAINS_REPARSE_POINT", "Reparse input returned the wrong error code.");
}

void reject_win32_archive_components() {
    // Given: archive components that Win32 treats as devices or silently normalizes.
    constexpr std::array<std::string_view, 20> paths{{
        "shaders/NUL",
        "shaders/lib/nul.txt",
        "shaders/CON.glsl",
        "shaders/AUX",
        "shaders/PRN.log",
        "shaders/COM1",
        "shaders/com9.bin",
        "shaders/COM\xC2\xB9.txt",
        "shaders/LPT1",
        "shaders/lpt9.txt",
        "shaders/trailing.",
        "shaders/trailing ",
        "shaders/bad<name",
        "shaders/bad>name",
        "shaders/bad|name",
        "shaders/bad\"name",
        "shaders/bad?name",
        "shaders/bad*name",
        "shaders/control\x01.glsl",
        "shaders/double//separator",
    }};

    // When/Then: none can reach CreateFileW as a normalized or device destination.
    const SourcePathPolicy policy;
    for (const auto path : paths) {
        const auto code = capture_error_code([&policy, path] {
            static_cast<void>(policy.archive_relative_path(
                {std::string(path), SourceEntryKind::regular_file, false}));
        });
        require(code == "SOURCE_CONTAINS_REPARSE_POINT", "Unsafe Win32 component returned the wrong error code.");
    }
}

void reject_win32_device_name_commit_entry() {
    // Given: a real Git commit whose archive contains NUL.txt without creating that path in the worktree.
    TempDirectory repository_dir("device-repository");
    TempDirectory pending_dir("device-pending");
    initialize_repository(repository_dir.path());
    run_git(repository_dir.path(), "config core.protectNTFS false");
    write_text(repository_dir.path() / "payload.glsl", "must not disappear");
    run_git(repository_dir.path(), "add payload.glsl");
    const auto blob = capture_git(repository_dir.path(), "rev-parse :payload.glsl");
    run_git(repository_dir.path(), "rm --cached -q payload.glsl");
    run_git(repository_dir.path(), "update-index --add --cacheinfo 100644," + blob + ",shaders/NUL.txt");
    run_git(repository_dir.path(), "commit -q -m device-name");
    const SourceLimits limits{.max_total_bytes = kMiB, .max_files = 8};
    SourcePreparer preparer(repository_dir.path(), pending_dir.path(), limits);

    // When: commit preparation streams that archive into its staging directory.
    std::string code;
    try {
        const auto prepared = preparer.prepare_commit("HEAD");
        throw std::runtime_error("NUL.txt archive entry was accepted with " +
            std::to_string(prepared.reference().file_count()) + " promoted files.");
    } catch (const StateError& error) {
        code = error.code();
    }

    // Then: the device alias is rejected instead of reporting a successful but empty source.
    require(code == "SOURCE_CONTAINS_REPARSE_POINT", "The NUL.txt archive entry returned the wrong error code.");
}

void commit_archive_streaming_50_mib() {
    // Given: a 50 MiB shader commit that differs from the current worktree.
    TempDirectory repository_dir("stream-repository");
    TempDirectory extraction_dir("stream-extraction");
    initialize_repository(repository_dir.path());
    write_sized_file(repository_dir.path() / "shaders" / "payload.bin", 50 * kMiB);
    write_text(repository_dir.path() / "shaders" / "marker.glsl", "archived");
    commit_all(repository_dir.path(), "archive-source");
    GitRepository repository(repository_dir.path());
    const auto archived_sha = repository.resolve_commit("HEAD");
    fs::remove(repository_dir.path() / "shaders" / "payload.bin");
    write_text(repository_dir.path() / "shaders" / "marker.glsl", "working");
    commit_all(repository_dir.path(), "current-worktree");
    const SourceLimits limits{.max_total_bytes = 51 * kMiB, .max_files = 8};
    SourcePreparer preparer(repository_dir.path(), extraction_dir.path(), limits);

    // When: Git tar input is converted directly into the final compressed archive.
    auto prepared = preparer.prepare_commit(archived_sha);
    const auto& stats = prepared.archive_stats();

    // Then: conversion is bounded, writes only tar.zst, and leaves the worktree untouched.
    require(fs::is_regular_file(prepared.archive()) &&
            prepared.reference().archive_format() == vibris::control::v2::SOURCE_ARCHIVE_FORMAT_TAR_ZSTD &&
            prepared.reference().compressed_bytes() == fs::file_size(prepared.archive()),
        "The commit source was not written as one final tar.zst archive.");
    require(stats.largest_read_bytes > 0 && stats.largest_read_bytes <= kMiB, "Archive reads exceeded 1 MiB.");
    require(stats.extracted_file_count == 2, "The archive extraction file count was not exposed.");
    require(stats.extracted_total_bytes == 50 * kMiB + 8, "The archive extraction byte count was not exposed.");
    require(!contains_tar(extraction_dir.path()), "Commit extraction created an intermediate tar file.");
    require(!fs::exists(prepared.directory() / "tree"), "Commit preparation created an unpacked tree.");
    require(!fs::exists(repository_dir.path() / "shaders" / "payload.bin"), "Commit preparation changed the worktree.");
    require(read_text(repository_dir.path() / "shaders" / "marker.glsl") == "working",
        "Commit preparation checked out the requested revision.");
}

void distinct_uuid_without_dedup() {
    // Given: one immutable commit and a pending source root with room for two preparations.
    TempDirectory repository_dir("uuid-repository");
    TempDirectory pending_dir("uuid-pending");
    initialize_repository(repository_dir.path());
    write_text(repository_dir.path() / "shaders" / "main.glsl", "void main() {}\n");
    commit_all(repository_dir.path(), "shader-source");
    const SourceLimits limits{.max_total_bytes = kMiB, .max_files = 8};
    SourcePreparer preparer(repository_dir.path(), pending_dir.path(), limits);

    // When: identical commit content is prepared twice.
    auto first = preparer.prepare_commit("HEAD");
    auto second = preparer.prepare_commit("HEAD");
    auto workspace = preparer.prepare_workspace();

    // Then: each preparation owns an independent UUID archive without hash reuse or deduplication.
    require(first.reference().source_uuid() != second.reference().source_uuid(),
        "Identical commits reused a source UUID.");
    require(first.reference().requested_revision() == "HEAD" &&
            first.reference().resolved_revision().size() == 40 &&
            first.reference().resolved_revision() == first.reference().origin().commit().revision(),
        "Commit provenance omitted the requested revision or resolved full commit.");
    require(first.directory() != second.directory(), "Identical commits reused a source directory.");
    require(first.reference().snapshot_sha256() == second.reference().snapshot_sha256() &&
            first.reference().snapshot_sha256() == workspace.reference().snapshot_sha256(),
        "Commit and workspace packaging disagreed on the source hash.");
    require(fs::is_regular_file(first.archive()) && fs::is_regular_file(second.archive()),
        "An identical commit did not retain both compressed archives.");
    require(!fs::exists(first.directory() / "main.glsl") && !fs::exists(second.directory() / "main.glsl"),
        "Commit preparation wrote a naked source tree.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 7> test_cases{{
    {"ValidRelativeArchiveEntry", valid_relative_archive_entry},
    {"RejectArchiveTraversal", reject_archive_traversal},
    {"RejectSymlinkOrReparse", reject_symlink_or_reparse},
    {"RejectWin32ArchiveComponents", reject_win32_archive_components},
    {"RejectWin32DeviceNameCommitEntry", reject_win32_device_name_commit_entry},
    {"CommitArchiveStreaming50MiB", commit_archive_streaming_50_mib},
    {"DistinctUuidWithoutDedup", distinct_uuid_without_dedup},
}};

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-commit-source-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown commit source test scenario: " << argv[1] << '\n';
    return 2;
}
