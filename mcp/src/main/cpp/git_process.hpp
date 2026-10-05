#pragma once

#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <Windows.h>

#include "state_error.hpp"

#include <chrono>
#include <cwchar>
#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

namespace vibris::mcp {

inline constexpr auto kGitOperationTimeout = std::chrono::seconds(5);
inline constexpr DWORD kGitPollMilliseconds = 10;
inline constexpr DWORD kGitTerminationWaitMilliseconds = 1000;
inline constexpr std::size_t kGitMaxDiagnosticBytes = 32768;

inline std::filesystem::path git_candidate(const std::filesystem::path& install_root) {
    if (install_root.empty() || !install_root.is_absolute()) return {};
    for (const auto* relative : {L"mingw64\\bin\\git.exe", L"cmd\\git.exe", L"bin\\git.exe"}) {
        const auto candidate = install_root / relative;
        std::error_code error;
        if (std::filesystem::is_regular_file(candidate, error) && !error) {
            return std::filesystem::weakly_canonical(candidate, error);
        }
    }
    return {};
}

inline std::filesystem::path registry_git_candidate(HKEY hive) {
    wchar_t value[32768]{};
    DWORD bytes = sizeof(value);
    const auto status = RegGetValueW(hive, L"SOFTWARE\\GitForWindows", L"InstallPath",
        RRF_RT_REG_SZ, nullptr, value, &bytes);
    if (status != ERROR_SUCCESS || bytes < sizeof(wchar_t)) return {};
    return git_candidate(std::filesystem::path(value));
}

inline std::filesystem::path resolve_git_executable() {
    for (const auto hive : {HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER}) {
        if (const auto candidate = registry_git_candidate(hive); !candidate.empty()) return candidate;
    }
    for (const auto* variable : {L"ProgramFiles", L"ProgramFiles(x86)", L"LOCALAPPDATA"}) {
        std::wstring value(32768, L'\0');
        const DWORD copied = GetEnvironmentVariableW(variable, value.data(), static_cast<DWORD>(value.size()));
        if (copied == 0 || copied >= value.size()) continue;
        value.resize(copied);
        const auto root = std::filesystem::path(value) /
            (_wcsicmp(variable, L"LOCALAPPDATA") == 0 ? L"Programs\\Git" : L"Git");
        if (const auto candidate = git_candidate(root); !candidate.empty()) return candidate;
    }
    throw StateError("INTERNAL_ERROR", "Unable to locate a trusted Git for Windows installation.", true);
}

inline std::wstring quote_git_argument(std::wstring_view argument) {
    if (!argument.empty() && argument.find_first_of(L" \t\"") == std::wstring_view::npos) {
        return std::wstring(argument);
    }
    std::wstring quoted(1, L'"');
    std::size_t backslashes = 0;
    for (const wchar_t character : argument) {
        if (character == L'\\') {
            ++backslashes;
        } else if (character == L'"') {
            quoted.append(backslashes * 2 + 1, L'\\');
            quoted.push_back(L'"');
            backslashes = 0;
        } else {
            quoted.append(backslashes, L'\\');
            quoted.push_back(character);
            backslashes = 0;
        }
    }
    quoted.append(backslashes * 2, L'\\');
    quoted.push_back(L'"');
    return quoted;
}

inline std::wstring git_command_line(const std::vector<std::wstring>& arguments) {
    std::wstring command;
    for (const auto& argument : arguments) {
        if (!command.empty()) command.push_back(L' ');
        command += quote_git_argument(argument);
    }
    if (command.size() >= 32767) {
        throw StateError("INTERNAL_ERROR", "Git command is too long.", true);
    }
    return command;
}

inline std::vector<wchar_t> sanitized_git_environment(const std::filesystem::path& index = {}) {
    std::vector<wchar_t> filtered;
    auto* environment = GetEnvironmentStringsW();
    if (environment == nullptr) {
        throw StateError("INTERNAL_ERROR", "Unable to read the Git child environment.", true);
    }
    for (auto* entry = environment; *entry != L'\0'; entry += std::wcslen(entry) + 1) {
        const std::wstring_view value(entry);
        const auto separator = value.find(L'=');
        const bool override = separator != std::wstring_view::npos && separator >= 4 &&
            _wcsnicmp(value.data(), L"GIT_", 4) == 0;
        if (!override) {
            filtered.insert(filtered.end(), value.begin(), value.end());
            filtered.push_back(L'\0');
        }
    }
    FreeEnvironmentStringsW(environment);
    if (!index.empty()) {
        const auto entry = L"GIT_INDEX_FILE=" + std::filesystem::absolute(index).wstring();
        filtered.insert(filtered.end(), entry.begin(), entry.end());
        filtered.push_back(L'\0');
    }
    filtered.push_back(L'\0');
    return filtered;
}

inline bool terminate_and_reap_git(const HANDLE process) noexcept {
    const auto initial = WaitForSingleObject(process, 0);
    if (initial == WAIT_OBJECT_0) return true;
    if (initial == WAIT_FAILED) return false;
    if (!TerminateProcess(process, ERROR_TIMEOUT)) {
        return WaitForSingleObject(process, 0) == WAIT_OBJECT_0;
    }
    return WaitForSingleObject(process, kGitTerminationWaitMilliseconds) == WAIT_OBJECT_0;
}

}
