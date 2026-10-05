#pragma once

#include "tool_registry.hpp"
#include <filesystem>

namespace vibris::mcp {
// Deletions use the SHA observed by list, so concurrent manifest changes are retained.
[[nodiscard]] ToolOutcome prune_artifacts(const std::filesystem::path& root,
    const Json& arguments, const std::function<ToolOutcome(const Json&)>& manage);
}
