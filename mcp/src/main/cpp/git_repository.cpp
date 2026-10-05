#include "git_repository.hpp"

#include "git_process.hpp"
#include "state_error.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <ranges>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

using Handle = HANDLE;

void close_handle(void*& value) noexcept {
    if (value != nullptr) {
        CloseHandle(static_cast<Handle>(value));
        value = nullptr;
    }
}

struct PipePair final {
    Handle read = nullptr;
    Handle write = nullptr;
    PipePair() = default;
    PipePair(const PipePair&) = delete;
    PipePair& operator=(const PipePair&) = delete;
    PipePair(PipePair&& other) noexcept
        : read(std::exchange(other.read, nullptr)), write(std::exchange(other.write, nullptr)) {
    }
    ~PipePair() {
        if (read != nullptr) CloseHandle(read);
        if (write != nullptr) CloseHandle(write);
    }
};

PipePair make_pipe(const DWORD buffer_size) {
    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    PipePair pipe;
    if (!CreatePipe(&pipe.read, &pipe.write, &security, buffer_size) ||
        !SetHandleInformation(pipe.read, HANDLE_FLAG_INHERIT, 0)) {
        throw StateError("INTERNAL_ERROR", "Unable to create a Git process pipe.", true);
    }
    return pipe;
}

enum class DiagnosticDrainResult {
    complete,
    budget_exhausted,
    deadline,
    failed,
};

DiagnosticDrainResult drain_diagnostic(Handle pipe, std::string& output,
    const std::chrono::steady_clock::time_point deadline) noexcept {
    if (pipe == nullptr) return DiagnosticDrainResult::complete;
    std::array<char, 4096> buffer{};
    std::size_t budget = 64 * 1024;
    while (budget != 0) {
        if (std::chrono::steady_clock::now() >= deadline) return DiagnosticDrainResult::deadline;
        DWORD available = 0;
        if (!PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr)) {
            return GetLastError() == ERROR_BROKEN_PIPE ?
                DiagnosticDrainResult::complete : DiagnosticDrainResult::failed;
        }
        if (available == 0) return DiagnosticDrainResult::complete;
        const auto requested = (std::min)({available, static_cast<DWORD>(buffer.size()),
            static_cast<DWORD>(budget)});
        DWORD read = 0;
        if (!ReadFile(pipe, buffer.data(), requested, &read, nullptr)) {
            return GetLastError() == ERROR_BROKEN_PIPE ?
                DiagnosticDrainResult::complete : DiagnosticDrainResult::failed;
        }
        if (read == 0) return DiagnosticDrainResult::complete;
        budget -= read;
        if (output.size() < kGitMaxDiagnosticBytes) {
            output.append(buffer.data(), (std::min)(static_cast<std::size_t>(read),
                kGitMaxDiagnosticBytes - output.size()));
        }
    }
    return DiagnosticDrainResult::budget_exhausted;
}

bool is_full_sha(std::string_view value) {
    return (value.size() == 40 || value.size() == 64) &&
        std::ranges::all_of(value, [](unsigned char character) { return std::isxdigit(character) != 0; });
}

}

GitArchivePipe::GitArchivePipe(void* process, void* stdout_read, void* stderr_read,
    const std::chrono::steady_clock::time_point deadline) noexcept
    : process_(process), stdout_read_(stdout_read), stderr_read_(stderr_read), deadline_(deadline) {
}

GitArchivePipe::GitArchivePipe(GitArchivePipe&& other) noexcept
    : process_(std::exchange(other.process_, nullptr)),
      stdout_read_(std::exchange(other.stdout_read_, nullptr)),
      stderr_read_(std::exchange(other.stderr_read_, nullptr)),
      deadline_(other.deadline_), stderr_text_(std::move(other.stderr_text_)),
      captured_output_(std::move(other.captured_output_)),
      captured_offset_(std::exchange(other.captured_offset_, 0)),
      exit_code_(other.exit_code_), captured_(std::exchange(other.captured_, false)),
      waited_(std::exchange(other.waited_, true)) {
}

GitArchivePipe& GitArchivePipe::operator=(GitArchivePipe&& other) noexcept {
    if (this != &other) {
        close();
        process_ = std::exchange(other.process_, nullptr);
        stdout_read_ = std::exchange(other.stdout_read_, nullptr);
        stderr_read_ = std::exchange(other.stderr_read_, nullptr);
        deadline_ = other.deadline_;
        stderr_text_ = std::move(other.stderr_text_);
        captured_output_ = std::move(other.captured_output_);
        captured_offset_ = std::exchange(other.captured_offset_, 0);
        exit_code_ = other.exit_code_;
        captured_ = std::exchange(other.captured_, false);
        waited_ = std::exchange(other.waited_, true);
    }
    return *this;
}

GitArchivePipe::~GitArchivePipe() {
    close();
}

void GitArchivePipe::timeout() {
    if (process_ != nullptr) static_cast<void>(terminate_and_reap_git(static_cast<Handle>(process_)));
    close_handle(stdout_read_);
    close_handle(stderr_read_);
    close_handle(process_);
    waited_ = true;
    throw StateError("INTERNAL_ERROR", "Git operation exceeded its five-second deadline.", true);
}

std::size_t GitArchivePipe::read(std::span<std::byte> buffer) {
    if (captured_) {
        const auto remaining = captured_output_.size() - captured_offset_;
        const auto count = (std::min)(buffer.size(), remaining);
        if (count != 0) {
            std::ranges::copy_n(captured_output_.data() + captured_offset_, count, buffer.data());
            captured_offset_ += count;
        }
        return count;
    }
    if (stdout_read_ == nullptr || buffer.empty()) return 0;
    for (;;) {
        if (std::chrono::steady_clock::now() >= deadline_) timeout();
        const auto diagnostic = drain_diagnostic(
            static_cast<Handle>(stderr_read_), stderr_text_, deadline_);
        if (diagnostic == DiagnosticDrainResult::deadline) timeout();
        if (diagnostic == DiagnosticDrainResult::failed) {
            throw StateError("INTERNAL_ERROR", "Unable to read Git diagnostics.", true);
        }
        DWORD available = 0;
        if (!PeekNamedPipe(static_cast<Handle>(stdout_read_), nullptr, 0, nullptr, &available, nullptr)) {
            if (GetLastError() == ERROR_BROKEN_PIPE) {
                close_handle(stdout_read_);
                return 0;
            }
            throw StateError("INTERNAL_ERROR", "Unable to inspect the Git archive pipe.", true);
        }
        if (available != 0) {
            const auto requested = static_cast<DWORD>((std::min<std::size_t>)({
                buffer.size(), static_cast<std::size_t>(available), 1024 * 1024}));
            DWORD read = 0;
            if (!ReadFile(static_cast<Handle>(stdout_read_), buffer.data(), requested, &read, nullptr)) {
                if (GetLastError() == ERROR_BROKEN_PIPE) {
                    close_handle(stdout_read_);
                    return 0;
                }
                throw StateError("INTERNAL_ERROR", "Unable to read the Git archive pipe.", true);
            }
            if (std::chrono::steady_clock::now() >= deadline_) timeout();
            return read;
        }
        if (WaitForSingleObject(static_cast<Handle>(process_), 0) == WAIT_OBJECT_0) {
            close_handle(stdout_read_);
            return 0;
        }
        if (std::chrono::steady_clock::now() >= deadline_) timeout();
        static_cast<void>(WaitForSingleObject(static_cast<Handle>(process_), kGitPollMilliseconds));
    }
}

int GitArchivePipe::wait() {
    if (process_ == nullptr) return exit_code_;
    std::array<std::byte, 4096> discard{};
    while (WaitForSingleObject(static_cast<Handle>(process_), 0) != WAIT_OBJECT_0) {
        if (std::chrono::steady_clock::now() >= deadline_) timeout();
        const auto diagnostic = drain_diagnostic(
            static_cast<Handle>(stderr_read_), stderr_text_, deadline_);
        if (diagnostic == DiagnosticDrainResult::deadline) timeout();
        if (diagnostic == DiagnosticDrainResult::failed) {
            throw StateError("INTERNAL_ERROR", "Unable to read Git diagnostics.", true);
        }
        if (stdout_read_ != nullptr) {
            DWORD available = 0;
            if (PeekNamedPipe(static_cast<Handle>(stdout_read_), nullptr, 0, nullptr, &available, nullptr) &&
                available != 0) {
                DWORD read = 0;
                static_cast<void>(ReadFile(static_cast<Handle>(stdout_read_), discard.data(),
                    (std::min)(available, static_cast<DWORD>(discard.size())), &read, nullptr));
            }
        }
        if (std::chrono::steady_clock::now() >= deadline_) timeout();
        static_cast<void>(WaitForSingleObject(static_cast<Handle>(process_), kGitPollMilliseconds));
    }
    static_cast<void>(drain_diagnostic(static_cast<Handle>(stderr_read_), stderr_text_, deadline_));
    DWORD exit_code = 0;
    if (!GetExitCodeProcess(static_cast<Handle>(process_), &exit_code)) {
        throw StateError("INTERNAL_ERROR", "Unable to read the Git exit status.", true);
    }
    close_handle(stdout_read_);
    close_handle(stderr_read_);
    close_handle(process_);
    waited_ = true;
    exit_code_ = static_cast<int>(exit_code);
    return exit_code_;
}

void GitArchivePipe::capture_output(const std::size_t max_bytes,
    const std::string_view overflow_code, const std::string_view overflow_message) {
    std::vector<std::byte> buffer(1024 * 1024);
    for (auto count = read(buffer); count != 0; count = read(buffer)) {
        if (count > max_bytes - (std::min)(captured_output_.size(), max_bytes)) {
            close();
            throw StateError(std::string(overflow_code), std::string(overflow_message));
        }
        captured_output_.insert(captured_output_.end(), buffer.begin(), buffer.begin() + count);
    }
    exit_code_ = wait();
    captured_ = true;
    captured_offset_ = 0;
}

void GitArchivePipe::close() noexcept {
    close_handle(stdout_read_);
    close_handle(stderr_read_);
    if (process_ != nullptr) {
        if (!waited_) static_cast<void>(terminate_and_reap_git(static_cast<Handle>(process_)));
        close_handle(process_);
    }
    waited_ = true;
}

GitArchivePipe GitRepository::launch_git(
    const std::vector<std::wstring>& arguments, const std::filesystem::path& requested_executable,
    const std::size_t max_stdout_bytes, const std::string_view overflow_code,
    const std::string_view overflow_message, const std::filesystem::path& index) {
    auto stdout_pipe = make_pipe(1024 * 1024);
    auto stderr_pipe = make_pipe(64 * 1024);
    const auto executable = requested_executable.empty() ? resolve_git_executable() : requested_executable;
    auto resolved = arguments;
    resolved.front() = executable.wstring();
    auto command = git_command_line(resolved);
    std::array<Handle, 2> inherited{stdout_pipe.write, stderr_pipe.write};
    SIZE_T attribute_bytes = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attribute_bytes);
    std::vector<std::byte> attributes(attribute_bytes);
    auto* attribute_list = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributes.data());
    if (!InitializeProcThreadAttributeList(attribute_list, 1, 0, &attribute_bytes)) {
        throw StateError("INTERNAL_ERROR", "Unable to restrict inherited Git process handles.", true);
    }
    if (!UpdateProcThreadAttribute(attribute_list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited.data(),
            sizeof(inherited), nullptr, nullptr)) {
        DeleteProcThreadAttributeList(attribute_list);
        throw StateError("INTERNAL_ERROR", "Unable to restrict inherited Git process handles.", true);
    }
    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = INVALID_HANDLE_VALUE;
    startup.StartupInfo.hStdOutput = stdout_pipe.write;
    startup.StartupInfo.hStdError = stderr_pipe.write;
    startup.lpAttributeList = attribute_list;
    PROCESS_INFORMATION process{};
    auto environment = sanitized_git_environment(index);
    const auto deadline = std::chrono::steady_clock::now() + kGitOperationTimeout;
    const BOOL created = CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, TRUE,
        EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT,
        environment.data(), nullptr, &startup.StartupInfo, &process);
    DeleteProcThreadAttributeList(attribute_list);
    if (!created) throw StateError("INTERNAL_ERROR", "Unable to start Git.", true);
    CloseHandle(process.hThread);
    CloseHandle(stdout_pipe.write);
    stdout_pipe.write = nullptr;
    CloseHandle(stderr_pipe.write);
    stderr_pipe.write = nullptr;
    auto result = GitArchivePipe(process.hProcess, stdout_pipe.read, stderr_pipe.read, deadline);
    stdout_pipe.read = nullptr;
    stderr_pipe.read = nullptr;
    if (max_stdout_bytes != 0) {
        result.capture_output(max_stdout_bytes, overflow_code, overflow_message);
    }
    return result;
}

GitRepository::GitRepository(std::filesystem::path repository)
    : repository_(std::filesystem::absolute(std::move(repository)).lexically_normal()) {
}

std::string GitRepository::resolve_commit(std::string_view revision) const {
    if (revision.empty()) throw StateError("COMMIT_NOT_FOUND", "Commit revision is empty.");
    auto process = launch_git({L"git", L"-C", repository_.wstring(), L"rev-parse", L"--verify",
        L"--end-of-options", std::filesystem::path(std::string(revision) + "^{commit}").wstring()}, {},
        8192, "COMMIT_NOT_FOUND", "Git revision output exceeded its limit.");
    std::array<std::byte, 4096> buffer{};
    std::string output;
    for (std::size_t read = process.read(buffer); read != 0; read = process.read(buffer)) {
        if (output.size() < 8192) {
            output.append(reinterpret_cast<const char*>(buffer.data()),
                (std::min)(read, 8192 - output.size()));
        }
    }
    const auto exit_code = process.wait();
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n')) output.pop_back();
    if (exit_code != 0 || !is_full_sha(output)) {
        throw StateError("COMMIT_NOT_FOUND", "Git could not resolve the requested commit.");
    }
    return output;
}

std::string GitRepository::query(const std::vector<std::wstring>& arguments,
    const std::size_t max_stdout_bytes, const bool allow_nonzero) const {
    auto process = launch_git(arguments, {}, max_stdout_bytes);
    std::array<std::byte, 4096> buffer{};
    std::string output;
    for (std::size_t read = process.read(buffer); read != 0; read = process.read(buffer)) {
        output.append(reinterpret_cast<const char*>(buffer.data()), read);
    }
    const auto exit_code = process.wait();
    if (exit_code != 0 && !allow_nonzero) {
        throw StateError("INTERNAL_ERROR", "Git provenance query failed.", true);
    }
    if (exit_code != 0) return {};
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n')) output.pop_back();
    return output;
}

std::string GitRepository::current_branch() const {
    return query({L"git", L"-C", repository_.wstring(), L"branch", L"--show-current"}, 8192);
}

std::string GitRepository::shader_tree_id(std::string_view revision) const {
    if (!is_full_sha(revision)) {
        throw StateError("COMMIT_NOT_FOUND", "Shader tree lookup requires a full commit SHA.");
    }
    const auto output = query({L"git", L"-C", repository_.wstring(), L"rev-parse", L"--verify",
        L"--end-of-options", std::filesystem::path(std::string(revision) + ":shaders").wstring()}, 8192);
    if (!is_full_sha(output)) {
        throw StateError("COMMIT_HAS_NO_SHADERS", "The resolved commit has no shaders tree.");
    }
    return output;
}

bool GitRepository::shader_worktree_dirty() const {
    return !query({L"git", L"-C", repository_.wstring(), L"status", L"--porcelain=v1", L"-z",
        L"--untracked-files=all", L"--", L"shaders"}, 16 * 1024 * 1024).empty();
}

std::string GitRepository::apply_shader_patch(std::string_view commit,
    const std::filesystem::path& patch, const std::filesystem::path& index) const {
    const auto run = [&](std::vector<std::wstring> args) {
        args.insert(args.begin(), {L"git", L"-C", repository_.wstring()});
        auto process = launch_git(args, {}, 16 * 1024 * 1024, "PATCH_APPLY_FAILED",
            "Patch query exceeded its limit.", index);
        std::array<std::byte, 4096> buffer{};
        std::string output;
        for (auto count = process.read(buffer); count != 0; count = process.read(buffer))
            output.append(reinterpret_cast<const char*>(buffer.data()), count);
        if (process.wait() != 0) throw StateError("PATCH_APPLY_FAILED",
            "Git could not apply the patch: " + process.diagnostics().substr(0, 2048));
        return output;
    };
    const auto absolute_patch = std::filesystem::absolute(patch).wstring();
    const auto stat = run({L"apply", L"--numstat", L"-z", L"--", absolute_patch});
    const auto validate_path = [](std::string_view path) {
        const std::filesystem::path value{std::string(path)};
        if (!path.starts_with("shaders/") || value.is_absolute() || value.has_root_name() ||
            std::any_of(value.begin(), value.end(), [](const auto& part) { return part == ".."; }))
            throw StateError("PATCH_OUTSIDE_SHADERS", "Patch path is outside shaders/: " + std::string(path));
    };
    std::size_t position = 0;
    const auto token = [&]() {
        const auto end = stat.find('\0', position);
        if (end == std::string::npos) throw StateError("PATCH_APPLY_FAILED", "Git emitted malformed patch paths.");
        const auto value = std::string_view(stat).substr(position, end - position);
        position = end + 1;
        return value;
    };
    while (position < stat.size()) {
        const auto entry = token();
        const auto first = entry.find('\t');
        const auto second = first == std::string_view::npos ? first : entry.find('\t', first + 1);
        if (second == std::string_view::npos) throw StateError("PATCH_APPLY_FAILED", "Git emitted malformed patch numstat.");
        const auto path = entry.substr(second + 1);
        if (path.empty()) { validate_path(token()); validate_path(token()); }
        else validate_path(path);
    }
    static_cast<void>(run({L"read-tree", std::filesystem::path(commit).wstring()}));
    static_cast<void>(run({L"apply", L"--cached", L"--check", L"--", absolute_patch}));
    static_cast<void>(run({L"apply", L"--cached", L"--", absolute_patch}));
    auto tree = run({L"write-tree"});
    while (!tree.empty() && (tree.back() == '\r' || tree.back() == '\n')) tree.pop_back();
    if (!is_full_sha(tree)) throw StateError("PATCH_APPLY_FAILED", "Git did not return the patched tree.");
    return tree;
}

GitArchivePipe GitRepository::open_shader_archive(
    std::string_view full_sha, const std::size_t max_archive_bytes) const {
    if (!is_full_sha(full_sha)) {
        throw StateError("COMMIT_NOT_FOUND", "Commit archive requires a full commit SHA.");
    }
    return launch_git({L"git", L"-C", repository_.wstring(), L"archive", L"--format=tar",
        std::filesystem::path(full_sha).wstring(), L"shaders"}, {}, max_archive_bytes,
        "SOURCE_TOO_LARGE", "Commit archive exceeded its bounded capture size.");
}

}
