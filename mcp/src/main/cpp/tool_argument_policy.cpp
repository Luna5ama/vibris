#include "tool_argument_policy.hpp"

#include "job_context.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cmath>
#include <filesystem>
#include <string>
#include <unordered_set>

namespace vibris::mcp {
namespace {

bool safe_flat_name(std::string_view value) {
    if (value.empty() || value.size() > 128 || value == "." || value == "..") return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-';
    });
}

bool safe_id(std::string_view value) {
    return value.size() <= 48 && safe_flat_name(value);
}

bool safe_config_key(std::string_view value) {
    if (value.empty() || value.size() > 128 ||
        !((value.front() >= 'A' && value.front() <= 'Z') ||
          (value.front() >= 'a' && value.front() <= 'z') || value.front() == '_')) return false;
    return std::all_of(value.begin() + 1, value.end(), [](unsigned char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '_';
    });
}

bool safe_config_value(const Json& value) {
    if (value.is_boolean() || value.is_number()) return true;
    if (!value.is_string()) return false;
    const auto& text = value.get_ref<const std::string&>();
    return text.size() <= 4096 && std::all_of(text.begin(), text.end(), [](unsigned char character) {
        return character >= 0x20 && character <= 0x7e;
    });
}

bool safe_relative_path(std::string_view value) {
    if (value.empty()) return false;
    const std::filesystem::path path{std::string(value)};
    if (path.is_absolute() || path.has_root_name() || path.has_root_directory()) return false;
    return std::none_of(path.begin(), path.end(), [](const std::filesystem::path& component) {
        return component == "..";
    });
}

std::optional<InvocationError> validate_shader_config(const Json& arguments) {
    const auto config = arguments.find("config");
    if (config == arguments.end() || !config->is_object()) return std::nullopt;
    if (config->size() > 1024 || config->dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, "Shader config exceeds its size limit."};
    }
    for (const auto& [key, value] : config->items()) {
        if (!safe_config_key(key) || !safe_config_value(value)) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "arguments.config must contain safe option names and scalar ASCII values"};
        }
    }
    return std::nullopt;
}

std::optional<InvocationError> validate_config_values(const Json& values, const std::string& path) {
    if (!values.is_object()) return std::nullopt;
    if (values.size() > 1024 || values.dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, path + " exceeds its size limit."};
    }
    for (const auto& [key, value] : values.items()) {
        if (!safe_config_key(key) || !safe_config_value(value)) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                path + " must contain safe option names and scalar ASCII values"};
        }
    }
    return std::nullopt;
}

std::optional<InvocationError> validate_named_inputs(
    const Json& arguments, std::unordered_set<std::string>& source_ids,
    std::unordered_set<std::string>& config_ids) {
    if (const auto sources = arguments.find("sources"); sources != arguments.end() && sources->is_array()) {
        for (std::size_t index = 0; index < sources->size(); ++index) {
            const auto& source = (*sources)[index];
            if (!source.is_object() || !source.contains("id") || !source.at("id").is_string()) continue;
            const auto id = source.at("id").get<std::string>();
            if (!safe_id(id) || !source_ids.insert(id).second) {
                return InvocationError{InvocationErrorCode::InvalidArguments,
                    "arguments.sources contains an unsafe or repeated id"};
            }
        }
    }
    if (const auto configs = arguments.find("configs"); configs != arguments.end() && configs->is_array()) {
        for (std::size_t index = 0; index < configs->size(); ++index) {
            const auto& config = (*configs)[index];
            if (!config.is_object() || !config.contains("id") || !config.at("id").is_string()) continue;
            const auto id = config.at("id").get<std::string>();
            if (!safe_id(id) || !config_ids.insert(id).second) {
                return InvocationError{InvocationErrorCode::InvalidArguments,
                    "arguments.configs contains an unsafe or repeated id"};
            }
            if (config.contains("values")) {
                if (const auto error = validate_config_values(
                        config.at("values"), "arguments.configs[" + std::to_string(index) + "].values")) {
                    return error;
                }
            }
        }
    }
    return std::nullopt;
}

}

std::optional<InvocationError> validate_argument_policy(std::string_view tool_name, const Json& arguments) {
    if (arguments.is_object()) {
        if (const auto error = validate_shader_config(arguments)) return error;
    }
    if (!arguments.is_object()) return std::nullopt;
    std::unordered_set<std::string> source_ids;
    std::unordered_set<std::string> config_ids;
    if (const auto error = validate_named_inputs(arguments, source_ids, config_ids)) return error;
    const auto actions = arguments.find("actions");
    if (actions != arguments.end() && actions->is_array()) {
        for (std::size_t index = 0; index < actions->size(); ++index) {
            if (!(*actions)[index].is_object()) continue;
            const auto type = (*actions)[index].value("type", std::string{});
            if (type == "camera_motion") {
                const auto restore = arguments.find("restore_state");
                if (restore != arguments.end() &&
                    (!restore->value("on_success", true) || !restore->value("on_error", true))) {
                    return InvocationError{InvocationErrorCode::InvalidArguments,
                        "camera_motion requires restoration on success and error"};
                }
                const auto& keys = (*actions)[index].at("keyframes");
                if (keys.size() < 2 || keys.size() > 16 || keys.front().at("frame_offset") != 0 ||
                    keys.front().at("yaw_delta_degrees") != 0 || keys.front().at("pitch_delta_degrees") != 0) {
                    return InvocationError{InvocationErrorCode::InvalidArguments,
                        "camera_motion requires 2-16 keyframes starting at frame zero with zero deltas"};
                }
                for (const auto axis : {"x_delta", "y_delta", "z_delta"}) {
                    if (keys.front().value(axis, 0.0) != 0.0) return InvocationError{
                        InvocationErrorCode::InvalidArguments, "camera_motion must start at the current position"};
                }
                std::int64_t previous = -1;
                Json previous_key;
                for (const auto& key : keys) {
                    const auto offset = key.at("frame_offset").get<std::int64_t>();
                    const auto yaw = key.at("yaw_delta_degrees").get<double>();
                    const auto pitch = key.at("pitch_delta_degrees").get<double>();
                    if (offset <= previous || offset > 239 || !std::isfinite(yaw) || std::abs(yaw) > 720 ||
                        !std::isfinite(pitch) || std::abs(pitch) > 180) {
                        return InvocationError{InvocationErrorCode::InvalidArguments,
                            "camera_motion keyframes must be ordered, finite and bounded"};
                    }
                    for (const auto axis : {"x_delta", "y_delta", "z_delta"}) {
                        const double value = key.value(axis, 0.0);
                        if (!std::isfinite(value) || std::abs(value) > 16.0 ||
                            (previous >= 0 && std::abs(value-previous_key.value(axis, 0.0)) > offset-previous)) {
                            return InvocationError{InvocationErrorCode::InvalidArguments,
                                "camera_motion XYZ offsets must be finite, within 16 blocks and at most one block per frame"};
                        }
                    }
                    previous = offset;
                    previous_key = key;
                }
            }
            if (type == "load_shader") {
                if (tool_name == "vibris_run_matrix") {
                    return InvocationError{InvocationErrorCode::InvalidArguments,
                        "arguments.actions must not load shaders inside a matrix template"};
                }
                const auto source = (*actions)[index].value("source_id", std::string{});
                const auto config = (*actions)[index].value("config_id", std::string{});
                if (!source_ids.contains(source) || !config_ids.contains(config)) {
                    return InvocationError{InvocationErrorCode::InvalidArguments,
                        "load_shader references an unknown source or config id"};
                }
            }
            const auto name = (*actions)[index].find("artifact_name");
            if (name != (*actions)[index].end() && name->is_string() &&
                !safe_flat_name(name->get_ref<const std::string&>())) {
                return InvocationError{InvocationErrorCode::InvalidArguments,
                    "arguments.actions[" + std::to_string(index) + "].artifact_name must be a safe flat name"};
            }
            const auto path = (*actions)[index].find("path");
            if (path != (*actions)[index].end() && path->is_string() &&
                !safe_relative_path(path->get_ref<const std::string&>())) {
                return InvocationError{InvocationErrorCode::InvalidArguments,
                    "arguments.actions[" + std::to_string(index) + "].path must be relative and remain in the game directory"};
            }
        }
    }
    if (const auto matrix = arguments.find("matrix"); matrix != arguments.end() && matrix->is_object()) {
        for (const auto* axis : {"sources", "configs"}) {
            const auto values = matrix->find(axis);
            if (values == matrix->end() || !values->is_array() || values->empty()) {
                return InvocationError{InvocationErrorCode::InvalidArguments,
                    "arguments.matrix axes must not be empty"};
            }
            const auto& ids = std::string_view(axis) == "sources" ? source_ids : config_ids;
            std::unordered_set<std::string> selected;
            for (const auto& value : *values) {
                if (!value.is_string() || !ids.contains(value.get<std::string>()) ||
                    !selected.insert(value.get<std::string>()).second) {
                    return InvocationError{InvocationErrorCode::InvalidArguments,
                        "arguments.matrix references an unknown or repeated named input"};
                }
            }
        }
        const auto cases = matrix->at("sources").size() * matrix->at("configs").size();
        const std::size_t template_actions = actions != arguments.end() && actions->is_array() ? actions->size() : 0;
        if (arguments.value("recipe", std::string{}) != "profile_matrix" &&
            cases * (template_actions + 1) > 128) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "expanded matrix exceeds the action limit"};
        }
    }
    return std::nullopt;
}

}