#include "artifact_prune.hpp"

#include <algorithm>
#include <chrono>
#include <system_error>
#include <vector>
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

namespace vibris::mcp {
ToolOutcome prune_artifacts(const std::filesystem::path& root, const Json& arguments,
    const std::function<ToolOutcome(const Json&)>& manage) {
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    const auto age = arguments.value("older_than_ms", std::uint64_t{});
    const auto cutoff = age > static_cast<std::uint64_t>(now) ? 0 : static_cast<std::uint64_t>(now) - age;
    auto listed = manage({{"operation", "list"}});
    if (std::holds_alternative<ToolFailure>(listed)) return listed;
    auto manifests = std::get<Json>(listed).value("manifests", Json::array());
    std::vector<Json> candidates;
    for (const auto& manifest : manifests) {
        if (arguments.contains("older_than_ms") && manifest.value("completed_at_unix_ms",
            manifest.value("created_at_unix_ms", std::uint64_t{})) >= cutoff) continue;
        if (arguments.contains("job_ids") && std::find(arguments["job_ids"].begin(),
            arguments["job_ids"].end(), manifest.value("job_id", std::string{})) == arguments["job_ids"].end()) continue;
        if (arguments.contains("recipe") && manifest.value("recipe", std::string{}) != arguments["recipe"].get<std::string>()) continue;
        candidates.push_back(manifest);
    }
    std::sort(candidates.begin(), candidates.end(), [](const Json& a, const Json& b) {
        const auto left = a.value("completed_at_unix_ms", a.value("created_at_unix_ms", std::uint64_t{}));
        const auto right = b.value("completed_at_unix_ms", b.value("created_at_unix_ms", std::uint64_t{}));
        return left != right ? left > right : a.at("manifest_id") < b.at("manifest_id");
    });
    const auto keep = std::min(arguments.value("keep_latest", std::size_t{}), candidates.size());
    const bool dry_run = arguments.value("dry_run", false);
    Json selected = Json::array(), deleted = Json::array(), failures = Json::array(), analysis = Json::array();
    std::uint64_t freed = 0, selected_bytes = 0;
    for (std::size_t index = keep; index < candidates.size(); ++index) {
        const auto& manifest = candidates[index];
        selected.push_back(manifest.at("manifest_id"));
        selected_bytes += manifest.value("total_bytes", std::uint64_t{});
        if (dry_run) continue;
        auto outcome = manage({{"operation", "delete"}, {"manifest_id", manifest.at("manifest_id")},
            {"expected_manifest_sha256", manifest.at("manifest_sha256")}});
        if (const auto* failure = std::get_if<ToolFailure>(&outcome)) {
            failures.push_back({{"manifest_id", manifest.at("manifest_id")}, {"code", failure->code},
                {"message", failure->message}, {"details", failure->details}});
        } else if (std::get<Json>(outcome).value("deleted", false)) {
            deleted.push_back(manifest.at("manifest_id"));
            freed += manifest.value("total_bytes", std::uint64_t{});
        } else {
            failures.push_back({{"manifest_id", manifest.at("manifest_id")},
                {"code", "ARTIFACT_NOT_DELETED"}, {"message", "Server did not confirm deletion."}});
        }
    }
    // Local analyses have no job/recipe manifest, so only the explicit age filter applies.
    if (arguments.contains("older_than_ms") && !arguments.contains("job_ids") && !arguments.contains("recipe")) {
        const auto directory = root / ".vibris" / "analysis";
        std::error_code error;
        std::vector<std::filesystem::directory_entry> entries;
        const auto ordinary = [](const std::filesystem::path& path) {
            const auto attributes = GetFileAttributesW(path.c_str());
            return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_REPARSE_POINT);
        };
        if (ordinary(root / ".vibris") && ordinary(directory) && std::filesystem::exists(directory, error)) {
            for (const auto& entry : std::filesystem::directory_iterator(directory)) {
                if (!entry.is_directory() || !ordinary(entry.path())) continue;
                const auto modified = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::clock_cast<std::chrono::system_clock>(entry.last_write_time()).time_since_epoch()).count();
                if (modified >= 0 && static_cast<std::uint64_t>(modified) < cutoff) entries.push_back(entry);
            }
            std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
                return a.last_write_time() > b.last_write_time();
            });
            for (std::size_t index = std::min(arguments.value("keep_latest", std::size_t{}), entries.size()); index < entries.size(); ++index) {
                const auto& path = entries[index].path();
                const auto relative = std::filesystem::canonical(path).lexically_relative(std::filesystem::canonical(root / ".vibris"));
                if (relative.empty() || relative.is_absolute() || *relative.begin() == "..") {
                    failures.push_back({{"path", path.string()}, {"message", "Analysis path escaped the managed directory."}});
                    continue;
                }
                std::uint64_t bytes = 0;
                bool linked = false;
                for (const auto& file : std::filesystem::recursive_directory_iterator(path)) {
                    if (!ordinary(file.path())) { linked = true; break; }
                    if (file.is_regular_file()) bytes += file.file_size();
                }
                if (linked) {
                    failures.push_back({{"path", path.string()}, {"message", "Analysis contains a reparse point."}});
                    continue;
                }
                selected_bytes += bytes;
                if (!dry_run) {
                    std::filesystem::remove_all(path, error);
                    if (error) { failures.push_back({{"path", path.string()}, {"message", error.message()}}); continue; }
                    freed += bytes;
                }
                analysis.push_back(path.string());
            }
        }
    }
    return Json{{"dry_run", dry_run}, {"selected_manifests", std::move(selected)},
        {"deleted_manifests", std::move(deleted)}, {"selected_bytes", selected_bytes},
        {"freed_bytes", freed}, {"analysis_paths", std::move(analysis)}, {"failures", std::move(failures)}};
}
}
