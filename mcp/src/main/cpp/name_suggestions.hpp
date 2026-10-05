#pragma once
#include <algorithm>
#include <cctype>
#include <numeric>
#include <string>
#include <string_view>
#include <vector>

namespace vibris::mcp {
inline std::vector<std::string> name_suggestions(std::string_view input, std::vector<std::string> candidates) {
    const auto normalize = [](std::string text) {
        std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        for (const std::string_view prefix : {"iris_custom_image.", "usam_", "uimg_"})
            if (text.starts_with(prefix)) text.erase(0, prefix.size());
        return text;
    };
    const auto needle = normalize(std::string(input));
    const auto score = [&](const std::string& candidate) {
        const auto text = normalize(candidate);
        std::vector<std::size_t> row(text.size() + 1); std::iota(row.begin(), row.end(), 0);
        for (std::size_t i = 0; i < needle.size(); ++i) {
            auto diagonal = row[0]; row[0] = i + 1;
            for (std::size_t j = 0; j < text.size(); ++j) {
                const auto previous = row[j + 1];
                row[j + 1] = std::min({row[j + 1] + 1, row[j] + 1, diagonal + (needle[i] != text[j])});
                diagonal = previous;
            }
        }
        return std::pair{text.find(needle) == std::string::npos && needle.find(text) == std::string::npos, row.back()};
    };
    std::sort(candidates.begin(), candidates.end(), [&](const auto& a, const auto& b) {
        const auto left = score(a), right = score(b); return left != right ? left < right : a < b; });
    if (candidates.size() > 3) candidates.resize(3);
    return candidates;
}
}
