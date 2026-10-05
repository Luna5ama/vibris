#include "image_data.hpp"
#include "state_error.hpp"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include <stb_image.h>
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include <stb_image_write.h>

#include <bit>
#include <algorithm>
#include <array>
#include <iterator>
#include <cmath>
#include <cstring>
#include <cwctype>
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <fstream>
#include <limits>
#include <map>
#include <memory>

namespace vibris::mcp {
std::filesystem::path managed_output_directory(const std::filesystem::path& root, const std::filesystem::path& path) {
    const auto local = root / ".vibris";
    std::filesystem::create_directories(local);
    const auto base = std::filesystem::canonical(local);
    const auto root_relative = base.lexically_relative(std::filesystem::canonical(root));
    const auto relative = std::filesystem::weakly_canonical(path).lexically_relative(base);
    if (root_relative.empty() || root_relative.is_absolute() || *root_relative.begin() == ".." ||
        relative.empty() || relative.is_absolute() || *relative.begin() == "..")
        throw StateError("INVALID_ARTIFACT_PATH", "Output directory must stay inside worktree/.vibris.");
    std::filesystem::create_directories(path);
    return path;
}
std::filesystem::path managed_file(const std::filesystem::path& root, const std::filesystem::path& path) {
    const auto alias = std::filesystem::absolute(path.is_absolute() ? path : root / path).lexically_normal();
    const auto local = std::filesystem::absolute(root / ".vibris").lexically_normal();
    const auto relative = alias.lexically_relative(local);
    if (relative.empty() || relative.is_absolute() || *relative.begin() == "..")
        throw StateError("INVALID_ARTIFACT_PATH", "Path must be inside worktree/.vibris.");
    // RAM-disk drivers can reject GetFinalPathNameByHandle, used by canonical(). Resolve only
    // the runtime's deliberate artifact link, then forbid any further reparse points.
    if (*relative.begin() == "artifact") {
        auto target = local / "artifact";
        const auto attributes = GetFileAttributesW(target.c_str());
        if (attributes != INVALID_FILE_ATTRIBUTES && attributes & FILE_ATTRIBUTE_REPARSE_POINT) {
            auto link = std::filesystem::read_symlink(target);
            target = std::filesystem::absolute(link.is_absolute() ? link : target.parent_path() / link).lexically_normal();
        }
        auto remainder = relative.begin();
        for (++remainder; remainder != relative.end(); ++remainder) target /= *remainder;
        auto checked = target.root_path();
        for (const auto& part : target.relative_path()) {
            checked /= part;
            const auto flags = GetFileAttributesW(checked.c_str());
            if (flags == INVALID_FILE_ATTRIBUTES || flags & FILE_ATTRIBUTE_REPARSE_POINT)
                throw StateError("INVALID_ARTIFACT_PATH", "Artifact target must exist without additional directory links.");
        }
        if (!std::filesystem::is_regular_file(target))
            throw StateError("INVALID_ARTIFACT_PATH", "Path must name a regular file.");
        return alias;
    }
    const auto base = std::filesystem::canonical(local);
    const auto full = std::filesystem::canonical(alias);
    auto component = full.begin();
    for (const auto& required : base) {
        if (component == full.end()) throw StateError("INVALID_ARTIFACT_PATH", "Path must be inside worktree/.vibris.");
        const auto a = component->native(), b = required.native();
        if (a.size() != b.size() || !std::equal(a.begin(), a.end(), b.begin(),
            [](wchar_t left, wchar_t right) { return std::towlower(left) == std::towlower(right); }))
            throw StateError("INVALID_ARTIFACT_PATH", "Path must be inside worktree/.vibris.");
        ++component;
    }
    if (!std::filesystem::is_regular_file(full)) throw StateError("INVALID_ARTIFACT_PATH", "Path must name a regular file.");
    return alias;
}

namespace {
double half_value(std::uint16_t bits) {
    const auto sign = bits & 0x8000 ? -1.0 : 1.0;
    const auto exponent = (bits >> 10) & 31;
    const auto fraction = bits & 1023;
    if (exponent == 31) return fraction ? std::numeric_limits<double>::quiet_NaN() : sign * std::numeric_limits<double>::infinity();
    if (exponent == 0) return sign * std::ldexp(static_cast<double>(fraction), -24);
    return sign * std::ldexp(1.0 + fraction / 1024.0, exponent - 15);
}
template<typename T> double scalar(const char* bytes, bool swap) {
    std::array<char, sizeof(T)> data;
    std::copy_n(bytes, sizeof(T), data.begin());
    if (swap) std::reverse(data.begin(), data.end());
    T value;
    std::memcpy(&value, data.data(), sizeof(T));
    return static_cast<double>(value);
}
}

ImageData read_image(const std::filesystem::path& path) {
    ImageData image;
    if (std::filesystem::file_size(path) > 512ULL * 1024 * 1024) throw StateError("COMPARE_INPUT_TOO_LARGE", "Input exceeds 512 MiB.");
    if (path.extension() == ".png") {
        std::ifstream input(path, std::ios::binary);
        const std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
        if (!stbi_info_from_memory(bytes.data(), static_cast<int>(bytes.size()), &image.width, &image.height, &image.channels) ||
            static_cast<std::uint64_t>(image.width) * image.height * image.channels > 64ULL * 1024 * 1024)
            throw StateError("COMPARE_DECODE_FAILED", "PNG dimensions are invalid or exceed the decoded size limit.");
        const auto count = static_cast<std::size_t>(image.width) * image.height * image.channels;
        image.values.resize(count);
        const bool sixteen = stbi_is_16_bit_from_memory(bytes.data(), static_cast<int>(bytes.size())) != 0;
        image.scalar_type = sixteen ? "PNG16" : "PNG8";
        image.data_range = 1;
        if (sixteen) {
            std::unique_ptr<stbi_us, decltype(&stbi_image_free)> data(stbi_load_16_from_memory(bytes.data(),
                static_cast<int>(bytes.size()), &image.width, &image.height, &image.channels, 0), stbi_image_free);
            if (!data) throw StateError("COMPARE_DECODE_FAILED", "PNG decode failed.");
            for (std::size_t index = 0; index < count; ++index) image.values[index] = data.get()[index] / 65535.0;
        } else {
            std::unique_ptr<stbi_uc, decltype(&stbi_image_free)> data(stbi_load_from_memory(bytes.data(),
                static_cast<int>(bytes.size()), &image.width, &image.height, &image.channels, 0), stbi_image_free);
            if (!data) throw StateError("COMPARE_DECODE_FAILED", "PNG decode failed.");
            for (std::size_t index = 0; index < count; ++index) image.values[index] = data.get()[index] / 255.0;
        }
        return image;
    }
    if (path.extension() != ".bin") throw StateError("COMPARE_DECODE_FAILED", "Only PNG and typed BIN are supported.");
    auto sidecar = path;
    sidecar.replace_extension(".json");
    std::ifstream metadata(sidecar);
    if (!metadata) throw StateError("COMPARE_METADATA_MISSING", "BIN requires a same-name JSON sidecar.");
    if (std::filesystem::file_size(sidecar) > 1024 * 1024)
        throw StateError("COMPARE_METADATA_INVALID", "BIN metadata exceeds 1 MB.");
    image.metadata = Json::parse(metadata);
    const auto& description = image.metadata.contains("resource") ? image.metadata["resource"] : image.metadata;
    image.width = description.at("width").get<int>();
    image.height = description.at("height").get<int>();
    image.channels = description.at("channel_count").get<int>();
    image.scalar_type = description.at("scalar_type").get<std::string>();
    if (image.scalar_type.starts_with("SCALAR_TYPE_")) image.scalar_type.erase(0, 12);
    const std::map<std::string, std::size_t> sizes{{"FLOAT16", 2}, {"FLOAT32", 4}, {"FLOAT64", 8},
        {"UINT8", 1}, {"INT8", 1}, {"UINT16", 2}, {"INT16", 2}, {"UINT32", 4}, {"INT32", 4}, {"UINT64", 8}, {"INT64", 8}};
    if (!sizes.contains(image.scalar_type)) throw StateError("COMPARE_DECODE_FAILED", "Unsupported BIN scalar type: " + image.scalar_type);
    if (image.width <= 0 || image.height <= 0 || image.channels < 1 || image.channels > 4 || description.value("depth", 1) != 1)
        throw StateError("COMPARE_DECODE_FAILED", "Comparison requires a nonempty 2D image with one to four channels.");
    const auto count = static_cast<std::uint64_t>(image.width) * image.height * image.channels;
    if (count > 64ULL * 1024 * 1024 || std::filesystem::file_size(path) != count * sizes.at(image.scalar_type))
        throw StateError("COMPARE_DECODE_FAILED", "BIN byte size does not match metadata or exceeds the decoded size limit.");
    const auto endian = description.value("endianness", std::string("native"));
    if (endian != "native" && endian != "little" && endian != "big") throw StateError("COMPARE_DECODE_FAILED", "Unknown BIN endianness.");
    const bool swap = endian == "big";
    std::ifstream input(path, std::ios::binary);
    const std::vector<char> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
    image.values.resize(static_cast<std::size_t>(count));
    for (std::size_t index = 0; index < count; ++index) {
        const auto* value = bytes.data() + index * sizes.at(image.scalar_type);
        const auto& type = image.scalar_type;
        if (type == "FLOAT16") image.values[index] = half_value(static_cast<std::uint16_t>(scalar<std::uint16_t>(value, swap)));
        else if (type == "FLOAT32") image.values[index] = scalar<float>(value, swap);
        else if (type == "FLOAT64") image.values[index] = scalar<double>(value, swap);
        else if (type == "UINT8") image.values[index] = scalar<std::uint8_t>(value, swap);
        else if (type == "INT8") image.values[index] = scalar<std::int8_t>(value, swap);
        else if (type == "UINT16") image.values[index] = scalar<std::uint16_t>(value, swap);
        else if (type == "INT16") image.values[index] = scalar<std::int16_t>(value, swap);
        else if (type == "UINT32") image.values[index] = scalar<std::uint32_t>(value, swap);
        else if (type == "INT32") image.values[index] = scalar<std::int32_t>(value, swap);
        else if (type == "UINT64") image.values[index] = scalar<std::uint64_t>(value, swap);
        else image.values[index] = scalar<std::int64_t>(value, swap);
    }
    const auto numeric = description.value("numeric_class", std::string{});
    if (numeric == "unorm") image.data_range = std::ldexp(1.0, static_cast<int>(sizes.at(image.scalar_type) * 8)) - 1;
    if (numeric == "snorm") image.data_range = std::ldexp(1.0, static_cast<int>(sizes.at(image.scalar_type) * 8)) - 2;
    return image;
}

void write_png(const std::filesystem::path& path, int width, int height, int channels,
    const std::vector<unsigned char>& pixels) {
    const auto write = [](void* context, void* data, int size) {
        static_cast<std::ofstream*>(context)->write(static_cast<char*>(data), size);
    };
    std::ofstream output(path, std::ios::binary);
    if (!output || !stbi_write_png_to_func(write, &output, width, height, channels, pixels.data(), width * channels) || !output)
        throw StateError("COMPARE_WRITE_FAILED", "Could not write PNG: " + path.string());
}
}
