#pragma once
#include "tool_registry.hpp"
#include <filesystem>
#include <vector>

namespace vibris::mcp {
struct ImageData {
    int width = 0, height = 0, channels = 0;
    std::string scalar_type;
    double data_range = 0;
    Json metadata = Json::object();
    std::vector<double> values;
};
[[nodiscard]] std::filesystem::path managed_file(const std::filesystem::path& root, const std::filesystem::path& path);
[[nodiscard]] std::filesystem::path managed_output_directory(const std::filesystem::path& root, const std::filesystem::path& path);
[[nodiscard]] ImageData read_image(const std::filesystem::path& path);
void write_png(const std::filesystem::path& path, int width, int height, int channels, const std::vector<unsigned char>& pixels);
}
