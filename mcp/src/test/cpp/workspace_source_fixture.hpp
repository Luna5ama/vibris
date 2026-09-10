#pragma once

#include "source_preparer.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <chrono>
#include <concepts>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace vibris::mcp::test {

namespace fs = std::filesystem;

static_assert(std::movable<PreparedSource>);
static_assert(!std::copyable<PreparedSource>);

class TempDirectory final {
public:
    explicit TempDirectory(std::string_view label)
        : path_(fs::temp_directory_path() /
            ("vibris-" + std::string(label) + "-" +
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

inline void require(bool condition, std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

inline void write_file(const fs::path& path, std::string_view bytes) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    require(output.good(), "Could not create workspace fixture file.");
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    require(output.good(), "Could not write workspace fixture file.");
}

inline std::string read_file(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Expected prepared source file to exist.");
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

inline void replace_with_file_symlink(const fs::path& link, const fs::path& target) {
    require(fs::remove(link), "Could not remove the checked workspace fixture file.");
    if (!CreateSymbolicLinkW(
            link.c_str(), target.c_str(), SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE)) {
        throw std::runtime_error("Could not create workspace fixture symlink: " + std::to_string(GetLastError()));
    }
}

inline void run_git(const fs::path& worktree, std::string_view arguments) {
    const auto command = "git -C \"" + worktree.string() + "\" " + std::string(arguments);
    require(std::system(command.c_str()) == 0, "Could not create Git workspace fixture.");
}

class WorkspaceFixture final {
public:
    WorkspaceFixture()
        : temp_("workspace-source"), worktree_(temp_.path() / "worktree"), pending_(temp_.path() / "pending") {
        fs::create_directories(worktree_ / "shaders" / "lib");
        fs::create_directory(worktree_ / "shaders" / "empty");
        fs::create_directories(pending_);
        write_file(worktree_ / ".gitignore", "shaders/ignored.properties\n");
        write_file(worktree_ / "shaders" / "composite.fsh", "tracked-composite");
        write_file(worktree_ / "shaders" / "lib" / "live.glsl", "live-0");
        run_git(worktree_, "init --quiet");
        run_git(worktree_, "config user.email vibris-tests@example.invalid");
        run_git(worktree_, "config user.name vibris-tests");
        run_git(worktree_, "config core.autocrlf false");
        run_git(worktree_, "add .gitignore shaders/composite.fsh shaders/lib/live.glsl");
        run_git(worktree_, "commit --quiet -m fixture");
        write_file(worktree_ / "shaders" / "untracked.glsl", "untracked-source");
        write_file(worktree_ / "shaders" / "ignored.properties", "ignored-source");
        run_git(worktree_, "ls-files --error-unmatch shaders/composite.fsh");
        run_git(worktree_, "check-ignore -q shaders/ignored.properties");
    }

    [[nodiscard]] const fs::path& worktree() const noexcept {
        return worktree_;
    }

    [[nodiscard]] const fs::path& pending() const noexcept {
        return pending_;
    }

    [[nodiscard]] fs::path shaders() const {
        return worktree_ / "shaders";
    }

    [[nodiscard]] fs::path live_file() const {
        return shaders() / "lib" / "live.glsl";
    }

private:
    TempDirectory temp_;
    fs::path worktree_;
    fs::path pending_;
};

inline std::pair<std::uint64_t, std::uint64_t> file_totals(const fs::path& root) {
    std::uint64_t count = 0;
    std::uint64_t bytes = 0;
    for (const auto& entry : fs::recursive_directory_iterator(root)) {
        if (entry.is_regular_file()) {
            ++count;
            bytes += entry.file_size();
        }
    }
    return {count, bytes};
}

inline SourceLimits generous_limits() {
    return {.max_total_bytes = 1024 * 1024, .max_files = 128};
}

inline bool pending_has_no_sources(const fs::path& pending) {
    return fs::is_empty(pending);
}

struct ErrorSnapshot final {
    std::string code;
    bool retryable;
};

template <typename Action>
ErrorSnapshot capture_state_error(Action&& action) {
    try {
        std::forward<Action>(action)();
    } catch (const StateError& error) {
        return {std::string(error.code()), error.retryable()};
    }
    throw std::runtime_error("Expected StateError, but source preparation succeeded.");
}

} // namespace vibris::mcp::test
