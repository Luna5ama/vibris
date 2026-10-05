#include "image_compare.hpp"
#include "config_document.hpp"
#include "state_error.hpp"
#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <limits>
#include <numeric>

namespace vibris::mcp {
namespace {
struct Selection { int x, y, width, height; std::vector<int> channels; };
Selection selection(const ImageData& image, const Json& arguments) {
    Selection selected{0, 0, image.width, image.height, {}};
    if (arguments.contains("region")) {
        const auto& region = arguments["region"];
        selected.x = region.at("x").get<int>(); selected.y = region.at("y").get<int>();
        selected.width = region.at("width").get<int>(); selected.height = region.at("height").get<int>();
    }
    if (selected.x < 0 || selected.y < 0 || selected.width <= 0 || selected.height <= 0 ||
        selected.x > image.width - selected.width || selected.y > image.height - selected.height)
        throw StateError("COMPARE_REGION_OUT_OF_BOUNDS", "Region exceeds " + std::to_string(image.width) + "x" + std::to_string(image.height));
    if (arguments.contains("channels")) selected.channels = arguments["channels"].get<std::vector<int>>();
    else { selected.channels.resize(image.channels); std::iota(selected.channels.begin(), selected.channels.end(), 0); }
    if (selected.channels.empty() || std::any_of(selected.channels.begin(), selected.channels.end(),
        [&](int value) { return value < 0 || value >= image.channels; })) throw StateError("COMPARE_CHANNEL_INVALID", "Channel selection is outside the image.");
    return selected;
}
void topology(const ImageData& a, const ImageData& b) {
    if (a.width != b.width || a.height != b.height || a.channels != b.channels || a.scalar_type != b.scalar_type)
        throw StateError("COMPARE_TOPOLOGY_MISMATCH", "Image dimensions, channels and scalar types must match.");
}
double quantile(std::vector<double> values, double p) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    const auto position = p * (values.size() - 1);
    const auto low = static_cast<std::size_t>(position), high = static_cast<std::size_t>(std::ceil(position));
    std::nth_element(values.begin(), values.begin() + low, values.end());
    const auto a = values[low];
    if (high == low) return a;
    std::nth_element(values.begin(), values.begin() + high, values.end());
    return a + (values[high] - a) * (position - low);
}
Json finite_value(double value) { return std::isfinite(value) ? Json(value) : Json(nullptr); }
struct Moments {
    long double absolute = 0, squared = 0, a = 0, b = 0, energy = 0;
    double maximum = 0;
    std::vector<double> errors;
    std::size_t mismatches = 0, invalid = 0;
    void add(double left, double right) {
        if (!std::isfinite(left) || !std::isfinite(right)) {
            ++invalid;
            if (!(left == right || (std::isnan(left) && std::isnan(right)))) ++mismatches;
            return;
        }
        const auto error = std::abs(left - right);
        absolute += error; squared += static_cast<long double>(error) * error;
        a += left; b += right; energy += static_cast<long double>(left) * left;
        maximum = std::max(maximum, error); errors.push_back(error);
    }
    Json json() const {
        const auto n = errors.size();
        return {{"mean_absolute_error", n ? finite_value(static_cast<double>(absolute / n)) : Json(nullptr)},
            {"root_mean_square_error", n ? finite_value(std::sqrt(static_cast<double>(squared / n))) : Json(nullptr)},
            {"max_absolute_error", n ? Json(maximum) : Json(nullptr)},
            {"p95_absolute_error", finite_value(quantile(errors, .95))},
            {"mean_a", n ? finite_value(static_cast<double>(a / n)) : Json(nullptr)},
            {"mean_b", n ? finite_value(static_cast<double>(b / n)) : Json(nullptr)},
            {"relative_mse", energy > 0 ? finite_value(static_cast<double>(squared / energy)) :
                (squared == 0 && n ? Json(0) : Json(nullptr))},
            {"finite_samples", n}, {"nonfinite_samples", invalid}, {"nonfinite_mismatches", mismatches}};
    }
};
int reflected(int coordinate, int size) {
    if (size == 1) return 0;
    while (coordinate < 0 || coordinate >= size) coordinate = coordinate < 0 ? -coordinate : 2 * size - coordinate - 2;
    return coordinate;
}
std::vector<double> gaussian(const std::vector<double>& input, int width, int height) {
    std::array<double, 11> kernel;
    double sum = 0;
    for (int i = -5; i <= 5; ++i) { kernel[i + 5] = std::exp(-i * i / 4.5); sum += kernel[i + 5]; }
    for (auto& value : kernel) value /= sum;
    std::vector<double> temp(input.size(), 0), result(input.size(), 0);
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x)
        for (int offset = -5; offset <= 5; ++offset) temp[static_cast<std::size_t>(y) * width + x] +=
            kernel[offset + 5] * input[static_cast<std::size_t>(y) * width + reflected(x + offset, width)];
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x)
        for (int offset = -5; offset <= 5; ++offset) result[static_cast<std::size_t>(y) * width + x] +=
            kernel[offset + 5] * temp[static_cast<std::size_t>(reflected(y + offset, height)) * width + x];
    return result;
}
double ssim(const ImageData& a, const ImageData& b, const Selection& region) {
    std::array<std::vector<double>, 5> moments;
    for (auto& array : moments) array.reserve(static_cast<std::size_t>(region.width) * region.height);
    const auto luminance = [&](const ImageData& image, std::size_t pixel) {
        if (region.channels.size() == 1) return image.values[pixel + region.channels[0]];
        if (image.channels >= 3 && std::find(region.channels.begin(), region.channels.end(), 0) != region.channels.end() &&
            std::find(region.channels.begin(), region.channels.end(), 1) != region.channels.end() &&
            std::find(region.channels.begin(), region.channels.end(), 2) != region.channels.end())
            return .2126 * image.values[pixel] + .7152 * image.values[pixel + 1] + .0722 * image.values[pixel + 2];
        return std::numeric_limits<double>::quiet_NaN();
    };
    for (int y = region.y; y < region.y + region.height; ++y) for (int x = region.x; x < region.x + region.width; ++x) {
        const auto pixel = (static_cast<std::size_t>(y) * a.width + x) * a.channels;
        const auto left = luminance(a, pixel), right = luminance(b, pixel);
        if (!std::isfinite(left) || !std::isfinite(right)) return std::numeric_limits<double>::quiet_NaN();
        const std::array values{left, right, left * left, right * right, left * right};
        for (std::size_t i = 0; i < values.size(); ++i) moments[i].push_back(values[i]);
    }
    for (auto& array : moments) array = gaussian(array, region.width, region.height);
    const auto c1 = std::pow(.01 * a.data_range, 2), c2 = std::pow(.03 * a.data_range, 2);
    long double sum = 0;
    for (std::size_t i = 0; i < moments[0].size(); ++i) {
        const auto left = moments[0][i], right = moments[1][i];
        const auto va = std::max(0.0, moments[2][i] - left * left), vb = std::max(0.0, moments[3][i] - right * right);
        const auto covariance = moments[4][i] - left * right;
        sum += (2 * left * right + c1) * (2 * covariance + c2) /
            ((left * left + right * right + c1) * (va + vb + c2));
    }
    return std::clamp(static_cast<double>(sum / moments[0].size()), -1.0, 1.0);
}
Json thresholds(Json metrics, const Json& limits) {
    Json violations = Json::array();
    for (const auto& [limit, metric] : {std::pair{"max_mean_absolute_error", "mean_absolute_error"},
        std::pair{"max_root_mean_square_error", "root_mean_square_error"}, std::pair{"max_p95_absolute_error", "p95_absolute_error"},
        std::pair{"max_absolute_error", "max_absolute_error"}, std::pair{"max_threshold_pixel_ratio", "threshold_pixel_ratio"},
        std::pair{"max_relative_mse", "relative_mse"}, std::pair{"min_ssim", "ssim"}}) {
        if (!limits.contains(limit)) continue;
        if (!metrics.contains(metric) || metrics[metric].is_null()) {
            violations.push_back({{"metric", metric}, {"reason", "unavailable"}}); continue;
        }
        const auto actual = metrics[metric].get<double>(), expected = limits[limit].get<double>();
        if (std::string_view(limit) == "min_ssim" ? actual < expected : actual > expected)
            violations.push_back({{"metric", metric}, {"actual", actual}, {"limit", expected}});
    }
    if (metrics.value("nonfinite_mismatches", std::size_t{}) != 0)
        violations.push_back({{"metric", "nonfinite_mismatches"}, {"actual", metrics["nonfinite_mismatches"]}, {"limit", 0}});
    metrics["violations"] = std::move(violations);
    metrics["passed"] = limits.empty() ? Json(nullptr) : Json(metrics["violations"].empty());
    metrics["verdict"] = limits.empty() ? "not_evaluated" : metrics["violations"].empty() ? "passed" : "failed";
    return metrics;
}
}

Json image_difference(const ImageData& a, const ImageData& b, const Json& arguments) {
    topology(a, b);
    const auto region = selection(a, arguments);
    const auto limits = arguments.value("thresholds", Json::object());
    const auto threshold = limits.value("pixel_error_threshold", 0.0);
    Moments overall;
    std::vector<Moments> channels(region.channels.size());
    std::size_t above = 0;
    for (int y = region.y; y < region.y + region.height; ++y) for (int x = region.x; x < region.x + region.width; ++x) {
        bool exceeds = false;
        for (std::size_t selected = 0; selected < region.channels.size(); ++selected) {
            const auto index = (static_cast<std::size_t>(y) * a.width + x) * a.channels + region.channels[selected];
            const auto left = a.values[index], right = b.values[index];
            channels[selected].add(left, right); overall.add(left, right);
            exceeds = exceeds || (std::isfinite(left) && std::isfinite(right) && std::abs(left - right) > threshold) ||
                ((!std::isfinite(left) || !std::isfinite(right)) &&
                    !(left == right || (std::isnan(left) && std::isnan(right))));
        }
        if (exceeds) ++above;
    }
    auto metrics = overall.json();
    metrics["sample_count"] = overall.errors.size();
    metrics["pixel_count"] = static_cast<std::uint64_t>(region.width) * region.height;
    metrics["threshold_pixel_ratio"] = static_cast<double>(above) / (static_cast<std::uint64_t>(region.width) * region.height);
    metrics["pixel_error_threshold"] = threshold;
    metrics["channels"] = Json::array();
    for (std::size_t index = 0; index < channels.size(); ++index) {
        auto row = channels[index].json(); row["channel"] = region.channels[index]; metrics["channels"].push_back(std::move(row));
    }
    metrics["psnr"] = nullptr; metrics["ssim"] = nullptr;
    if (a.data_range > 0 && b.data_range == a.data_range && !overall.errors.empty() && overall.invalid == 0) {
        const auto mse = static_cast<double>(overall.squared / overall.errors.size());
        metrics["psnr"] = mse == 0 ? Json(nullptr) : finite_value(10 * std::log10(a.data_range * a.data_range / mse));
        metrics["psnr_infinite"] = mse == 0;
        metrics["ssim"] = finite_value(ssim(a, b, region));
    }
    metrics["relative_mse_definition"] = "sum((b-a)^2)/sum(a^2)";
    metrics["ssim_definition"] = "luminance, 11x11 Gaussian sigma=1.5, population moments, reflected boundary";
    return thresholds(std::move(metrics), limits);
}

ToolOutcome compare_images(const std::filesystem::path& root, const Json& arguments) {
    const auto a_path = managed_file(root, arguments.at("a").at("path").get<std::string>());
    const auto b_path = managed_file(root, arguments.at("b").at("path").get<std::string>());
    for (auto path : {a_path, b_path}) if (path.extension() == ".bin") {
        path.replace_extension(".json"); static_cast<void>(managed_file(root, path));
    }
    const auto a = read_image(a_path), b = read_image(b_path);
    auto result = image_difference(a, b, arguments);
    result["a"] = a_path.string(); result["b"] = b_path.string();
    const auto mode = arguments.value("mode", std::string("difference"));
    if (mode == "statistical") {
        const auto sibling = [&](const std::filesystem::path& mean, const char* output) {
            const auto name = mean.filename().string();
            if (!name.ends_with(".mean.bin")) throw StateError("COMPARE_STATISTICS_MISSING", "Statistical input must name an accumulate .mean.bin file.");
            auto path = mean.parent_path() / (name.substr(0, name.size() - 9) + "." + output + ".bin");
            path = managed_file(root, path);
            auto meta = path; meta.replace_extension(".json"); static_cast<void>(managed_file(root, meta));
            return read_image(path);
        };
        const auto va = sibling(a_path, "variance"), vb = sibling(b_path, "variance");
        const auto sa = sibling(a_path, "stderr"), sb = sibling(b_path, "stderr");
        topology(a, va); topology(a, vb); topology(a, sa); topology(a, sb);
        const auto region = selection(a, arguments);
        std::vector<double> variance_ratios;
        long double z_sum = 0, relative_a = 0, relative_b = 0;
        std::size_t valid = 0, significant = 0;
        for (int y = region.y; y < region.y + region.height; ++y) for (int x = region.x; x < region.x + region.width; ++x)
            for (const auto channel : region.channels) {
                const auto i = (static_cast<std::size_t>(y) * a.width + x) * a.channels + channel;
                if (!std::isfinite(a.values[i]) || !std::isfinite(b.values[i]) || !std::isfinite(sa.values[i]) ||
                    !std::isfinite(sb.values[i]) || !std::isfinite(va.values[i]) || !std::isfinite(vb.values[i]) ||
                    sa.values[i] < 0 || sb.values[i] < 0 || va.values[i] < 0 || vb.values[i] < 0) continue;
                const auto denominator = std::hypot(sa.values[i], sb.values[i]);
                const auto z = denominator == 0
                    ? (b.values[i] == a.values[i] ? 0.0 : std::copysign(std::numeric_limits<double>::infinity(), b.values[i] - a.values[i]))
                    : (b.values[i] - a.values[i]) / denominator;
                ++valid; z_sum += z; if (std::abs(z) > 3) ++significant;
                if (va.values[i] > 0) variance_ratios.push_back(vb.values[i] / va.values[i]);
                if (a.values[i] != 0 && b.values[i] != 0) {
                    relative_a += va.values[i] / (a.values[i] * a.values[i]);
                    relative_b += vb.values[i] / (b.values[i] * b.values[i]);
                }
            }
        result["statistical"] = {{"assumption", "independent_runs"}, {"valid_samples", valid},
            {"abs_z_above_3_ratio", valid ? Json(static_cast<double>(significant) / valid) : Json(nullptr)},
            {"mean_z", valid ? finite_value(static_cast<double>(z_sum / valid)) : Json(nullptr)},
            {"variance_ratio_median", finite_value(quantile(variance_ratios, .5))},
            {"variance_ratio_p90", finite_value(quantile(variance_ratios, .9))},
            {"relative_mse_ratio", relative_a > 0 ? finite_value(static_cast<double>(relative_b / relative_a)) : Json(nullptr)}};
        const auto screen_statistics = [](const ImageData& image, int channel) {
            const auto& accumulation = image.metadata.at("accumulation");
            const auto& batches = accumulation.at("screen_batch_means");
            std::vector<double> values;
            for (const auto& batch : batches) {
                const auto& value = batch.is_array() ? batch.at(channel) : batch;
                if (value.is_number() && std::isfinite(value.get<double>())) values.push_back(value.get<double>());
            }
            if (values.size() < 2) return std::pair{std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::quiet_NaN()};
            const auto mean = std::accumulate(values.begin(), values.end(), 0.0) / values.size();
            double squared = 0;
            for (const auto value : values) squared += (value - mean) * (value - mean);
            return std::pair{mean, std::sqrt(squared / (values.size() - 1) / values.size())};
        };
        result["statistical"]["screen_mean_ratio"] = Json::array();
        result["statistical"]["standard_batch_means_se"] = a.metadata.at("accumulation").value("standard_batch_means_se", false) &&
            b.metadata.at("accumulation").value("standard_batch_means_se", false);
        result["statistical"]["stderr_estimator"] = "unweighted_valid_batch_means";
        result["statistical"]["screen_mean_scope"] = "full_capture";
        result["statistical"]["batch_metadata"] = {{"a", a.metadata.at("accumulation")}, {"b", b.metadata.at("accumulation")}};
        for (const auto channel : region.channels) {
            const auto [mean_a, se_a] = screen_statistics(a, channel);
            const auto [mean_b, se_b] = screen_statistics(b, channel);
            const auto ratio = mean_b / mean_a;
            const auto se = std::hypot(se_b / mean_a, mean_b * se_a / (mean_a * mean_a));
            result["statistical"]["screen_mean_ratio"].push_back({{"channel", channel},
                {"ratio", finite_value(ratio)}, {"standard_error", finite_value(se)}});
        }
    }
    const auto directory = root / ".vibris" / "analysis" / ("compare-" + detail::generate_uuid());
    static_cast<void>(managed_output_directory(root, directory));
    if (arguments.value("heatmap", true)) {
        const auto region = selection(a, arguments);
        std::vector<unsigned char> heatmap(static_cast<std::size_t>(region.width) * region.height * 3);
        const auto scale = result["max_absolute_error"].is_number() ? result["max_absolute_error"].get<double>() : 1;
        for (int y = 0; y < region.height; ++y) for (int x = 0; x < region.width; ++x) {
            double error = 0; bool invalid = false;
            for (const auto channel : region.channels) {
                const auto i = (static_cast<std::size_t>(y + region.y) * a.width + x + region.x) * a.channels + channel;
                if (!std::isfinite(a.values[i]) || !std::isfinite(b.values[i])) invalid = true;
                else error = std::max(error, std::abs(a.values[i] - b.values[i]));
            }
            const auto i = (static_cast<std::size_t>(y) * region.width + x) * 3;
            heatmap[i] = invalid ? 255 : static_cast<unsigned char>(scale > 0 ? std::clamp(error / scale, 0.0, 1.0) * 255 : 0);
            heatmap[i + 1] = 0; heatmap[i + 2] = invalid ? 255 : 0;
        }
        const auto path = directory / "heatmap.png";
        write_png(path, region.width, region.height, 3, heatmap); result["heatmap_path"] = path.string();
    }
    result["diff_path"] = (directory / "diff.json").string();
    std::ofstream output(directory / "diff.json", std::ios::binary);
    output << result.dump(2);
    if (!output) throw StateError("COMPARE_WRITE_FAILED", "Could not write comparison summary.");
    return result;
}

void compare_recipe_captures(const std::filesystem::path& root, const Json& arguments, ToolOutcome& outcome) {
    auto* result = std::get_if<Json>(&outcome);
    if (!result || arguments.value("recipe", std::string{}) != "ab_compare" || !result->value("success", false)) return;
    std::vector<std::string> a, b;
    for (const auto& receipt : result->value("action_receipts", Json::array())) {
        if (!receipt.contains("capture")) continue;
        for (const auto& artifact : receipt["capture"].value("artifacts", Json::array())) {
            if (artifact.value("role", std::string{}) != "ARTIFACT_ROLE_PRIMARY") continue;
            const auto path = artifact.at("relative_path").get<std::string>();
            const auto name = std::filesystem::path(path).filename().string();
            if (name.starts_with("a-")) a.push_back(path);
            if (name.starts_with("b-")) b.push_back(path);
        }
    }
    if (a.size() != arguments.at("captures").size() || b.size() != a.size())
        throw StateError("COMPARE_CAPTURE_MISSING", "A/B job did not return all requested capture pairs.");
    (*result)["comparisons"] = Json::array();
    for (std::size_t i = 0; i < a.size(); ++i) {
        const auto compared = compare_images(root, {{"a", {{"path", a[i]}}}, {"b", {{"path", b[i]}}},
            {"thresholds", arguments.value("visual_thresholds", Json::object())}});
        (*result)["comparisons"].push_back(std::get<Json>(compared));
        const auto& comparison = (*result)["comparisons"].back();
        (*result)["artifacts"].push_back({{"relative_path", comparison.at("diff_path")},
            {"kind", "ARTIFACT_KIND_BENCHMARK_METRICS"}, {"format", "ARTIFACT_FORMAT_JSON"}});
        if (comparison.contains("heatmap_path")) (*result)["artifacts"].push_back({{"relative_path", comparison.at("heatmap_path")},
            {"kind", "ARTIFACT_KIND_HEATMAP"}, {"format", "ARTIFACT_FORMAT_PNG"}});
    }
    const bool evaluated = !arguments.value("visual_thresholds", Json::object()).empty();
    const bool passed = std::all_of((*result)["comparisons"].begin(), (*result)["comparisons"].end(),
        [](const Json& comparison) { return comparison.at("passed").is_null() || comparison.at("passed").get<bool>(); });
    if (a.size() == 1) (*result)["comparison"] = (*result)["comparisons"][0];
    else (*result)["comparison"] = {{"passed", evaluated ? Json(passed) : Json(nullptr)},
        {"verdict", !evaluated ? "not_evaluated" : passed ? "passed" : "failed"}, {"capture_count", a.size()}};
    (*result)["capture_job_success"] = true;
    auto guards = visual_comparison_guards(*result, true);
    (*result)["visual_guards"] = guards;
    (*result)["success"] = guards.at("passed").get<bool>() && passed;
    (*result)["status"] = !guards.at("passed").get<bool>() ? "invalid_comparison" : passed ? "completed" : "completed_with_failures";
    (*result)["verdict"] = (*result)["comparison"]["verdict"];
    if (!guards.at("passed").get<bool>()) (*result)["error"] = {{"error_code", "INVALID_VISUAL_RECEIPT"},
        {"message", "The visual comparison receipt failed its deterministic-state guards."}, {"details", guards["mismatches"]}};
}
}
