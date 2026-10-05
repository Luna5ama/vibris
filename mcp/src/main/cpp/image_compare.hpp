#pragma once
#include "image_data.hpp"

namespace vibris::mcp {
[[nodiscard]] Json image_difference(const ImageData& a, const ImageData& b, const Json& arguments);
[[nodiscard]] ToolOutcome compare_images(const std::filesystem::path& root, const Json& arguments);
void compare_recipe_captures(const std::filesystem::path& root, const Json& arguments, ToolOutcome& outcome);
}
