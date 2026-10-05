#include "preset_previews.hpp"
#include "image_data.hpp"
#include "state_error.hpp"
#include "git_process.hpp"
#include "config_document.hpp"
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include <stb_image_resize2.h>
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <bcrypt.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>

namespace vibris::mcp {
namespace fs = std::filesystem;
namespace {
std::string sha256(const std::string& value) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
        throw StateError("PREVIEW_CACHE_ERROR", "Could not initialize preview cache hashing.");
    std::array<unsigned char, 32> digest{};
    const auto status = BCryptHash(algorithm, nullptr, 0,
        reinterpret_cast<PUCHAR>(const_cast<char*>(value.data())), static_cast<ULONG>(value.size()),
        digest.data(), static_cast<ULONG>(digest.size()));
    BCryptCloseAlgorithmProvider(algorithm, 0);
    if (status < 0) throw StateError("PREVIEW_CACHE_ERROR", "Could not hash preview cache key.");
    std::string result;
    for (const auto byte : digest) { result += "0123456789abcdef"[byte >> 4]; result += "0123456789abcdef"[byte & 15]; }
    return result;
}

// Fixed 8x8 cells containing a five-column ASCII bitmap, with a one-pixel margin.
constexpr std::string_view glyphs = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.:/ ";
constexpr std::array<std::array<unsigned char, 5>, 42> font{{
    {{0x7e,0x11,0x11,0x11,0x7e}},{{0x7f,0x49,0x49,0x49,0x36}},{{0x3e,0x41,0x41,0x41,0x22}},
    {{0x7f,0x41,0x41,0x22,0x1c}},{{0x7f,0x49,0x49,0x49,0x41}},{{0x7f,0x09,0x09,0x09,0x01}},
    {{0x3e,0x41,0x49,0x49,0x7a}},{{0x7f,0x08,0x08,0x08,0x7f}},{{0x41,0x41,0x7f,0x41,0x41}},
    {{0x20,0x40,0x41,0x3f,0x01}},{{0x7f,0x08,0x14,0x22,0x41}},{{0x7f,0x40,0x40,0x40,0x40}},
    {{0x7f,0x02,0x0c,0x02,0x7f}},{{0x7f,0x04,0x08,0x10,0x7f}},{{0x3e,0x41,0x41,0x41,0x3e}},
    {{0x7f,0x09,0x09,0x09,0x06}},{{0x3e,0x41,0x51,0x21,0x5e}},{{0x7f,0x09,0x19,0x29,0x46}},
    {{0x46,0x49,0x49,0x49,0x31}},{{0x01,0x01,0x7f,0x01,0x01}},{{0x3f,0x40,0x40,0x40,0x3f}},
    {{0x1f,0x20,0x40,0x20,0x1f}},{{0x3f,0x40,0x38,0x40,0x3f}},{{0x63,0x14,0x08,0x14,0x63}},
    {{0x07,0x08,0x70,0x08,0x07}},{{0x61,0x51,0x49,0x45,0x43}},
    {{0x3e,0x51,0x49,0x45,0x3e}},{{0x00,0x42,0x7f,0x40,0x00}},{{0x42,0x61,0x51,0x49,0x46}},
    {{0x21,0x41,0x45,0x4b,0x31}},{{0x18,0x14,0x12,0x7f,0x10}},{{0x27,0x45,0x45,0x45,0x39}},
    {{0x3c,0x4a,0x49,0x49,0x30}},{{0x01,0x71,0x09,0x05,0x03}},{{0x36,0x49,0x49,0x49,0x36}},
    {{0x06,0x49,0x49,0x29,0x1e}},{{0x08,0x08,0x08,0x08,0x08}},{{0x40,0x40,0x40,0x40,0x40}},
    {{0x00,0x60,0x60,0x00,0x00}},{{0x00,0x36,0x36,0x00,0x00}},{{0x20,0x10,0x08,0x04,0x02}},{{0,0,0,0,0}}
}};
void label(std::vector<unsigned char>& pixels, int width, int x, int y, const std::string& value, int max_width) {
    int cursor = 0;
    for (unsigned char character : value) {
        if (cursor + 8 > max_width) break;
        const auto index = glyphs.find(static_cast<char>(std::toupper(character)));
        if (index != std::string_view::npos) for (int col = 0; col < 5; ++col) for (int row = 0; row < 7; ++row) {
            if (!(font[index][col] & (1 << row))) continue;
            const auto at = (static_cast<std::size_t>(y + row) * width + x + cursor + col + 1) * 3;
            pixels[at] = pixels[at + 1] = pixels[at + 2] = 235;
        }
        cursor += 8;
    }
}
Json read_json(const fs::path& path) {
    if (fs::file_size(path) > 1024 * 1024) throw StateError("PREVIEW_CACHE_ERROR", "Preview metadata is too large.");
    std::ifstream stream(path, std::ios::binary); return Json::parse(stream);
}
}

fs::path preset_preview_directory(const fs::path& root, const Json& preset, int width, const std::string& version) {
    return root / ".vibris" / "previews" / sha256(preset.at("preset_sha256").get<std::string>() + "|" +
        std::to_string(width) + "|" + version);
}
bool preset_preview_cached(const fs::path& root, const Json& preset, int width, const std::string& version) {
    const auto directory = preset_preview_directory(root, preset, width, version);
    try {
        const auto metadata = read_json(managed_file(root, directory / "meta.json"));
        const auto image = read_image(managed_file(root, directory / "thumb.png"));
        return metadata.value("preset_sha256", std::string{}) == preset.at("preset_sha256").get<std::string>() &&
            metadata.value("tile_width", 0) == width && metadata.value("vibris_version", std::string{}) == version &&
            image.width == width && image.height == metadata.value("height", 0) && image.channels == 3 && image.height <= width * 4;
    } catch (const std::exception&) { return false; }
}

Json finish_preset_previews(const fs::path& root, const Json& arguments, const Json& cases) {
    const auto width = arguments.value("tile_width", 384);
    const auto version = arguments.at("vibris_version").get<std::string>();
    auto presets = arguments.at("presets");
    for (auto& preset : presets) {
        const auto directory = preset_preview_directory(root, preset, width, version);
        const auto found = std::find_if(cases.begin(), cases.end(), [&](const Json& item) {
            return item.value("preset_id", std::string{}) == preset.at("preset_id").get<std::string>(); });
        if (found != cases.end()) {
            std::string screenshot;
            for (const auto& receipt : found->value("action_receipts", Json::array())) {
                if (!receipt.contains("capture")) continue;
                for (const auto& artifact : receipt["capture"].value("artifacts", Json::array()))
                    if (artifact.value("format", std::string{}) == "ARTIFACT_FORMAT_PNG" &&
                        artifact.value("role", std::string{}) == "ARTIFACT_ROLE_PRIMARY")
                        screenshot = artifact.at("relative_path").get<std::string>();
            }
            if (screenshot.empty()) throw StateError("PREVIEW_CAPTURE_MISSING", "Preset screenshot was not returned.");
            const auto source = read_image(managed_file(root, screenshot));
            std::vector<unsigned char> rgb(static_cast<std::size_t>(source.width) * source.height * 3);
            for (std::size_t index = 0; index < rgb.size(); ++index)
                rgb[index] = static_cast<unsigned char>(std::lround(std::clamp(source.values[
                    index / 3 * source.channels + std::min<int>(index % 3, source.channels - 1)], 0.0, 1.0) * 255));
            const auto height = std::max(1, static_cast<int>(std::lround(static_cast<double>(width) * source.height / source.width)));
            if (height > width * 4) throw StateError("PREVIEW_TOPOLOGY_INVALID", "Preset preview aspect ratio is too tall.");
            std::vector<unsigned char> thumb(static_cast<std::size_t>(width) * height * 3);
            if (!stbir_resize_uint8_linear(rgb.data(), source.width, source.height, 0, thumb.data(), width, height, 0, STBIR_RGB))
                throw StateError("PREVIEW_RESIZE_FAILED", "Could not resize preset preview.");
            static_cast<void>(managed_output_directory(root, directory));
            write_png(directory / "thumb.png", width, height, 3, thumb);
            const auto provenance = found->value("provenance", Json::object());
            Json metadata{{"preset_id", preset.at("preset_id")}, {"preset_sha256", preset.at("preset_sha256")},
                {"tile_width", width}, {"height", height}, {"vibris_version", version},
                {"source_snapshot_sha256", provenance.value("source_snapshot_sha256", std::string{})},
                {"settings_sha256", provenance.value("config_sha256", std::string{})}};
            std::ofstream output(directory / "meta.json", std::ios::binary); output << metadata.dump(2);
            if (!output) throw StateError("PREVIEW_CACHE_ERROR", "Could not write preview metadata.");
        }
        preset["thumbnail_path"] = managed_file(root, directory / "thumb.png").string();
        preset["cache_metadata"] = read_json(managed_file(root, directory / "meta.json"));
    }
    Json sheets = Json::array();
    const auto output_directory = root / ".vibris" / "analysis" / ("previews-" + detail::generate_uuid());
    static_cast<void>(managed_output_directory(root, output_directory));
    for (std::size_t first = 0; first < presets.size(); first += 24) {
        const auto end = std::min(first + 24, presets.size());
        int image_height = 1;
        for (auto i = first; i < end; ++i) image_height = std::max(image_height, presets[i]["cache_metadata"].value("height", 1));
        const auto tile_height = image_height + 32;
        const auto sheet_width = width * 6, sheet_height = tile_height * 4;
        std::vector<unsigned char> pixels(static_cast<std::size_t>(sheet_width) * sheet_height * 3, 24);
        Json members = Json::array();
        for (auto i = first; i < end; ++i) {
            const auto x = static_cast<int>((i - first) % 6) * width;
            const auto y = static_cast<int>((i - first) / 6) * tile_height;
            const auto image = read_image(presets[i].at("thumbnail_path").get<std::string>());
            for (int row = 0; row < image.height; ++row) for (int col = 0; col < width; ++col) for (int c = 0; c < 3; ++c)
                pixels[(static_cast<std::size_t>(y + row) * sheet_width + x + col) * 3 + c] =
                    static_cast<unsigned char>(std::lround(image.values[(static_cast<std::size_t>(row) * width + col) * 3 + c] * 255));
            label(pixels, sheet_width, x, y + image_height + 5, presets[i].at("preset_id").get<std::string>(), width);
            std::string tags;
            for (const auto& tag : presets[i].value("tags", Json::array())) { if (!tags.empty()) tags += " "; tags += tag.get<std::string>(); }
            label(pixels, sheet_width, x, y + image_height + 17, tags, width);
            members.push_back(presets[i].at("preset_id"));
        }
        const auto path = output_directory / ("sheet-" + std::to_string(first / 24 + 1) + ".png");
        write_png(path, sheet_width, sheet_height, 3, pixels);
        sheets.push_back({{"path", path.string()}, {"presets", std::move(members)}});
    }
    return {{"success", true}, {"kind", "preset_previews"}, {"sheets", std::move(sheets)}, {"presets", std::move(presets)}};
}

void attach_preset_thumbnails(const fs::path& root, Json& listed) {
    const auto version = listed.value("vibris_version", std::string{});
    if (version.empty()) return;
    for (auto& preset : listed["presets"]) {
        const auto path = preset_preview_directory(root, preset, 384, version) / "thumb.png";
        if (preset_preview_cached(root, preset, 384, version)) preset["thumbnail_path"] = path.string();
    }
}
}
