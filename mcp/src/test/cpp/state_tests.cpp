#include "backend_state_fixture.hpp"
#include "config_document.hpp"
#include "git_executable_resolver.hpp"
#include "mcp_backend.hpp"
#include "mcp_stdio_server.hpp"
#include "result_mapper.hpp"
#include "state_error.hpp"
#include "tool_registry.hpp"
#include "workspace_identity_store.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <process.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <barrier>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_set>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using vibris::mcp::JobContext;
using vibris::mcp::StateError;
using vibris::mcp::WorkspaceIdentityStore;
namespace proto = ::vibris::control::v2;

namespace {

class McpBackend final {
public:
    McpBackend(const fs::path& root, std::string address)
        : root_(fs::canonical(root)), backend_(std::make_unique<vibris::mcp::McpBackend>(std::move(address))) {}

    [[nodiscard]] vibris::mcp::ToolOutcome dispatch(std::string_view name, const vibris::mcp::Json& arguments) {
        auto scoped = arguments;
        if (!scoped.contains("worktree_root")) scoped["worktree_root"] = root_.string();
        return backend_->dispatch(name, scoped);
    }

    [[nodiscard]] std::optional<vibris::mcp::GrpcClientStats> shutdown() { return backend_->shutdown(); }

private:
    fs::path root_;
    std::unique_ptr<vibris::mcp::McpBackend> backend_;
};

void initialize_git_repository(const fs::path& repository);

class TempDirectory final {
public:
    explicit TempDirectory(std::string_view label)
        : path_(fs::temp_directory_path() /
            ("vibris-state-" + std::string(label) + "-" +
                std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_);
        try {
            initialize_git_repository(path_);
        } catch (...) {
            std::error_code ignored;
            fs::remove_all(path_, ignored);
            throw;
        }
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

void initialize_git_repository(const fs::path& repository) {
    const auto executable = vibris::mcp::resolve_git_executable();
    const auto repository_argument = repository.wstring();
    const std::array<const wchar_t*, 6> arguments {
        L"git", L"init", L"--quiet", L"--", repository_argument.c_str(), nullptr};

    errno = 0;
    const auto result = _wspawnv(_P_WAIT, executable.c_str(), arguments.data());
    if (result != 0) {
        throw std::runtime_error(
            "Real Git worktree fixture initialization failed with exit " + std::to_string(result) +
            " and errno " + std::to_string(errno) + ".");
    }
}

struct ErrorSnapshot final {
    std::string code;
    bool retryable;
    std::size_t message_size;
};

void require(bool condition, std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

template <typename Action>
ErrorSnapshot capture_state_error(Action&& action) {
    try {
        action();
    } catch (const StateError& error) {
        return {std::string(error.code()), error.retryable(), std::string_view(error.what()).size()};
    }
    throw std::runtime_error("Expected StateError, but the operation succeeded.");
}

JobContext valid_config() {
    return {
        1,
        "11111111-1111-4111-8111-111111111111",
        "shaders",
        "shader-test-world",
        "minecraft:overworld",
        "sunset",
        "village-rooftop",
        70.0,
        32,
    };
}

std::string read_bytes(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Expected persisted config file to exist.");
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

void write_bytes(const fs::path& path, std::string_view bytes) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    require(output.good(), "Unable to create test state file.");
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    require(output.good(), "Unable to write test state file.");
}

vibris::mcp::Json require_json(
    const vibris::mcp::ToolOutcome& outcome, std::string_view message) {
    const auto* result = std::get_if<vibris::mcp::Json>(&outcome);
    require(result != nullptr, message);
    return *result;
}

using IdentityIoOperation = vibris::mcp::detail::WorkspaceIdentityIoOperation;

class IdentityIoHooks final : public vibris::mcp::detail::WorkspaceIdentityIoHooks {
public:
    enum class Substitution {
        none,
        regular,
        reparse,
    };

    std::barrier<>* move_gate = nullptr;
    std::optional<IdentityIoOperation> forced_operation;
    std::uint32_t forced_error = ERROR_ACCESS_DENIED;
    std::string external_winner;
    bool hold_external_winner = false;
    std::size_t release_winner_after_retries = 0;
    Substitution substitution = Substitution::none;
    fs::path substitution_target;
    std::size_t create_collision_budget = 0;
    std::string collision_sentinel;
    std::vector<fs::path> collision_paths;
    std::atomic<std::size_t> move_winners = 0;
    std::atomic<std::size_t> move_losers = 0;
    std::atomic<std::size_t> cleanup_successes = 0;
    std::atomic<std::size_t> cleanup_failures = 0;
    std::atomic<bool> external_winner_written = false;
    std::atomic<bool> setup_failed = false;
    std::atomic<bool> substitution_attempted = false;
    std::atomic<bool> substitution_succeeded = false;
    std::atomic<std::size_t> create_collisions = 0;
    std::atomic<std::size_t> winner_retries = 0;
    HANDLE held_winner = INVALID_HANDLE_VALUE;

    ~IdentityIoHooks() override {
        if (held_winner != INVALID_HANDLE_VALUE) {
            CloseHandle(held_winner);
        }
    }

    void before(IdentityIoOperation operation, const fs::path& temporary_path,
        const fs::path& identity_path) noexcept override {
        if (operation == IdentityIoOperation::winner_retry) {
            const auto retries = ++winner_retries;
            if (held_winner != INVALID_HANDLE_VALUE &&
                retries >= release_winner_after_retries) {
                CloseHandle(held_winner);
                held_winner = INVALID_HANDLE_VALUE;
            }
            return;
        }
        if (operation == IdentityIoOperation::create &&
            collision_paths.size() < create_collision_budget) {
            try {
                collision_paths.push_back(temporary_path);
                write_bytes(temporary_path, collision_sentinel);
            } catch (...) {
                setup_failed = true;
            }
        }
        if (operation == IdentityIoOperation::move && !external_winner.empty() &&
            !external_winner_written.exchange(true)) {
            try {
                write_bytes(identity_path, external_winner);
                if (hold_external_winner) {
                    held_winner = CreateFileW(identity_path.c_str(), GENERIC_WRITE | DELETE,
                        FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (held_winner == INVALID_HANDLE_VALUE) {
                        setup_failed = true;
                    }
                }
            } catch (...) {
                setup_failed = true;
            }
        }
        if (operation == IdentityIoOperation::move && substitution != Substitution::none &&
            !substitution_attempted.exchange(true)) {
            const auto displaced = temporary_path.wstring() + L".displaced";
            if (MoveFileExW(temporary_path.c_str(), displaced.c_str(), MOVEFILE_REPLACE_EXISTING)) {
                if (substitution == Substitution::regular) {
                    try {
                        write_bytes(temporary_path,
                            vibris::mcp::Json {{"schema_version", 2},
                                {"workspace_id", "33333333-3333-4333-8333-333333333333"}}.dump(2));
                        substitution_succeeded = true;
                    } catch (...) {
                        setup_failed = true;
                    }
                } else {
                    substitution_succeeded = CreateSymbolicLinkW(temporary_path.c_str(),
                        substitution_target.c_str(), 0) != 0;
                    setup_failed = !substitution_succeeded;
                }
            }
        }
        if (operation == IdentityIoOperation::move && move_gate) {
            move_gate->arrive_and_wait();
        }
    }

    std::optional<std::uint32_t> injected_error(
        IdentityIoOperation operation, const fs::path&, const fs::path&) noexcept override {
        if (forced_operation && *forced_operation == operation) {
            return forced_error;
        }
        return std::nullopt;
    }

    void after(IdentityIoOperation operation, const fs::path&, const fs::path&,
        bool success, std::uint32_t error) noexcept override {
        if (operation == IdentityIoOperation::create && !success &&
            (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS)) {
            ++create_collisions;
        }
        if (operation == IdentityIoOperation::move) {
            success ? ++move_winners : ++move_losers;
        }
        if (operation == IdentityIoOperation::cleanup) {
            success ? ++cleanup_successes : ++cleanup_failures;
        }
    }
};

class TestHandle final {
public:
    explicit TestHandle(HANDLE handle) : handle_(handle) {
        require(handle_ != INVALID_HANDLE_VALUE, "Unable to create the locked state-file fixture.");
    }

    TestHandle(const TestHandle&) = delete;
    TestHandle& operator=(const TestHandle&) = delete;

    ~TestHandle() {
        CloseHandle(handle_);
    }

private:
    HANDLE handle_;
};

bool has_identity_temp(const fs::path& directory) {
    std::error_code error;
    if (!fs::is_directory(directory, error) || error) {
        return false;
    }
    for (const auto& entry : fs::directory_iterator(directory)) {
        if (entry.path().filename().string().starts_with("vibris-workspace.json.tmp.")) {
            return true;
        }
    }
    return false;
}

std::string nested_value(std::size_t container_count) {
    std::string value = "0";
    for (std::size_t index = 0; index < container_count; ++index) {
        value = "{\"n\":" + value + "}";
    }
    return value;
}

void workspace_identity_concurrent_first_use() {
    TempDirectory temp("identity-first-use");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    constexpr std::size_t contender_count = 32;
    std::array<std::string, contender_count> identities;
    std::array<std::exception_ptr, contender_count> errors;
    std::barrier start(static_cast<std::ptrdiff_t>(contender_count));
    std::vector<std::jthread> contenders;
    contenders.reserve(contender_count);
    for (std::size_t index = 0; index < contender_count; ++index) {
        contenders.emplace_back([&, index] {
            try {
                WorkspaceIdentityStore store(identity_path);
                start.arrive_and_wait();
                identities[index] = store.load_or_create();
            } catch (...) {
                errors[index] = std::current_exception();
            }
        });
    }
    contenders.clear();

    for (const auto& error : errors) {
        if (error) {
            std::rethrow_exception(error);
        }
    }
    require(vibris::mcp::detail::is_uuid(identities.front()), "First-use identity is not a UUID.");
    for (const auto& identity : identities) {
        require(identity == identities.front(), "First-use contenders observed divergent workspace IDs.");
    }

    const auto document = vibris::mcp::Json::parse(read_bytes(identity_path));
    require(document.is_object() && document.size() == 2, "Workspace identity schema is not strict.");
    require(document.at("schema_version") == 2, "Workspace identity schema version changed.");
    require(document.at("workspace_id") == identities.front(), "Persisted identity differs from the winner.");
    for (const auto& entry : fs::directory_iterator(identity_path.parent_path())) {
        require(!entry.path().filename().string().starts_with("vibris-workspace.json.tmp."),
                "First-use publication left a temporary identity file.");
    }
}

void workspace_identity_deterministic_publication() {
    TempDirectory temp("identity-deterministic-publication");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    std::barrier move_gate(2);
    IdentityIoHooks hooks;
    hooks.move_gate = &move_gate;
    std::array<std::string, 2> identities;
    std::array<std::exception_ptr, 2> errors;
    std::array<std::jthread, 2> contenders {
        std::jthread([&] {
            try {
                identities[0] = WorkspaceIdentityStore(identity_path, hooks).load_or_create();
            } catch (...) {
                errors[0] = std::current_exception();
            }
        }),
        std::jthread([&] {
            try {
                identities[1] = WorkspaceIdentityStore(identity_path, hooks).load_or_create();
            } catch (...) {
                errors[1] = std::current_exception();
            }
        }),
    };
    for (auto& contender : contenders) {
        contender.join();
    }
    for (const auto& error : errors) {
        if (error) {
            std::rethrow_exception(error);
        }
    }

    require(identities[0] == identities[1], "Deterministic contenders observed different workspace IDs.");
    require(hooks.move_winners == 1, "Deterministic publication did not have exactly one move winner.");
    require(hooks.move_losers >= 1, "Deterministic publication did not exercise the loser path.");
    require(hooks.cleanup_successes >= 1 && hooks.cleanup_failures == 0,
        "Deterministic publication did not check loser cleanup.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Deterministic publication left a temporary identity file.");
    require(vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id") == identities[0],
        "Deterministic publication returned an identity other than the published winner.");
}

void workspace_identity_io_failures_cleanup() {
    const std::array failures {
        std::pair {IdentityIoOperation::write, static_cast<std::uint32_t>(ERROR_WRITE_FAULT)},
        std::pair {IdentityIoOperation::flush, static_cast<std::uint32_t>(ERROR_WRITE_FAULT)},
        std::pair {IdentityIoOperation::move, static_cast<std::uint32_t>(ERROR_ACCESS_DENIED)},
    };
    for (const auto& [operation, error_code] : failures) {
        TempDirectory temp("identity-io-failure");
        const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
        IdentityIoHooks hooks;
        hooks.forced_operation = operation;
        hooks.forced_error = error_code;
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path, hooks).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Injected identity I/O failure returned the wrong error contract.");
        require(error.message_size <= 512, "Injected identity I/O error was not bounded.");
        require(!fs::exists(identity_path), "Injected identity I/O failure published an identity.");
        require(hooks.cleanup_successes >= 1 && !has_identity_temp(identity_path.parent_path()),
            "Injected identity I/O failure did not remove its temporary file.");
    }

    TempDirectory cleanup_temp("identity-cleanup-failure");
    const auto cleanup_identity = cleanup_temp.path() / ".codex" / "vibris-workspace.json";
    const auto winner_id = std::string("22222222-2222-4222-8222-222222222222");
    const auto winner_bytes = vibris::mcp::Json {
        {"schema_version", 2}, {"workspace_id", winner_id}}.dump(2);
    IdentityIoHooks cleanup_hooks;
    cleanup_hooks.external_winner = winner_bytes;
    cleanup_hooks.forced_operation = IdentityIoOperation::cleanup;
    cleanup_hooks.forced_error = ERROR_ACCESS_DENIED;
    const auto cleanup_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(cleanup_identity, cleanup_hooks).load_or_create());
    });
    require(cleanup_error.code == "CONFIG_IO_ERROR" && cleanup_error.retryable,
        "Checked loser-cleanup failure returned the wrong error contract.");
    require(cleanup_hooks.cleanup_failures >= 1, "Checked loser-cleanup failure was not observed.");
    require(read_bytes(cleanup_identity) == winner_bytes, "Cleanup failure changed the published winner.");
    require(!has_identity_temp(cleanup_identity.parent_path()),
        "Cleanup failure fallback left a temporary identity file.");
}

void workspace_identity_malformed_winner() {
    TempDirectory temp("identity-malformed-winner");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const std::string malformed = R"({"schema_version":2,"workspace_id":"not-a-uuid"})";
    IdentityIoHooks hooks;
    hooks.external_winner = malformed;
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path, hooks).load_or_create());
    });
    require(!hooks.setup_failed, "Malformed-winner fixture setup failed.");
    require(error.code == "INVALID_CONFIG" && !error.retryable,
        "Malformed publication winner returned the wrong error contract.");
    require(hooks.move_losers == 1 && hooks.cleanup_successes == 1,
        "Malformed publication winner did not exercise checked loser cleanup.");
    require(read_bytes(identity_path) == malformed, "Malformed publication winner was replaced.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Malformed publication winner left a temporary identity file.");
}

void workspace_identity_retries_only_publication_loser() {
    TempDirectory initial("identity-initial-read-no-retry");
    const auto initial_identity = initial.path() / ".codex" / "vibris-workspace.json";
    static_cast<void>(WorkspaceIdentityStore(initial_identity).load_or_create());
    IdentityIoHooks initial_hooks;
    {
        TestHandle writer(CreateFileW(initial_identity.c_str(), GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(initial_identity, initial_hooks).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Initial busy identity read returned the wrong error contract.");
    }
    require(initial_hooks.winner_retries == 0,
        "Initial identity read entered the publication-loser retry path.");

    TempDirectory loser("identity-publication-loser-retry");
    const auto loser_identity = loser.path() / ".codex" / "vibris-workspace.json";
    const auto winner_id = std::string("55555555-5555-4555-8555-555555555555");
    const auto winner_bytes = vibris::mcp::Json {
        {"schema_version", 2}, {"workspace_id", winner_id}}.dump(2);
    IdentityIoHooks loser_hooks;
    loser_hooks.external_winner = winner_bytes;
    loser_hooks.hold_external_winner = true;
    loser_hooks.release_winner_after_retries = 3;
    const auto returned = WorkspaceIdentityStore(loser_identity, loser_hooks).load_or_create();
    require(!loser_hooks.setup_failed && loser_hooks.winner_retries == 3,
        "Publication loser did not use the bounded winner-only retry path.");
    require(loser_hooks.held_winner == INVALID_HANDLE_VALUE,
        "Publication-loser fixture did not release the winner handle.");
    require(returned == winner_id && read_bytes(loser_identity) == winner_bytes,
        "Publication-loser retry returned an ID different from the durable winner.");
    require(!has_identity_temp(loser_identity.parent_path()),
        "Publication-loser retry left an owned temporary identity file.");

    TempDirectory exhausted("identity-publication-loser-retry-exhausted");
    const auto exhausted_identity = exhausted.path() / ".codex" / "vibris-workspace.json";
    IdentityIoHooks exhausted_hooks;
    exhausted_hooks.external_winner = winner_bytes;
    exhausted_hooks.hold_external_winner = true;
    exhausted_hooks.release_winner_after_retries =
        vibris::mcp::detail::kMaxWorkspaceIdentityWinnerReadRetries + 1;
    const auto exhausted_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(exhausted_identity, exhausted_hooks).load_or_create());
    });
    require(!exhausted_hooks.setup_failed && exhausted_error.code == "CONFIG_IO_ERROR" &&
            exhausted_error.retryable,
        "Exhausted publication-loser retry returned the wrong error contract.");
    require(exhausted_hooks.winner_retries ==
            vibris::mcp::detail::kMaxWorkspaceIdentityWinnerReadRetries,
        "Publication-loser retry exceeded its exact bounded wait count.");
    require(!has_identity_temp(exhausted_identity.parent_path()),
        "Exhausted publication-loser retry left an owned temporary identity file.");
}

void workspace_identity_blocks_temp_substitution() {
    const std::array substitutions {
        IdentityIoHooks::Substitution::regular,
        IdentityIoHooks::Substitution::reparse,
    };
    for (const auto substitution : substitutions) {
        TempDirectory temp("identity-temp-substitution");
        const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
        const auto reparse_target = temp.path() / "attacker-identity.json";
        write_bytes(reparse_target,
            vibris::mcp::Json {{"schema_version", 2},
                {"workspace_id", "44444444-4444-4444-8444-444444444444"}}.dump(2));
        IdentityIoHooks hooks;
        hooks.substitution = substitution;
        hooks.substitution_target = reparse_target;
        const auto returned = WorkspaceIdentityStore(identity_path, hooks).load_or_create();
        require(hooks.substitution_attempted, "Temporary-path substitution hook did not run.");
        require(!hooks.substitution_succeeded && !hooks.setup_failed,
            "Attacker replaced the temporary identity pathname while publication owned it.");
        const auto attributes = GetFileAttributesW(identity_path.c_str());
        require(attributes != INVALID_FILE_ATTRIBUTES &&
                (attributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0 &&
                (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0,
            "Published workspace identity is not an ordinary file.");
        const auto persisted = vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id").get<std::string>();
        require(returned == persisted,
            "Publication returned a workspace ID different from the durable file.");
    }
}

void workspace_identity_preserves_create_collision() {
    TempDirectory temp("identity-create-collision");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    IdentityIoHooks hooks;
    hooks.create_collision_budget = 1;
    hooks.collision_sentinel = "attacker-owned-sentinel";
    const auto returned = WorkspaceIdentityStore(identity_path, hooks).load_or_create();
    require(hooks.collision_paths.size() == 1 && hooks.create_collisions == 1 && !hooks.setup_failed,
        "Unique-temp collision fixture did not exercise exactly one retry.");
    require(read_bytes(hooks.collision_paths.front()) == hooks.collision_sentinel,
        "Workspace identity publication deleted or changed an unowned collision entry.");
    require(vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id") == returned,
        "Collision retry returned an ID different from the durable identity.");
    require(DeleteFileW(hooks.collision_paths.front().c_str()) != 0,
        "Unable to remove the test-owned collision sentinel.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Collision retry left an owned workspace identity temporary file.");

    TempDirectory exhausted("identity-create-collision-exhausted");
    const auto exhausted_identity = exhausted.path() / ".codex" / "vibris-workspace.json";
    IdentityIoHooks exhausted_hooks;
    exhausted_hooks.create_collision_budget =
        vibris::mcp::detail::kMaxWorkspaceIdentityTempCreateAttempts;
    exhausted_hooks.collision_sentinel = "bounded-attacker-owned-sentinel";
    const auto exhausted_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(exhausted_identity, exhausted_hooks).load_or_create());
    });
    require(exhausted_error.code == "CONFIG_IO_ERROR" && exhausted_error.retryable,
        "Exhausted unique-temp collisions returned the wrong error contract.");
    require(exhausted_hooks.collision_paths.size() ==
            vibris::mcp::detail::kMaxWorkspaceIdentityTempCreateAttempts &&
            exhausted_hooks.create_collisions == exhausted_hooks.collision_paths.size() &&
            !exhausted_hooks.setup_failed,
        "Unique-temp collision retries were not bounded at the explicit limit.");
    std::unordered_set<std::wstring> collision_names;
    for (const auto& path : exhausted_hooks.collision_paths) {
        collision_names.insert(path.filename().wstring());
        require(read_bytes(path) == exhausted_hooks.collision_sentinel,
            "Exhausted collision retry changed an unowned sentinel.");
        require(DeleteFileW(path.c_str()) != 0,
            "Unable to remove an exhausted test-owned collision sentinel.");
    }
    require(collision_names.size() == exhausted_hooks.collision_paths.size(),
        "Unique-temp collision retry reused a candidate name.");
    require(!fs::exists(exhausted_identity) && !has_identity_temp(exhausted_identity.parent_path()),
        "Exhausted collision retry published an identity or left an owned temp.");
}

void workspace_identity_rejects_duplicate_keys() {
    TempDirectory temp("identity-duplicate-key");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    write_bytes(identity_path,
        R"({"schema_version":2,"workspace_id":"11111111-1111-4111-8111-111111111111","workspace_id":"22222222-2222-4222-8222-222222222222"})");
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    });
    require(error.code == "INVALID_CONFIG", "Duplicate identity keys were accepted.");
}

void workspace_identity_rejects_unsupported_version() {
    TempDirectory temp("identity-unsupported-version");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const std::string bytes = vibris::mcp::Json{
        {"schema_version", 2 - 1},
        {"workspace_id", "11111111-1111-4111-8111-111111111111"},
    }.dump();
    write_bytes(identity_path, bytes);
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    });
    require(error.code == "UNSUPPORTED_VERSION" && !error.retryable && error.message_size <= 512,
        "Unsupported identity schema returned the wrong bounded error contract.");
    require(read_bytes(identity_path) == bytes, "Unsupported workspace identity was rewritten.");
}

void config_rejects_unsupported_version() {
    auto document = vibris::mcp::Json::parse(vibris::mcp::detail::serialize_config(valid_config()));
    document["schema_version"] = 2 - 1;
    const auto error = capture_state_error([&] {
        static_cast<void>(vibris::mcp::detail::parse_config(
            document.dump(), vibris::mcp::detail::ConfigDocumentKind::persisted));
    });
    require(error.code == "UNSUPPORTED_VERSION" && !error.retryable && error.message_size <= 512,
        "Unsupported config schema returned the wrong bounded error contract.");
}

void workspace_identity_enforces_json_depth() {
    constexpr auto maximum_depth = vibris::mcp::detail::kMaxWorkspaceIdentityJsonNestingDepth;
    const auto expected_id = valid_config().workspace_id;
    const auto document = [&](std::size_t nested_containers) {
        return "{\"schema_version\":2,\"workspace_id\":\"" + expected_id +
            "\",\"extra\":" + nested_value(nested_containers) + "}";
    };

    for (const auto nested_containers : {maximum_depth - 1, maximum_depth}) {
        TempDirectory identity_temp("identity-depth");
        const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
        const auto bytes = document(nested_containers);
        write_bytes(identity_path, bytes);
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
        });
        require(error.code == "INVALID_CONFIG" && !error.retryable && error.message_size <= 512,
            "Nested identity returned the wrong bounded error contract.");
        require(read_bytes(identity_path) == bytes, "Nested identity was rewritten.");
    }
}

void workspace_identity_classifies_nonregular_state() {
    TempDirectory identity_temp("identity-directory");
    const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
    fs::create_directories(identity_path);
    const auto identity_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    });
    require(identity_error.code == "INVALID_CONFIG", "Nonregular identity returned the wrong error.");

}

void workspace_identity_rejects_reparse_state() {
    TempDirectory state_link("identity-state-link");
    const auto linked_state = state_link.path() / "linked-state";
    const auto workspace = state_link.path() / "workspace";
    fs::create_directories(linked_state);
    fs::create_directories(workspace);
    std::error_code link_error;
    fs::create_directory_symlink(linked_state, workspace / ".codex", link_error);
    require(!link_error, "Unable to create the state-directory reparse fixture.");
    const auto state_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(workspace / ".codex" / "vibris-workspace.json").load_or_create());
    });
    require(state_error.code == "CONFIG_IO_ERROR" && state_error.retryable,
        "Reparse workspace state directory returned the wrong error contract.");
    require(!fs::exists(linked_state / "vibris-workspace.json"),
        "Reparse workspace state directory redirected identity publication.");

    TempDirectory identity_link("identity-file-link");
    const auto identity_state = identity_link.path() / ".codex";
    const auto identity_target = identity_link.path() / "identity-target.json";
    const std::string identity_target_bytes = R"({"schema_version":2,"workspace_id":"11111111-1111-4111-8111-111111111111"})";
    write_bytes(identity_target, identity_target_bytes);
    fs::create_directories(identity_state);
    link_error.clear();
    fs::create_symlink(identity_target, identity_state / "vibris-workspace.json", link_error);
    require(!link_error, "Unable to create the identity-file reparse fixture.");
    const auto identity_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_state / "vibris-workspace.json").load_or_create());
    });
    require(identity_error.code == "INVALID_CONFIG" && !identity_error.retryable,
        "Reparse workspace identity returned the wrong error contract.");
    require(read_bytes(identity_target) == identity_target_bytes, "Reparse workspace identity target changed.");

}

void workspace_identity_maps_access_errors() {
    TempDirectory identity_temp("identity-access-error");
    const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
    static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    {
        TestHandle locked(CreateFileW(identity_path.c_str(), GENERIC_READ, 0, nullptr, OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL, nullptr));
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Workspace identity access failure returned the wrong error contract.");
    }

}

void workspace_identity_rejects_active_writer() {
    TempDirectory temp("identity-active-writer");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    TestHandle writer(CreateFileW(identity_path.c_str(), GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path).load_or_create());
    });
    require(error.code == "CONFIG_IO_ERROR" && error.retryable && error.message_size <= 512,
        "Active identity writer did not produce a bounded retryable I/O error.");

}

void same_worktree_backends_coexist() {
    TempDirectory temp("same-worktree-backends");
    fs::create_directories(temp.path() / "shaders");
    const auto pending_root = temp.path() / "pending";
    fs::create_directories(pending_root);
    const auto identity_path = temp.path() / ".vibris" / "workspace.json";
    write_bytes(identity_path,
        vibris::mcp::Json{{"schema_version", 2}, {"workspace_id", valid_config().workspace_id}}.dump(2));
    vibris::mcp::test::BackendStateServer server(pending_root);

    McpBackend first(temp.path(), server.target());
    McpBackend second(temp.path(), server.target());

    const auto scope = vibris::mcp::Json{{"worktree_root", fs::canonical(temp.path()).string()}};

    const auto& first_status = require_json(
        first.dispatch("vibris_get_status", scope), "First same-root status failed.");
    const auto& second_status = require_json(
        second.dispatch("vibris_get_status", scope), "Second same-root status failed.");
    require(first_status.at("workspace_id") == valid_config().workspace_id &&
            second_status.at("workspace_id") == valid_config().workspace_id,
        "Same-worktree backends observed different durable workspace IDs.");

    const vibris::mcp::Json actions{
        {"worktree_root", fs::canonical(temp.path()).string()},
        {"preset_id", "scene-a"},
        {"actions", vibris::mcp::Json::array({{{"type", "inspect_shader"}}})},
    };
    static_cast<void>(require_json(
        first.dispatch("vibris_run_actions", actions), "First same-root action failed."));
    static_cast<void>(require_json(
        second.dispatch("vibris_run_actions", actions), "Second same-root action failed."));

    const auto first_stats = first.shutdown();
    const auto second_stats = second.shutdown();
    require(first_stats && second_stats && first_stats->worker_threads_started == 1 &&
            first_stats->worker_threads_joined == 1 && second_stats->worker_threads_started == 1 &&
            second_stats->worker_threads_joined == 1,
        "Same-worktree backends did not cleanly own independent gRPC workers.");
    const auto hellos = server.service().hellos();
    require(hellos.size() == 2 && hellos[0].nested_workspace_id == valid_config().workspace_id &&
            hellos[1].nested_workspace_id == valid_config().workspace_id &&
            hellos[0].process_id != hellos[1].process_id,
        "Same-worktree backends did not share identity while retaining process ownership.");
}

void request_scoped_worktree_routing() {
    TempDirectory first("request-worktree-a");
    TempDirectory second("request-worktree-b");
    fs::create_directories(first.path() / "shaders");
    fs::create_directories(second.path() / "shaders");
    const auto pending_root = first.path() / "pending";
    fs::create_directories(pending_root);
    const auto first_identity = first.path() / ".vibris" / "workspace.json";
    const auto second_identity = second.path() / ".vibris" / "workspace.json";
    const std::string second_workspace_id = "22222222-2222-4222-8222-222222222222";
    write_bytes(first_identity,
        vibris::mcp::Json{{"schema_version", 2}, {"workspace_id", valid_config().workspace_id}}.dump(2));
    write_bytes(second_identity,
        vibris::mcp::Json{{"schema_version", 2}, {"workspace_id", second_workspace_id}}.dump(2));
    const auto first_identity_bytes = read_bytes(first_identity);
    const auto second_identity_bytes = read_bytes(second_identity);
    vibris::mcp::test::BackendStateServer server(pending_root);
    vibris::mcp::McpBackend backend(server.target());

    const auto run = [&](const fs::path& root, std::string_view preset) {
        return require_json(backend.dispatch("vibris_run_actions", {
            {"worktree_root", fs::canonical(root).string()},
            {"preset_id", preset},
            {"actions", vibris::mcp::Json::array({{{"type", "inspect_shader"}}})},
        }), "Request-scoped action failed.");
    };
    const auto& first_a = run(first.path(), "scene-a");
    require(first_a.at("workspace_id") == valid_config().workspace_id &&
            first_a.at("worktree_root") == fs::canonical(first.path()).string(),
        "First request was not scoped to its explicit worktree.");
    const auto& second_b = run(second.path(), "scene-b");
    require(second_b.at("workspace_id") == second_workspace_id &&
            second_b.at("worktree_root") == fs::canonical(second.path()).string(),
        "Second request was not scoped to its explicit worktree.");
    auto first_alias = fs::canonical(first.path()).string();
    first_alias.front() = std::islower(static_cast<unsigned char>(first_alias.front()))
        ? static_cast<char>(std::toupper(static_cast<unsigned char>(first_alias.front())))
        : static_cast<char>(std::tolower(static_cast<unsigned char>(first_alias.front())));
    const auto& first_c = run(fs::path(first_alias), "scene-c");
    require(first_c.at("workspace_id") == valid_config().workspace_id,
        "Returning to the first worktree lost its request-scoped runtime.");

    const auto missing_root = backend.dispatch("vibris_get_status", vibris::mcp::Json::object());
    const auto* missing_failure = std::get_if<vibris::mcp::ToolFailure>(&missing_root);
    require(missing_failure != nullptr && missing_failure->code == "INVALID_WORKTREE",
        "Backend accepted a request without worktree_root.");

    const auto stats = backend.shutdown();
    require(stats && stats->completion_queue_count == 2 && stats->worker_threads_started == 2 &&
            stats->worker_threads_joined == 2 && stats->pending_requests == 0,
        "Per-worktree gRPC routing did not cleanly own two runtimes.");
    const auto hellos = server.service().hellos();
    const auto jobs = server.service().jobs();
    require(hellos.size() == 2 && jobs.size() == 3,
        "Explicit worktree routing created the wrong number of runtime clients or jobs.");
    std::unordered_set<std::string> hello_workspaces;
    for (const auto& hello : hellos) hello_workspaces.insert(hello.nested_workspace_id);
    require(hello_workspaces == std::unordered_set<std::string>{valid_config().workspace_id, second_workspace_id},
        "Per-worktree clients did not use distinct durable identities.");
    const std::array expected_workspaces{
        valid_config().workspace_id, second_workspace_id, valid_config().workspace_id};
    const std::array expected_saves{std::string("save-a"), std::string("save-b"), std::string("save-c")};
    for (std::size_t index = 0; index < jobs.size(); ++index) {
        require(jobs[index].nested_workspace_id == expected_workspaces[index] &&
                jobs[index].context.save_id() == expected_saves[index],
            "A request inherited another worktree or preset context.");
    }
    require(server.service().validation_count() == 3,
        "Each request-scoped scene was not independently validated.");
    require(read_bytes(first_identity) == first_identity_bytes &&
            read_bytes(second_identity) == second_identity_bytes,
        "Request execution changed durable workspace identity state.");
}

void typed_preset_discovery_and_request_scene() {
    TempDirectory temp("typed-preset-discovery");
    fs::create_directories(temp.path() / "shaders");
    const auto pending_root = temp.path() / "pending";
    fs::create_directories(pending_root);
    const auto identity_path = temp.path() / ".vibris" / "workspace.json";
    write_bytes(identity_path,
        vibris::mcp::Json{{"schema_version", 2}, {"workspace_id", valid_config().workspace_id}}.dump(2));
    vibris::mcp::test::BackendStateServer server(
        pending_root, vibris::mcp::test::PresetCatalogKind::benchmark_19);
    McpBackend backend(temp.path(), server.target());

    const auto root = fs::canonical(temp.path()).string();

    const auto all_outcome = backend.dispatch("vibris_list_presets", {{"worktree_root", root}});
    const auto& all = require_json(all_outcome, "Known preset catalog listing failed.");
    require(all.at("presets").size() == 19, "Known preset catalog did not contain exactly 19 scenes.");
    for (const auto& [tag, count] : std::array<std::pair<const char*, std::size_t>, 4>{
             {{"sky", 6}, {"aerial-perspective", 4}, {"raster", 1}, {"shadow", 1}}}) {
        const auto filtered_outcome = backend.dispatch(
            "vibris_list_presets", {{"worktree_root", root}, {"tags", vibris::mcp::Json::array({tag})}});
        const auto& filtered = require_json(filtered_outcome, "Preset tag filtering failed.");
        require(filtered.at("presets").size() == count, "Preset tag filter returned the wrong scene count.");
        for (const auto& preset : filtered.at("presets")) {
            require(std::find(preset.at("tags").begin(), preset.at("tags").end(), tag) !=
                    preset.at("tags").end(),
                "Preset tag filter returned a scene without the requested tag.");
        }
    }
    const auto combined_outcome = backend.dispatch("vibris_list_presets", {
        {"worktree_root", root}, {"preset_id", "sky-noon-1"},
        {"tags", vibris::mcp::Json::array({"sky"})},
    });
    const auto& combined = require_json(combined_outcome, "Combined preset filters failed.");
    if (combined.at("presets").size() != 1 ||
        combined.at("presets").front().at("preset_id") != "sky-noon-1") {
        throw std::runtime_error(
            "Text and tag filters did not combine case-insensitively: " + combined.dump());
    }

    const auto& executed = require_json(backend.dispatch("vibris_run_actions", {
        {"worktree_root", root},
        {"preset_id", "sky-noon-1"},
        {"actions", vibris::mcp::Json::array({{{"type", "inspect_shader"}}})},
    }), "Request-scoped preset execution failed.");
    require(executed.at("workspace_id") == valid_config().workspace_id,
        "Request-scoped preset execution lost its workspace identity.");
    const auto validated = server.service().validated();
    require(validated.size() == 1 && validated.front().weather_preset_id() == "clear" &&
            validated.front().resolution().width() == 64 &&
            validated.front().save_id() == "save-sky-noon-1",
        "Request-scoped preset validation did not submit the complete resolved context.");

    const auto missing = backend.dispatch("vibris_run_actions", {
        {"worktree_root", root},
        {"preset_id", "not-in-catalog"},
        {"actions", vibris::mcp::Json::array({{{"type", "inspect_shader"}}})},
    });
    const auto* failure = std::get_if<vibris::mcp::ToolFailure>(&missing);
    require(failure != nullptr && failure->code == "INVALID_PRESET" && !failure->retryable,
        "Unknown request-scoped preset returned the wrong error contract.");
    static_cast<void>(backend.shutdown());
}

void tool_metadata_is_request_scoped() {
    const vibris::mcp::ToolRegistry tools;
    require(tools.definitions().size() == 10, "Tool registry did not expose exactly ten v2 tools.");
    for (const auto& definition : tools.definitions()) {
        const auto name = definition.at("name").get<std::string>();
        const auto description = definition.at("description").get<std::string>();
        require(description.find("worktree") != std::string::npos,
            "Tool metadata does not describe explicit worktree routing.");
        const auto& schema = definition.at("inputSchema");
        require(!schema.contains("oneOf"), "Tool schema retained a top-level oneOf.");
        const auto& required = schema.at("required");
        require(std::find(required.begin(), required.end(), "worktree_root") != required.end(),
            "Tool schema omitted required worktree_root.");
        require(definition.at("schema_version") == 2 && definition.contains("outputSchema"),
            "Tool metadata omitted the v2 output contract.");
        require(name != "vibris_configure" && name != "vibris_get_config",
            "Ambient configuration tool remained registered.");
    }
}

void resource_lists_are_empty() {
    TempDirectory temp("empty-resource-lists");
    fs::create_directories(temp.path() / ".git");

    McpBackend backend(temp.path(), "127.0.0.1:50051");
    const vibris::mcp::ToolRegistry tools(
        [&backend](std::string_view name, const vibris::mcp::Json& arguments) {
            return backend.dispatch(name, arguments);
        });
    std::istringstream input(
        R"({"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}})" "\n"
        R"({"jsonrpc":"2.0","id":2,"method":"resources/templates/list","params":{}})" "\n");
    std::ostringstream output;

    require(vibris::mcp::McpStdioServer(input, output, tools).run() == 0, "Resource listing server failed.");
    std::istringstream responses(output.str());
    std::string line;
    require(static_cast<bool>(std::getline(responses, line)), "resources/list returned no response.");
    const auto resources = vibris::mcp::Json::parse(line);
    require(resources.at("result").at("resources").empty(), "resources/list must return an empty resource array.");
    require(static_cast<bool>(std::getline(responses, line)), "resources/templates/list returned no response.");
    const auto templates = vibris::mcp::Json::parse(line);
    require(templates.at("result").at("resourceTemplates").empty(),
            "resources/templates/list must return an empty resource template array.");
}

void status_lease_transition_mapping() {
    proto::GetStatusResponse response;
    response.mutable_protocol_version()->set_major(3);
    response.mutable_protocol_version()->set_minor(0);
    response.set_wait_satisfied(true);
    auto* status = response.mutable_status();
    status->set_state(proto::SERVER_STATE_OCCUPIED);
    status->set_can_accept_job(true);
    status->set_can_start_job(false);
    status->mutable_readiness()->set_core_online(true);
    status->mutable_readiness()->set_minecraft_connected(true);
    status->mutable_readiness()->set_phase(proto::RUNTIME_PHASE_RELOADING_SHADERS);
    auto* lease = status->mutable_active_lease();
    lease->set_lease_id("lease-a");
    lease->set_workspace_id("workspace-a");
    lease->set_worktree_root("I:/fixture/a");
    lease->set_job_id("job-a");
    lease->set_request_id("request-a");
    lease->set_operation("action_sequence");
    lease->set_stage(proto::JOB_STAGE_COMPILING);
    lease->set_started_at_unix_ms(100);
    lease->set_cancellation_requested(true);
    auto* queued = status->add_queue();
    queued->set_job_id("job-b");
    queued->set_workspace_id("workspace-b");
    queued->set_operation("benchmark");
    queued->set_position(1);
    queued->set_queued_at_unix_ms(101);
    auto* transition = status->add_transitions();
    transition->set_sequence(7);
    transition->set_from_state(proto::SERVER_STATE_AVAILABLE);
    transition->set_to_state(proto::SERVER_STATE_OCCUPIED);
    transition->set_runtime_phase(proto::RUNTIME_PHASE_RELOADING_SHADERS);
    transition->set_job_id("job-a");
    transition->set_reason("job-started");
    auto* failure = status->mutable_last_error();
    failure->set_code(proto::ERROR_CODE_SERVER_NOT_AVAILABLE);
    failure->set_message("runtime disconnected");
    failure->set_recovery_action("Reconnect the runtime bridge.");

    const auto mapped = vibris::mcp::ResultMapper::status(response);
    require(mapped.at("wait_satisfied") == true && mapped.at("wait_timed_out") == false,
        "Status wait flags were not mapped truthfully.");
    const auto& mapped_status = mapped.at("status");
    require(mapped_status.at("operational") == true && mapped_status.at("can_accept_job") == true,
        "Operational status did not preserve the agent admission gate.");
    require(!mapped_status.contains("state") && !mapped_status.contains("can_start_job") &&
            !mapped_status.contains("readiness") && !mapped_status.contains("active_lease") &&
            !mapped_status.contains("transitions") && !mapped_status.contains("last_error"),
        "Operational status leaked internal runtime transitions to the agent.");
    require(mapped_status.at("queue").size() == 1 &&
            mapped_status.at("queue").front().at("position") == 1,
        "Fair queue position was not preserved by the MCP result mapper.");

    failure->set_code(proto::ERROR_CODE_INTERNAL);
    failure->set_message("Scheduled Minecraft restart could not be launched: fixture failure");
    const auto restart_diagnostic = vibris::mcp::ResultMapper::status(response).at("status");
    require(restart_diagnostic.at("operational") == true && restart_diagnostic.contains("last_error"),
        "Operational status hid a failed graceful-restart launch from the agent.");

    status->mutable_readiness()->set_core_online(false);
    const auto diagnostic = vibris::mcp::ResultMapper::status(response).at("status");
    require(!diagnostic.contains("operational") && diagnostic.contains("state") &&
            diagnostic.contains("active_lease") && diagnostic.contains("transitions") &&
            diagnostic.contains("last_error"),
        "Non-operational status did not preserve server diagnostics.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 21> test_cases {{
    {"WorkspaceIdentityConcurrentFirstUse", workspace_identity_concurrent_first_use},
    {"WorkspaceIdentityDeterministicPublication", workspace_identity_deterministic_publication},
    {"WorkspaceIdentityIoFailuresCleanup", workspace_identity_io_failures_cleanup},
    {"WorkspaceIdentityMalformedWinner", workspace_identity_malformed_winner},
    {"WorkspaceIdentityRetriesOnlyPublicationLoser", workspace_identity_retries_only_publication_loser},
    {"WorkspaceIdentityBlocksTempSubstitution", workspace_identity_blocks_temp_substitution},
    {"WorkspaceIdentityPreservesCreateCollision", workspace_identity_preserves_create_collision},
    {"WorkspaceIdentityRejectsDuplicateKeys", workspace_identity_rejects_duplicate_keys},
    {"WorkspaceIdentityRejectsUnsupportedVersion", workspace_identity_rejects_unsupported_version},
    {"ConfigRejectsUnsupportedVersion", config_rejects_unsupported_version},
    {"WorkspaceIdentityEnforcesJsonDepth", workspace_identity_enforces_json_depth},
    {"WorkspaceIdentityClassifiesNonregularState", workspace_identity_classifies_nonregular_state},
    {"WorkspaceIdentityRejectsReparseState", workspace_identity_rejects_reparse_state},
    {"WorkspaceIdentityMapsAccessErrors", workspace_identity_maps_access_errors},
    {"WorkspaceIdentityRejectsActiveWriter", workspace_identity_rejects_active_writer},
    {"SameWorktreeBackendsCoexist", same_worktree_backends_coexist},
    {"RequestScopedWorktreeRouting", request_scoped_worktree_routing},
    {"TypedPresetDiscoveryAndRequestScene", typed_preset_discovery_and_request_scene},
    {"ToolMetadataIsRequestScoped", tool_metadata_is_request_scoped},
    {"ResourceListsAreEmpty", resource_lists_are_empty},
    {"StatusLeaseTransitionMapping", status_lease_transition_mapping},
}};

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-state-tests <scenario>\n";
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
    std::cerr << "Unknown state test scenario: " << argv[1] << '\n';
    return 2;
}
