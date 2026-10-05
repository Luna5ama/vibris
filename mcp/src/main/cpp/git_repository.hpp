#pragma once

#include <chrono>
#include <cstddef>
#include <filesystem>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {

struct GitRepositorySecurityAccess;

class GitArchivePipe final {
public:
    GitArchivePipe(const GitArchivePipe&) = delete;
    GitArchivePipe& operator=(const GitArchivePipe&) = delete;
    GitArchivePipe(GitArchivePipe&& other) noexcept;
    GitArchivePipe& operator=(GitArchivePipe&& other) noexcept;
    ~GitArchivePipe();

    [[nodiscard]] std::size_t read(std::span<std::byte> buffer);
    [[nodiscard]] int wait();
    [[nodiscard]] const std::string& diagnostics() const noexcept { return stderr_text_; }

private:
    GitArchivePipe(void* process, void* stdout_read, void* stderr_read,
        std::chrono::steady_clock::time_point deadline) noexcept;
    void close() noexcept;
    void timeout();
    void capture_output(std::size_t max_bytes, std::string_view overflow_code,
        std::string_view overflow_message);

    void* process_ = nullptr;
    void* stdout_read_ = nullptr;
    void* stderr_read_ = nullptr;
    std::chrono::steady_clock::time_point deadline_{};
    std::string stderr_text_;
    std::vector<std::byte> captured_output_;
    std::size_t captured_offset_ = 0;
    int exit_code_ = 0;
    bool captured_ = false;
    bool waited_ = false;

    friend class GitRepository;
    friend struct GitRepositorySecurityAccess;
};

class GitRepository final {
public:
    explicit GitRepository(std::filesystem::path repository);

    [[nodiscard]] std::string resolve_commit(std::string_view revision) const;
    [[nodiscard]] std::string current_branch() const;
    [[nodiscard]] std::string shader_tree_id(std::string_view revision) const;
    [[nodiscard]] bool shader_worktree_dirty() const;
    [[nodiscard]] std::string apply_shader_patch(std::string_view commit,
        const std::filesystem::path& patch, const std::filesystem::path& index) const;
    [[nodiscard]] GitArchivePipe open_shader_archive(
        std::string_view full_sha, std::size_t max_archive_bytes) const;

private:
    [[nodiscard]] std::string query(
        const std::vector<std::wstring>& arguments, std::size_t max_stdout_bytes,
        bool allow_nonzero = false) const;
    static GitArchivePipe launch_git(const std::vector<std::wstring>& arguments,
        const std::filesystem::path& executable = {}, std::size_t max_stdout_bytes = 0,
        std::string_view overflow_code = "INTERNAL_ERROR",
        std::string_view overflow_message = "Git output exceeded its limit.",
        const std::filesystem::path& index = {});

    std::filesystem::path repository_;
    friend struct GitRepositorySecurityAccess;
};

}
