#include "scene_context_resolver.hpp"

#include "state_error.hpp"
#include "name_suggestions.hpp"

#include <string>
#include <string_view>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v2;

bool matches(const JobContext& config, const proto::SceneContext& context) {
    return context.save_id() == config.save_id && context.dimension_id() == config.dimension_id &&
        context.time_preset_id() == config.time_preset_id &&
        context.camera_preset_id() == config.camera_preset_id && context.fov() == config.fov;
}

bool complete(const proto::SceneContext& context) {
    return !context.weather_preset_id().empty() && !context.settings_preset_id().empty() &&
        context.has_resolution() && context.resolution().width() != 0 && context.resolution().height() != 0;
}

[[noreturn]] void invalid(std::string_view message) {
    throw StateError("INVALID_PRESET", std::string(message), false);
}

void validate(const proto::ScenePreset& preset) {
    if (!complete(preset.context()) || preset.preset_id().empty() || preset.version().empty() ||
        preset.preset_sha256().empty()) {
        invalid("The matched server preset has incomplete provenance.");
    }
}

}

proto::ScenePreset SceneContextResolver::resolve_preset(
    const JobContext& config, const proto::ListPresetsResponse& response) {
    const proto::ScenePreset* match = nullptr;
    for (const auto& preset : response.presets()) {
        if (!matches(config, preset.context())) continue;
        if (match == nullptr) {
            match = &preset;
            continue;
        }
        const bool current_default = match->context().settings_preset_id() == "default";
        const bool candidate_default = preset.context().settings_preset_id() == "default";
        if (current_default != candidate_default) {
            if (candidate_default) match = &preset;
            continue;
        }
        invalid("The selected scene matches multiple server presets.");
    }
    if (match == nullptr) invalid("The selected scene does not match a server preset.");
    validate(*match);
    return *match;
}

proto::ScenePreset SceneContextResolver::resolve_preset(
    std::string_view preset_id, const proto::ListPresetsResponse& response) {
    const proto::ScenePreset* match = nullptr;
    for (const auto& preset : response.presets()) {
        if (preset.preset_id() != preset_id) continue;
        if (match != nullptr) invalid("The preset identifier is duplicated in the server catalog.");
        match = &preset;
    }
    if (match == nullptr) {
        std::vector<std::string> names;
        for (const auto& preset : response.presets()) names.push_back(preset.preset_id());
        throw StateError("INVALID_PRESET", "The requested scene preset was not found.", false,
            {{"suggestions", name_suggestions(preset_id, std::move(names))}});
    }
    validate(*match);
    return *match;
}

proto::SceneContext SceneContextResolver::resolve(
    const JobContext& config, const proto::ListPresetsResponse& response) {
    return resolve_preset(config, response).context();
}

}
