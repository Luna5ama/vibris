#include "image_compare.hpp"
#include "state_error.hpp"
#include "preset_previews.hpp"
#include <cmath>
#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>

using namespace vibris::mcp;
namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void near(const Json& value, double expected) { require(value.is_number() && std::abs(value.get<double>() - expected) < 1e-9, "incorrect numeric comparison"); }
void difference_and_nonfinite() {
    ImageData a{2, 2, 1, "FLOAT32", 0, Json::object(), {1, 2, 3, 4}};
    auto b = a; b.values = {1, 3, 5, 7};
    auto result = image_difference(a, b, Json::object());
    near(result.at("mean_absolute_error"), 1.5);
    near(result.at("root_mean_square_error"), std::sqrt(3.5));
    near(result.at("p95_absolute_error"), 2.85);
    near(result.at("relative_mse"), 14.0 / 30);
    require(result.at("verdict") == "not_evaluated" && result.at("passed").is_null(), "unrequested thresholds returned passed");
    a.data_range = b.data_range = 1;
    near(image_difference(a, a, Json::object()).at("ssim"), 1);
    b.values[0] = INFINITY;
    result = image_difference(a, b, {{"thresholds", {{"max_mean_absolute_error", 5}}}});
    require(result.at("nonfinite_mismatches") == 1 && result.at("passed") == false, "nonfinite mismatch passed thresholds");
}
void typed_inputs_and_multiple_captures() {
    const auto root = std::filesystem::temp_directory_path() / "vibris-image-compare-tests";
    const auto directory = root / ".vibris" / "fixture";
    std::filesystem::create_directories(directory);
    try {
        write_png(directory / "a-0.png", 2, 2, 1, {0, 64, 128, 255});
        write_png(directory / "b-0.png", 2, 2, 1, {0, 64, 128, 255});
        const auto decoded = read_image(directory / "a-0.png");
        near(decoded.values[1], 64.0 / 255);
        const auto managed = root / "managed";
        std::filesystem::create_directories(managed);
        std::filesystem::copy_file(directory / "a-0.png", managed / "image.png");
        std::filesystem::create_directory_symlink(managed, root / ".vibris" / "artifact");
        const auto alias = root / ".vibris" / "artifact" / "image.png";
        require(managed_file(root, alias) == alias, "managed artifact link rejected");
        std::filesystem::create_directory_symlink(directory, managed / "nested");
        bool rejected = false;
        try { static_cast<void>(managed_file(root, root / ".vibris" / "artifact" / "nested" / "a-0.png")); }
        catch (const StateError& error) { rejected = error.code() == "INVALID_ARTIFACT_PATH"; }
        require(rejected, "nested artifact link escaped validation");
        const unsigned char png16[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x01, 0x10, 0x00, 0x00, 0x00, 0x00, 0x81, 0xd9, 0xfc, 0x15,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x60,
            0x60, 0xf8, 0xff, 0x1f, 0x00, 0x03, 0x02, 0x01, 0xff, 0xe6, 0x77, 0x0b, 0xae,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82};
        std::ofstream sixteen(directory / "sixteen.png", std::ios::binary);
        sixteen.write(reinterpret_cast<const char*>(png16), sizeof(png16)); sixteen.close();
        const auto decoded16 = read_image(directory / "sixteen.png");
        require(decoded16.scalar_type == "PNG16", "16-bit PNG decoded as 8-bit");
        near(decoded16.values[0], 0); near(decoded16.values[1], 1);
        for (const auto* type : {"FLOAT16", "FLOAT32", "UINT8"}) {
            const auto path = directory / (std::string(type) + ".bin");
            std::ofstream file(path, std::ios::binary);
            if (std::string_view(type) == "FLOAT16") { const std::uint16_t values[]{0x3c00, 0x4000}; file.write(reinterpret_cast<const char*>(values), sizeof(values)); }
            else if (std::string_view(type) == "FLOAT32") { const float values[]{1, 2}; file.write(reinterpret_cast<const char*>(values), sizeof(values)); }
            else { const unsigned char values[]{1, 2}; file.write(reinterpret_cast<const char*>(values), sizeof(values)); }
            file.close();
            auto metadata = path; metadata.replace_extension(".json");
            std::ofstream sidecar(metadata);
            sidecar << Json{{"width", 2}, {"height", 1}, {"channel_count", 1}, {"scalar_type", type}};
            sidecar.close();
            const auto image = read_image(path);
            near(image.values[0], 1); near(image.values[1], 2);
        }
        const auto statistics = [&](const char* prefix, const char* output, std::array<float, 2> values) {
            const auto path = directory / (std::string(prefix) + "." + output + ".bin");
            std::ofstream file(path, std::ios::binary);
            file.write(reinterpret_cast<const char*>(values.data()), sizeof(values)); file.close();
            auto sidecar = path; sidecar.replace_extension(".json");
            std::ofstream metadata(sidecar);
            metadata << Json{{"width", 2}, {"height", 1}, {"channel_count", 1}, {"scalar_type", "FLOAT32"},
                {"accumulation", {{"screen_batch_means", std::string_view(prefix) == "a" ? Json::array({1, 3}) : Json::array({2, 6})},
                    {"standard_batch_means_se", true}}}};
            return path;
        };
        const auto mean_a = statistics("a", "mean", {2, 4});
        const auto mean_b = statistics("b", "mean", {4, 8});
        statistics("a", "variance", {1, 4}); statistics("b", "variance", {4, 16});
        statistics("a", "stderr", {.5f, .5f}); statistics("b", "stderr", {.5f, .5f});
        const auto statistical = std::get<Json>(compare_images(root, {{"a", {{"path", mean_a.string()}}},
            {"b", {{"path", mean_b.string()}}}, {"mode", "statistical"}, {"heatmap", false}})).at("statistical");
        near(statistical.at("abs_z_above_3_ratio"), .5);
        near(statistical.at("mean_z"), 3 / std::sqrt(.5));
        near(statistical.at("variance_ratio_median"), 4);
        near(statistical.at("variance_ratio_p90"), 4);
        near(statistical.at("relative_mse_ratio"), 1);
        near(statistical.at("screen_mean_ratio")[0].at("ratio"), 2);
        near(statistical.at("screen_mean_ratio")[0].at("standard_error"), std::sqrt(2.0));
        require(statistical.at("screen_mean_scope") == "full_capture", "screen statistics scope is ambiguous");
        ToolOutcome outcome = Json{{"success", true}, {"artifacts", Json::array()}, {"action_receipts", Json::array()}};
        auto& result = std::get<Json>(outcome);
        for (int i = 0; i < 2; ++i) for (const auto* prefix : {"a", "b"}) {
            const auto path = directory / (std::string(prefix) + "-" + std::to_string(i) + ".png");
            write_png(path, 2, 2, 1, {0, 64, 128, 255});
            result["action_receipts"].push_back({{"capture", {{"artifacts", Json::array({
                {{"relative_path", path.string()}, {"role", "ARTIFACT_ROLE_PRIMARY"}}})}}}});
        }
        compare_recipe_captures(root, {{"recipe", "ab_compare"}, {"captures", Json::array({1, 2})},
            {"visual_thresholds", {{"min_ssim", .99}}}}, outcome);
        require(result.at("comparisons").size() == 2 && result.at("artifacts").size() == 4,
            "multiple captures did not produce independent comparisons");
        for (const auto& comparison : result.at("comparisons")) require(comparison.at("passed") == true, "identical images failed");
        Json preview{{"tile_width", 64}, {"vibris_version", "test-version"},
            {"presets", Json::array({{{"preset_id", "scene-1"}, {"preset_sha256", std::string(64, 'a')},
                {"tags", Json::array({"test"})}}})}};
        auto capture = result["action_receipts"][0];
        capture["capture"]["artifacts"][0]["format"] = "ARTIFACT_FORMAT_PNG";
        const auto sheet = finish_preset_previews(root, preview, Json::array({{{"preset_id", "scene-1"},
            {"action_receipts", Json::array({capture})}, {"provenance", {{"source_snapshot_sha256", "source"},
                {"config_sha256", "settings"}}}}}));
        require(sheet.at("sheets").size() == 1, "preview contact sheet missing");
        const auto image = read_image(sheet.at("sheets")[0].at("path").get<std::string>());
        require(image.width == 384 && image.height == 384, "contact sheet dimensions changed");
        require(preset_preview_cached(root, preview["presets"][0], 64, "test-version"), "preview cache miss after render");
        require(!preset_preview_cached(root, preview["presets"][0], 64, "other-version"), "preview cache ignored version");
        require(finish_preset_previews(root, preview, Json::array()).at("presets")[0].contains("thumbnail_path"),
            "cached preview did not return thumbnail");
    } catch (...) { std::filesystem::remove_all(root); throw; }
    std::filesystem::remove_all(root);
}
}
int main() {
    try { difference_and_nonfinite(); typed_inputs_and_multiple_captures(); std::cout << "PASS LocalImageComparison\n"; return 0; }
    catch (const std::exception& error) { std::cerr << "FAIL LocalImageComparison: " << error.what() << '\n'; return 1; }
}
