#pragma once
#include "tool_registry.hpp"
#include <filesystem>

namespace vibris::mcp {
[[nodiscard]] std::filesystem::path preset_preview_directory(const std::filesystem::path& root,
    const Json& preset, int width, const std::string& version);
[[nodiscard]] bool preset_preview_cached(const std::filesystem::path& root,
    const Json& preset, int width, const std::string& version);
[[nodiscard]] Json finish_preset_previews(const std::filesystem::path& root,
    const Json& arguments, const Json& cases);
void attach_preset_thumbnails(const std::filesystem::path& root, Json& listed);
}
