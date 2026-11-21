#include "runtime_start.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include <shellapi.h>
#include <wbemidl.h>
#include <wrl/client.h>

#include <algorithm>
#include <charconv>
#include <chrono>
#include <cwchar>
#include <fstream>
#include <memory>
#include <optional>
#include <stdexcept>
#include <thread>
#include <vector>

#include "protocol_version.hpp"
#include "result_mapper.hpp"
#include "git_process.hpp"
#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

namespace vibris::mcp {
namespace {

using namespace std::chrono_literals;
using Microsoft::WRL::ComPtr;
namespace fs = std::filesystem;
namespace control = ::vibris::control::v2;

constexpr std::wstring_view game_marker = L"-Dvibris.launch.gameDirectory=";
constexpr std::chrono::seconds pending_timeout{240};

struct Deployment {
    fs::path game;
    fs::path launcher;
    fs::path observed_launcher;
    fs::path legacy_restart;
    std::vector<std::wstring> launcher_arguments;
};

fs::path utf8_path(std::string_view value) {
    return fs::path(std::u8string(value.begin(), value.end()));
}

fs::path path_field(const Json& json, const char* key) {
    auto path = utf8_path(json.at(key).get<std::string>());
    if (!path.is_absolute() || path.root_name().native().size() != 2) {
        throw std::invalid_argument("Expected an absolute local drive path.");
    }
    return path;
}

bool same_file(const fs::path& left, const fs::path& right) {
    std::error_code error;
    return fs::equivalent(left, right, error) && !error;
}

Deployment load_deployment(const fs::path& path) {
    if (!fs::is_regular_file(path) || fs::file_size(path) > 8192) {
        throw std::invalid_argument("Missing or oversized startup configuration.");
    }
    std::ifstream input(path, std::ios::binary);
    const auto json = Json::parse(input);
    if (!json.is_object()) throw std::invalid_argument("Expected a deployment object.");
    for (const auto& field : json.items()) {
        const auto& key = field.key();
        if (key != "game_directory" && key != "launcher_executable" && key != "launcher_arguments" &&
            key != "observed_launcher" && key != "legacy_restart_entry") {
            throw std::invalid_argument("Unexpected startup configuration field.");
        }
    }
    Deployment deployment{};
    deployment.game = fs::canonical(path_field(json, "game_directory"));
    if (json.contains("launcher_executable")) {
        deployment.launcher = fs::canonical(path_field(json, "launcher_executable"));
        if (_wcsicmp(deployment.launcher.extension().c_str(), L".exe") != 0 ||
            !fs::is_regular_file(deployment.launcher)) {
            throw std::invalid_argument("Expected a native launcher executable.");
        }
    }
    if (json.contains("launcher_arguments")) {
        const auto& arguments = json.at("launcher_arguments");
        if (deployment.launcher.empty() || !arguments.is_array() || arguments.size() > 16) {
            throw std::invalid_argument("Invalid deployment launcher arguments.");
        }
        for (const auto& argument : arguments) {
            const auto value = argument.get<std::string>();
            if (value.find('\0') != std::string::npos) throw std::invalid_argument("Invalid launcher argument.");
            deployment.launcher_arguments.push_back(utf8_path(value).native());
        }
    }
    if (json.contains("observed_launcher")) {
        deployment.observed_launcher = path_field(json, "observed_launcher").lexically_normal();
    }
    if (json.contains("legacy_restart_entry")) {
        deployment.legacy_restart = path_field(json, "legacy_restart_entry").lexically_normal();
    }
    if (!fs::is_directory(deployment.game / "config" / "vibris")) {
        throw std::invalid_argument("The dedicated game directory is missing config/vibris.");
    }
    return deployment;
}

class Handle {
public:
    explicit Handle(HANDLE value) noexcept : value_(value) {}
    ~Handle() { if (value_ != INVALID_HANDLE_VALUE && value_ != nullptr) CloseHandle(value_); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    [[nodiscard]] HANDLE get() const { return value_; }
private:
    HANDLE value_;
};

class LaunchGuard final {
public:
    explicit LaunchGuard(const fs::path& directory)
        : file_(CreateFileW((directory / "config" / "vibris" / "mcp-start.guard").c_str(),
              GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_ALWAYS,
              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr)) {
        if (file_.get() == INVALID_HANDLE_VALUE) {
            if (GetLastError() == ERROR_SHARING_VIOLATION) return;
            throw std::runtime_error("Cannot lock the deployment's startup guard.");
        }
        BY_HANDLE_FILE_INFORMATION info{};
        if (!GetFileInformationByHandle(file_.get(), &info) ||
            (info.dwFileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY))) {
            throw std::runtime_error("The startup guard must be an ordinary file.");
        }
    }

    [[nodiscard]] bool locked() const { return file_.get() != INVALID_HANDLE_VALUE; }

    // A nonzero receipt survives EOF, launch timeout, and an MCP crash during admission.
    [[nodiscard]] std::uint64_t receipt() const {
        LARGE_INTEGER size{};
        if (!GetFileSizeEx(file_.get(), &size)) throw std::runtime_error("Cannot read the startup guard.");
        if (size.QuadPart == 0) return 0;
        std::uint64_t value = 0;
        DWORD count = 0;
        if (size.QuadPart != sizeof(value) || !SetFilePointerEx(file_.get(), {}, nullptr, FILE_BEGIN) ||
            !ReadFile(file_.get(), &value, sizeof(value), &count, nullptr) || count != sizeof(value)) {
            throw std::runtime_error("The startup guard is unreadable.");
        }
        return value;
    }

    void set_receipt(std::uint64_t value) const {
        DWORD count = 0;
        if (!SetFilePointerEx(file_.get(), {}, nullptr, FILE_BEGIN) ||
            !WriteFile(file_.get(), &value, sizeof(value), &count, nullptr) || count != sizeof(value) ||
            !FlushFileBuffers(file_.get())) {
            throw std::runtime_error("Cannot persist the startup guard.");
        }
    }

private:
    Handle file_;
};

std::uint64_t unix_seconds() {
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

std::optional<unsigned> loopback_port(std::string_view address) {
    constexpr std::string_view prefix = "127.0.0.1:";
    if (!address.starts_with(prefix)) return std::nullopt;
    address.remove_prefix(prefix.size());
    unsigned port = 0;
    const auto parsed = std::from_chars(address.data(), address.data() + address.size(), port);
    return parsed.ec == std::errc{} && parsed.ptr == address.data() + address.size() && port > 0 && port < 65536
        ? std::optional{port} : std::nullopt;
}

std::vector<std::byte> tcp_listeners(ULONG address_family) {
    DWORD bytes = 0;
    GetExtendedTcpTable(nullptr, &bytes, FALSE, address_family, TCP_TABLE_OWNER_PID_LISTENER, 0);
    std::vector<std::byte> rows(bytes);
    DWORD result = ERROR_INSUFFICIENT_BUFFER;
    for (int attempt = 0; attempt < 3 && result == ERROR_INSUFFICIENT_BUFFER; ++attempt) {
        rows.resize(bytes);
        result = GetExtendedTcpTable(rows.data(), &bytes, FALSE, address_family, TCP_TABLE_OWNER_PID_LISTENER, 0);
    }
    if (result != NO_ERROR) throw std::runtime_error("Cannot inspect the loopback listener.");
    return rows;
}

unsigned host_port(DWORD port) {
    return ((port & 0xff) << 8) | ((port >> 8) & 0xff);
}

std::optional<DWORD> listener(unsigned port) {
    std::optional<DWORD> owner;
    const auto observe = [&owner](DWORD pid) {
        if (owner && owner != pid) throw std::runtime_error("The loopback listener is ambiguous.");
        owner = pid;
    };
    const auto ipv4 = tcp_listeners(AF_INET);
    const auto& table = *reinterpret_cast<const MIB_TCPTABLE_OWNER_PID*>(ipv4.data());
    for (DWORD i = 0; i < table.dwNumEntries; ++i) {
        const auto& row = table.table[i];
        if (host_port(row.dwLocalPort) == port && (row.dwLocalAddr == 0 || row.dwLocalAddr == 0x0100007f)) {
            observe(row.dwOwningPid);
        }
    }
    const auto ipv6 = tcp_listeners(AF_INET6);
    const auto& table6 = *reinterpret_cast<const MIB_TCP6TABLE_OWNER_PID*>(ipv6.data());
    constexpr unsigned char mapped_loopback[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 255, 127, 0, 0, 1};
    for (DWORD i = 0; i < table6.dwNumEntries; ++i) {
        const auto& row = table6.table[i];
        // The JVM may use an IPv6 socket for an IPv4-mapped bind or a dual-stack wildcard.
        if (host_port(row.dwLocalPort) == port && (std::ranges::equal(row.ucLocalAddr, mapped_loopback) ||
            std::ranges::all_of(row.ucLocalAddr, [](unsigned char value) { return value == 0; }))) {
            observe(row.dwOwningPid);
        }
    }
    return owner;
}

class ComApartment {
public:
    ComApartment() {
        if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) {
            throw std::runtime_error("Cannot initialize Windows process inspection.");
        }
    }
    ~ComApartment() { CoUninitialize(); }
};

class BStr {
public:
    explicit BStr(const wchar_t* value) : value_(SysAllocString(value)) {
        if (!value_) throw std::bad_alloc();
    }
    ~BStr() { SysFreeString(value_); }
    operator BSTR() const { return value_; }
private:
    BSTR value_;
};

std::wstring text_property(IWbemClassObject& object, const wchar_t* name) {
    VARIANT value{};
    if (FAILED(object.Get(name, 0, &value, nullptr, nullptr))) {
        throw std::runtime_error("Windows process inspection could not read a required property.");
    }
    const auto result = value.vt == VT_BSTR && value.bstrVal ? std::wstring(value.bstrVal) : std::wstring{};
    VariantClear(&value);
    return result;
}

DWORD process_id(IWbemClassObject& object) {
    VARIANT value{};
    if (FAILED(object.Get(L"ProcessId", 0, &value, nullptr, nullptr)) || value.vt != VT_I4) {
        VariantClear(&value);
        throw std::runtime_error("Windows process inspection could not read a process ID.");
    }
    return static_cast<DWORD>(value.lVal);
}

bool equal_ci(std::wstring_view left, std::wstring_view right) {
    return CompareStringOrdinal(left.data(), static_cast<int>(left.size()),
        right.data(), static_cast<int>(right.size()), TRUE) == CSTR_EQUAL;
}

enum class JavaIdentity { target, other, unknown };

JavaIdentity identify_java(const std::wstring& command, const Deployment& deployment) {
    int count = 0;
    auto* args = CommandLineToArgvW(command.c_str(), &count);
    if (!args) throw std::runtime_error("Cannot identify a Java process.");
    const auto release = [](wchar_t** value) { LocalFree(value); };
    const std::unique_ptr<wchar_t*, decltype(release)> argv(args, release);
    bool target = false;
    bool other = false;
    bool unresolved = false;
    bool minecraft_or_argfile = false;
    for (int i = 1; i < count; ++i) {
        const std::wstring_view arg(args[i]);
        std::wstring_view path;
        if (arg == L"--gameDir" && i + 1 < count) path = args[i + 1];
        else if (arg.starts_with(L"--gameDir=")) path = arg.substr(10);
        else if (arg.starts_with(game_marker)) path = arg.substr(game_marker.size());
        if (!path.empty()) {
            const fs::path identity(path);
            std::error_code error;
            if (identity.is_absolute() && same_file(identity, deployment.game)) target = true;
            else if (identity.is_absolute() && fs::is_directory(identity, error)) other = true;
            else unresolved = true;
        }
        minecraft_or_argfile = minecraft_or_argfile || arg.starts_with(L"@") ||
            arg.ends_with(L".KnotClient") || arg == L"net.minecraft.client.main.Main" ||
            arg.ends_with(L"NewLaunch.jar");
    }
    if (unresolved || (target && other) || (minecraft_or_argfile && !target && !other)) {
        return JavaIdentity::unknown;
    }
    return target ? JavaIdentity::target : JavaIdentity::other;
}

struct ProcessObservation {
    std::vector<DWORD> target_java;
    bool launcher_open = false;
    bool planned_restart = false;
    bool ambiguous = false;
};

ProcessObservation inspect_processes(const Deployment& deployment) {
    ComApartment apartment;
    ComPtr<IWbemLocator> locator;
    ComPtr<IWbemServices> services;
    ComPtr<IEnumWbemClassObject> cursor;
    std::wstring query = L"SELECT Name, ProcessId, ExecutablePath, CommandLine FROM Win32_Process WHERE "
        L"Name='java.exe' OR Name='javaw.exe' OR Name='powershell.exe' OR Name='pwsh.exe' OR Name='cmd.exe'";
    const auto add_launcher_name = [&query](const fs::path& path) {
        if (path.empty()) return;
        const auto name = path.filename().native();
        query += L" OR Name='";
        for (const auto character : name) {
            if (character == L'\\' || character == L'\'') query += L'\\';
            query += character;
        }
        query += L'\'';
    };
    add_launcher_name(deployment.launcher);
    add_launcher_name(deployment.observed_launcher);
    if (FAILED(CoCreateInstance(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&locator))) ||
        FAILED(locator->ConnectServer(BStr(L"ROOT\\CIMV2"), nullptr, nullptr, nullptr, WBEM_FLAG_CONNECT_USE_MAX_WAIT,
            nullptr, nullptr, &services)) ||
        FAILED(CoSetProxyBlanket(services.Get(), RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, nullptr,
            RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_NONE)) ||
        FAILED(services->ExecQuery(BStr(L"WQL"), BStr(query.c_str()),
            WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY, nullptr, &cursor))) {
        throw std::runtime_error("Cannot inspect Java and launcher processes.");
    }

    ProcessObservation found;
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (std::chrono::steady_clock::now() < deadline) {
        ComPtr<IWbemClassObject> process;
        ULONG count = 0;
        const auto status = cursor->Next(250, 1, &process, &count);
        if (status == WBEM_S_FALSE) return found;
        if (status == WBEM_S_TIMEDOUT) continue;
        if (FAILED(status) || count != 1) break;
        const auto name = text_property(*process.Get(), L"Name");
        const bool java = equal_ci(name, L"java.exe") || equal_ci(name, L"javaw.exe");
        const bool launcher = (!deployment.launcher.empty() &&
                equal_ci(name, deployment.launcher.filename().native())) ||
            (!deployment.observed_launcher.empty() &&
                equal_ci(name, deployment.observed_launcher.filename().native()));
        if (launcher) {
            const auto executable = text_property(*process.Get(), L"ExecutablePath");
            if (executable.empty()) found.ambiguous = true;
            else if (same_file(executable, deployment.launcher) ||
                     same_file(executable, deployment.observed_launcher)) found.launcher_open = true;
            if (!java) continue;
        }
        const auto command = text_property(*process.Get(), L"CommandLine");
        if (command.empty()) {
            // An unreadable Java or interpreter could be the target or its restart helper.
            found.ambiguous = true;
        } else if (java) {
            const auto identity = identify_java(command, deployment);
            if (identity == JavaIdentity::target) found.target_java.push_back(process_id(*process.Get()));
            else if (identity == JavaIdentity::unknown) found.ambiguous = true;
        } else if (command.find(L"VIBRIS_RESTART_PARENT_PID") != std::wstring::npos &&
                   command.find(L"VIBRIS_RESTART_EXECUTABLE") != std::wstring::npos) {
            // Older mods spawn this helper before relinquishing the game. Its environment
            // belongs to the helper and is not read by the MCP.
            found.planned_restart = true;
        } else if (equal_ci(name, L"cmd.exe") && !deployment.legacy_restart.empty()) {
            std::wstring normalized = command;
            std::ranges::replace(normalized, L'/', L'\\');
            const auto& restart = deployment.legacy_restart.native();
            for (std::size_t i = 0; i + restart.size() <= normalized.size(); ++i) {
                if (equal_ci(std::wstring_view(normalized).substr(i, restart.size()), restart)) {
                    found.planned_restart = true;
                    break;
                }
            }
        }
    }
    throw std::runtime_error("Windows process inspection did not finish.");
}

bool launch_profile(const Deployment& deployment) {
    // The configured executable and literal arguments bypass shell interpretation.
    std::wstring command = quote_git_argument(deployment.launcher.native());
    for (const auto& argument : deployment.launcher_arguments) command += L' ' + quote_git_argument(argument);
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION child{};
    if (!CreateProcessW(deployment.launcher.c_str(), command.data(), nullptr, nullptr, FALSE, 0,
            nullptr, deployment.launcher.parent_path().c_str(), &startup, &child)) return false;
    const Handle process(child.hProcess);
    const Handle thread(child.hThread);
    return true;
}

ToolFailure pending(std::string phase) {
    return {"STARTUP_PENDING", "The dedicated instance is not ready. Call vibris_ensure_runtime again to wait.",
        true, {{"phase", std::move(phase)}}};
}

} // namespace

RuntimeStart::RuntimeStart(std::string address, fs::path deployment)
    : address_(std::move(address)), deployment_(std::move(deployment)) {}

ToolOutcome RuntimeStart::ensure_online(const Json& arguments) const {
    if (deployment_.empty()) {
        return ToolFailure{"STARTUP_NOT_CONFIGURED", "The local MCP deployment needs --startup-config.", false};
    }
    const auto port = loopback_port(address_);
    if (!port) {
        return ToolFailure{"STARTUP_NOT_CONFIGURED", "Startup requires the deployed IPv4 loopback endpoint.", false};
    }

    Deployment deployment;
    try {
        deployment = load_deployment(deployment_);
    } catch (const std::exception&) {
        return ToolFailure{"STARTUP_NOT_CONFIGURED",
            "Check the local startup configuration and the dedicated game directory.", false};
    }

    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(arguments.value("timeout_ms", 45'000));
    grpc::ChannelArguments channel;
    channel.SetInt(GRPC_ARG_ENABLE_HTTP_PROXY, 0);
    auto stub = control::VibrisControl::NewStub(
        grpc::CreateCustomChannel(address_, grpc::InsecureChannelCredentials(), channel));
    std::string phase = "checking";
    bool launched_here = false;
    do {
        if (interrupted_.load()) break;
        try {
            const LaunchGuard guard(deployment.game);
            if (!guard.locked()) {
                phase = "checking";
            } else {
                const auto receipt = guard.receipt();
                const auto before = listener(*port);
                // Retain the process identity across inspection and the Core handshake.
                const Handle game(before ? OpenProcess(SYNCHRONIZE, FALSE, *before) : nullptr);
                const auto observed = inspect_processes(deployment);
                const auto owner = listener(*port);
                if (before != owner) {
                    phase = "checking";
                } else if (observed.target_java.size() > 1 ||
                    (owner && std::ranges::find(observed.target_java, *owner) == observed.target_java.end())) {
                    return ToolFailure{"STARTUP_IDENTITY_UNKNOWN",
                        "The loopback listener or the running game does not uniquely identify the configured instance.", false};
                } else if (owner) {
                    if (!game.get()) return ToolFailure{"STARTUP_IDENTITY_UNKNOWN",
                        "Cannot verify the dedicated game's process lifetime.", false};
                    grpc::ClientContext call;
                    call.set_deadline(std::chrono::system_clock::now() + 700ms);
                    control::GetStatusRequest request;
                    request.mutable_protocol_version()->set_major(protocol_major);
                    request.set_detail(control::STATUS_DETAIL_SUMMARY);
                    control::GetStatusResponse response;
                    const auto status = stub->GetStatus(&call, request, &response);
                    phase = "waiting_for_handshake";
                    if (status.ok() && WaitForSingleObject(game.get(), 0) == WAIT_TIMEOUT &&
                        listener(*port) == owner) {
                        if (!protocol_version_supported(response.has_protocol_version(), response.protocol_version()) ||
                            !response.has_status()) {
                            return ToolFailure{"STARTUP_HANDSHAKE_FAILED",
                                "The dedicated game returned an incompatible Core status.", false};
                        }
                        if (response.status().has_restart()) {
                            if (!receipt) guard.set_receipt(unix_seconds());
                            phase = "planned_restart";
                        } else if (response.status().readiness().core_online() &&
                                   response.status().readiness().minecraft_connected()) {
                            if (receipt) guard.set_receipt(0);
                            return Json{{"phase", "online"}, {"launched_by_this_call", launched_here},
                                {"process_id", *owner},
                                {"status", ResultMapper::status(response).at("status")}};
                        }
                    } else if (status.error_code() == grpc::StatusCode::FAILED_PRECONDITION ||
                               status.error_code() == grpc::StatusCode::UNIMPLEMENTED) {
                        return ToolFailure{"STARTUP_HANDSHAKE_FAILED",
                            "The dedicated game did not accept the MCP status protocol.", false};
                    }
                } else if (observed.planned_restart) {
                    if (!receipt) guard.set_receipt(unix_seconds());
                    phase = "planned_restart";
                } else if (!observed.target_java.empty()) {
                    phase = "waiting_for_handshake";
                } else if (observed.ambiguous) {
                    return ToolFailure{"STARTUP_IDENTITY_UNKNOWN",
                        "A running process could not be ruled out as the dedicated instance or its restart helper.", true};
                } else if (const auto now = unix_seconds(); receipt && now >= receipt &&
                           now - receipt >= static_cast<std::uint64_t>(pending_timeout.count())) {
                    return ToolFailure{"STARTUP_UNCONFIRMED",
                        "A prior launch or restart has no verified outcome. Check the launcher and game before clearing "
                        "the deployment startup guard.", false};
                } else if (observed.launcher_open || receipt) {
                    phase = observed.launcher_open ? "launcher_open" : "launch_requested";
                } else if (deployment.launcher.empty()) {
                    return ToolFailure{"STARTUP_LAUNCH_NOT_CONFIGURED",
                        "The dedicated instance is stopped. Configure a trusted native launcher entry for its profile "
                        "or start that profile normally and call this tool to verify the connection.", false};
                } else {
                    if (interrupted_.load()) break;
                    guard.set_receipt(unix_seconds());
                    if (!launch_profile(deployment)) {
                        const auto launch_error = GetLastError();
                        guard.set_receipt(0);
                        return ToolFailure{"STARTUP_LAUNCH_FAILED", "Windows could not open the deployed launcher.", true,
                            {{"win32_error", launch_error}}};
                    }
                    launched_here = true;
                    phase = "launch_requested";
                }
            }
        } catch (const std::exception& error) {
            return ToolFailure{"STARTUP_INSPECTION_FAILED", error.what(), true};
        }
        const auto remaining = deadline - std::chrono::steady_clock::now();
        if (remaining <= 0ms || interrupted_.load()) break;
        std::this_thread::sleep_for(std::min(remaining, std::chrono::steady_clock::duration(500ms)));
    } while (std::chrono::steady_clock::now() < deadline);

    if (interrupted_.load()) return ToolFailure{"STARTUP_WAIT_INTERRUPTED", "The MCP connection is closing.", true};
    return pending(std::move(phase));
}

} // namespace vibris::mcp
