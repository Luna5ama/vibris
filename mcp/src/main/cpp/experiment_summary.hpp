#pragma once
#include "tool_registry.hpp"

namespace vibris::mcp {
[[nodiscard]] Json summarize_experiment(const Json& cases, const Json& arguments);
[[nodiscard]] std::string experiment_csv(const Json& cases);
}
