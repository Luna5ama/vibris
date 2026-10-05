#include "result_mapper.hpp"

#include "git_repository.hpp"
#include "source_preparer.hpp"

#include <google/protobuf/descriptor.h>
#include <google/protobuf/message.h>
#include <google/protobuf/util/json_util.h>
#include <nlohmann/json.hpp>

#include <stdexcept>
#include <string>
#include <filesystem>
#include <map>

namespace vibris::mcp {
namespace proto = ::vibris::control::v2;
namespace {

void normalize_integer_scalars(const google::protobuf::Message& message, nlohmann::json& mapped);

std::string map_key(const google::protobuf::Message& entry,
    const google::protobuf::FieldDescriptor& key) {
    const auto* reflection = entry.GetReflection();
    switch (key.cpp_type()) {
    case google::protobuf::FieldDescriptor::CPPTYPE_BOOL:
        return reflection->GetBool(entry, &key) ? "true" : "false";
    case google::protobuf::FieldDescriptor::CPPTYPE_INT32:
        return std::to_string(reflection->GetInt32(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
        return std::to_string(reflection->GetInt64(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT32:
        return std::to_string(reflection->GetUInt32(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT64:
        return std::to_string(reflection->GetUInt64(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_STRING:
        return reflection->GetString(entry, &key);
    default:
        throw std::runtime_error("protobuf JSON map key type is unsupported");
    }
}

void normalize_field(const google::protobuf::Message& message,
    const google::protobuf::FieldDescriptor& field, nlohmann::json& target, const int repeated_index) {
    const auto* reflection = message.GetReflection();
    switch (field.cpp_type()) {
    case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
        target = repeated_index < 0
            ? reflection->GetInt64(message, &field)
            : reflection->GetRepeatedInt64(message, &field, repeated_index);
        break;
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT64:
        target = repeated_index < 0
            ? reflection->GetUInt64(message, &field)
            : reflection->GetRepeatedUInt64(message, &field, repeated_index);
        break;
    case google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE:
        normalize_integer_scalars(repeated_index < 0
                ? reflection->GetMessage(message, &field)
                : reflection->GetRepeatedMessage(message, &field, repeated_index),
            target);
        break;
    default:
        break;
    }
}

void normalize_integer_scalars(const google::protobuf::Message& message, nlohmann::json& mapped) {
    const auto* descriptor = message.GetDescriptor();
    const auto* reflection = message.GetReflection();
    for (int field_index = 0; field_index < descriptor->field_count(); ++field_index) {
        const auto* field = descriptor->field(field_index);
        const auto mapped_field = mapped.find(field->name());
        if (mapped_field == mapped.end()) continue;

        if (field->is_map()) {
            if (!mapped_field->is_object()) {
                throw std::runtime_error("protobuf JSON map-field mapping is inconsistent");
            }
            for (int index = 0; index < reflection->FieldSize(message, field); ++index) {
                const auto& entry = reflection->GetRepeatedMessage(message, field, index);
                const auto* entry_descriptor = entry.GetDescriptor();
                const auto* key_field = entry_descriptor->FindFieldByName("key");
                const auto* value_field = entry_descriptor->FindFieldByName("value");
                if (key_field == nullptr || value_field == nullptr) {
                    throw std::runtime_error("protobuf map entry descriptor is incomplete");
                }
                const auto key = map_key(entry, *key_field);
                const auto value = mapped_field->find(key);
                if (value == mapped_field->end()) {
                    throw std::runtime_error("protobuf JSON map entry is missing");
                }
                normalize_field(entry, *value_field, *value, -1);
            }
            continue;
        }

        if (field->is_repeated()) {
            if (!mapped_field->is_array() ||
                mapped_field->size() != static_cast<std::size_t>(reflection->FieldSize(message, field))) {
                throw std::runtime_error("protobuf JSON repeated-field mapping is inconsistent");
            }
            for (std::size_t index = 0; index < mapped_field->size(); ++index) {
                normalize_field(message, *field, mapped_field->at(index), static_cast<int>(index));
            }
        } else {
            normalize_field(message, *field, *mapped_field, -1);
        }
    }
}

void finalize_one(nlohmann::json& provenance) {
    if (!provenance.is_object()) return;
    const auto measured = provenance.value("source_snapshot_sha256", std::string{});
    const auto start_head = provenance.value("start_head", std::string{});
    if (measured.empty() || start_head.empty()) return;
    const auto worktree = provenance.value("worktree_root", std::string{});
    if (worktree.empty()) {
        provenance["completion_head"] = start_head;
        provenance["head_changed"] = false;
        provenance["stale"] = false;
        return;
    }
    if (provenance.value("requested_revision", std::string{}) != "workspace") {
        const auto completion_head = GitRepository(std::filesystem::path(worktree)).resolve_commit("HEAD");
        provenance["completion_head"] = completion_head;
        provenance["head_changed"] = completion_head != start_head;
        provenance["stale"] = false;
        return;
    }
    const auto completion = capture_workspace_provenance(
        std::filesystem::path(worktree),
        {.max_total_bytes = 512ULL * 1024ULL * 1024ULL, .max_files = 100'000});
    provenance["completion_head"] = completion.head;
    provenance["head_changed"] = completion.head != start_head;
    const auto delta = shader_content_delta_sha256(measured, completion.source_snapshot_sha256);
    provenance["stale"] = !delta.empty();
    if (!delta.empty()) provenance["dirty_shader_delta_sha256"] = delta;
}

void finalize_all(nlohmann::json& value) {
    if (value.is_array()) {
        for (auto& item : value) finalize_all(item);
        return;
    }
    if (!value.is_object()) return;
    if (value.contains("summary_json") && value["summary_json"].is_string()) {
        const auto summary = nlohmann::json::parse(value["summary_json"].get<std::string>(), nullptr, false);
        if (!summary.is_discarded()) { value["summary"] = summary; value.erase("summary_json"); }
    }
    for (auto& [key, item] : value.items()) {
        if (key == "provenance" || key == "baseline_provenance" || key == "candidate_provenance") {
            finalize_one(item);
        }
        finalize_all(item);
    }
}

void hide_operational_runtime_internals(nlohmann::json& mapped) {
    auto status = mapped.find("status");
    if (status == mapped.end() || !status->is_object()) return;
    const auto readiness = status->find("readiness");
    if (readiness == status->end() || !readiness->is_object() ||
        !readiness->value("core_online", false) ||
        !readiness->value("minecraft_connected", false)) {
        return;
    }

    // These fields describe internal lease and shader-load transitions, not whether
    // an agent may submit work. Exposing them makes a healthy, queueable server look
    // broken while Minecraft is compiling a shader pack. Core remains authoritative;
    // MCP only removes the transient diagnostics from the agent-facing projection.
    (*status)["operational"] = true;
    const auto last_error = status->find("last_error");
    const bool restart_launch_failed = last_error != status->end() && last_error->is_object() &&
        last_error->value("message", std::string{}).starts_with(
            "Scheduled Minecraft restart could not be launched:");
    status->erase("state");
    status->erase("can_start_job");
    status->erase("readiness");
    status->erase("active_lease");
    status->erase("transitions");
    if (!restart_launch_failed) status->erase("last_error");
}

void compact_result(nlohmann::json& value) {
    if (value.is_array()) {
        for (auto& item : value) compact_result(item);
        return;
    }
    if (!value.is_object()) return;
    if (value.contains("artifact_id") && value.contains("relative_path")) {
        value.erase("resource");
        value.erase("job_id");
        value.erase("request_id");
    }
    if (value.contains("pass_id") && value.contains("readable_resources")) value.erase("readable_resources");
    if (value.contains("effective_settings") && value["effective_settings"].is_object()) {
        auto& settings = value["effective_settings"];
        if (settings.contains("settings") && settings["settings"].is_array()) {
            nlohmann::json selected = nlohmann::json::array();
            for (const auto& setting : settings["settings"]) {
                const auto origin = setting.value("origin", std::string{});
                if (!setting.value("changed_from_default", false) &&
                    origin != "SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE" &&
                    origin != "SHADER_SETTING_ORIGIN_PRESET") continue;
                auto item = setting;
                item.erase("changed_from_default");
                constexpr std::string_view origin_prefix = "SHADER_SETTING_ORIGIN_";
                if (origin.starts_with(origin_prefix)) item["origin"] = origin.substr(origin_prefix.size());
                selected.push_back(std::move(item));
            }
            settings["setting_count"] = settings["settings"].size();
            settings.erase("settings");
            settings["non_default"] = std::move(selected);
        }
    }
    if (value.contains("programs") && value.contains("shader_generation") && value["programs"].is_array() &&
        (value["programs"].empty() || value["programs"][0].contains("compile_state"))) {
        auto& programs = value["programs"];
        value["program_count"] = programs.size();
        nlohmann::json failed = nlohmann::json::array();
        std::size_t succeeded = 0;
        for (const auto& program : programs) {
            if (program.value("compile_state", std::string{}) == "COMPILE_STATE_FAILED" ||
                program.value("link_state", std::string{}) == "COMPILE_STATE_FAILED" ||
                !program.value("diagnostics", nlohmann::json::array()).empty()) failed.push_back(program);
            if (program.value("compile_state", std::string{}) == "COMPILE_STATE_SUCCEEDED") ++succeeded;
        }
        value["succeeded_program_count"] = succeeded;
        value["diagnostic_program_count"] = failed.size();
        programs = std::move(failed);
    }
    for (auto& [key, item] : value.items()) compact_result(item);
}

std::string pointer_component(const std::string& value) {
    std::string result;
    for (const auto character : value) {
        if (character == '~') result += "~0";
        else if (character == '/') result += "~1";
        else result += character;
    }
    return result;
}

void deduplicate_result(nlohmann::json& value, const std::string& path,
    std::map<std::string, std::string>& settings, std::map<std::string, std::string>& artifacts,
    std::map<std::string, std::string>& resources) {
    if (value.is_array()) {
        for (std::size_t index = 0; index < value.size(); ++index)
            deduplicate_result(value[index], path + "/" + std::to_string(index), settings, artifacts, resources);
        return;
    }
    if (!value.is_object()) return;
    if (value.contains("settings_sha256") && value.contains("non_default") &&
        value["settings_sha256"].is_string() && !value["settings_sha256"].get_ref<const std::string&>().empty()) {
        const auto [found, inserted] = settings.emplace(value["settings_sha256"].get<std::string>(), path);
        if (!inserted) { value.erase("non_default"); value["settings_ref"] = found->second; }
    }
    if (value.contains("artifact_id") && value.contains("relative_path") && value.contains("sha256")) {
        const auto key = value["artifact_id"].get<std::string>() + ":" + value["sha256"].get<std::string>();
        const auto [found, inserted] = artifacts.emplace(key, path);
        if (!inserted) value = {{"artifact_id", value["artifact_id"]}, {"relative_path", value["relative_path"]},
            {"sha256", value["sha256"]}};
    }
    if (value.contains("resource") && value["resource"].is_object() && value["resource"].contains("logical_name")) {
        auto description = value["resource"];
        description.erase("frame_id");
        const auto [found, inserted] = resources.emplace(description.dump(), path + "/resource");
        if (!inserted) {
            const auto frame = value["resource"].value("frame_id", nlohmann::json(nullptr));
            value["resource"] = {{"resource_ref", found->second}, {"frame_id", frame}};
        }
    }
    for (auto& [key, item] : value.items())
        deduplicate_result(item, path + "/" + pointer_component(key), settings, artifacts, resources);
}

}

nlohmann::json ResultMapper::message(const google::protobuf::Message& value) {
    google::protobuf::util::JsonPrintOptions options;
    options.preserve_proto_field_names = true;
    options.always_print_fields_with_no_presence = true;

    std::string encoded;
    const auto status = google::protobuf::util::MessageToJsonString(value, &encoded, options);
    if (!status.ok()) {
        throw std::runtime_error("protobuf JSON mapping failed: " + status.ToString());
    }
    auto mapped = nlohmann::json::parse(encoded);
    normalize_integer_scalars(value, mapped);
    return mapped;
}

nlohmann::json ResultMapper::list_presets(const proto::ListPresetsResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::list_resources(const proto::ListResourcesResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::validation(const proto::ValidateContextResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::status(const proto::GetStatusResponse& response) {
    auto mapped = message(response);
    hide_operational_runtime_internals(mapped);
    return mapped;
}

nlohmann::json ResultMapper::artifacts(const proto::ManageArtifactsResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::server_message(const proto::ServerMessage& response) {
    return message(response);
}

void ResultMapper::finalize_provenance(nlohmann::json& result) {
    finalize_all(result);
}

nlohmann::json ResultMapper::agent_projection(
    const std::string_view tool, nlohmann::json result, const nlohmann::json& arguments) {
    compact_result(result);
    if (tool == "vibris_list_resources" && result.contains("catalog")) {
        auto& catalog = result["catalog"];
        auto& layouts = catalog["resource_layouts"] = nlohmann::json::object();
        catalog["texture_layout_defaults"] = {{"depth", 1}, {"layers", 1}, {"mip_levels", 1}};
        catalog["texture_layout_defaults"]["available_views"] = nlohmann::json::array({"CURRENT", "MAIN"});
        nlohmann::json frame = nullptr;
        bool common_frame = true;
        std::map<std::string, std::string> known;
        for (auto& resource : catalog["resources"]) {
            const auto resource_frame = resource.value("frame_id", 0);
            if (frame.is_null()) frame = resource_frame;
            else common_frame = common_frame && frame == resource_frame;
            auto layout = resource;
            const auto name = resource.at("logical_name");
            layout.erase("logical_name"); layout.erase("frame_id"); layout.erase("physical_name");
            if (layout.value("source_width", 0) == 0 || layout["source_width"] == layout.value("width", 0)) layout.erase("source_width");
            if (layout.value("source_height", 0) == 0 || layout["source_height"] == layout.value("height", 0)) layout.erase("source_height");
            for (const auto* field : {"kind", "scalar_type"}) {
                const auto prefix = std::string_view(field) == "kind" ? "RESOURCE_KIND_" : "SCALAR_TYPE_";
                auto text = layout.value(field, std::string{});
                if (text.starts_with(prefix)) layout[field] = text.substr(std::string_view(prefix).size());
            }
            for (auto& view : layout["available_views"]) {
                const auto text = view.get<std::string>();
                if (text.starts_with("TEXTURE_VIEW_")) view = text.substr(13);
            }
            if (layout["available_views"] == catalog["texture_layout_defaults"]["available_views"])
                layout.erase("available_views");
            if (layout["kind"] == "BUFFER") layout = {{"kind", "BUFFER"}, {"byte_size", layout.at("byte_size")}};
            else for (const auto* field : {"depth", "layers", "mip_levels"})
                if (layout.value(field, 0) == 1) layout.erase(field);
            const auto [found, inserted] = known.emplace(layout.dump(), "layout" + std::to_string(known.size()));
            if (inserted) layouts[found->second] = std::move(layout);
            resource = {{"logical_name", name}, {"layout_ref", found->second}, {"frame_id", resource.value("frame_id", 0)}};
        }
        if (common_frame && !frame.is_null()) {
            catalog["catalog_context"]["frame_id"] = frame;
            for (auto& resource : catalog["resources"]) resource.erase("frame_id");
        }
        catalog["program_defaults"] = {{"stage", "compute"}};
        std::map<std::string, std::size_t> entry_counts, main_counts;
        std::size_t program_count = 0;
        for (const auto& pass : catalog["passes"]) for (const auto& program : pass["programs"]) {
            ++program_count;
            ++entry_counts[program.value("entry_file", std::string{})];
            ++main_counts[program.value("main_source", std::string{})];
        }
        for (const auto& [path, count] : entry_counts)
            if (!path.empty() && count > 1 && count * 2 > program_count) catalog["program_defaults"]["entry_file"] = path;
        if (!main_counts.empty()) {
            const auto common = std::max_element(main_counts.begin(), main_counts.end(),
                [](const auto& a, const auto& b) { return a.second < b.second; });
            if (!common->first.empty() && common->second > 1) catalog["program_defaults"]["main_source"] = common->first;
        }
        catalog["program_stage_extensions"] = {{"compute", "csh"}, {"vertex", "vsh"},
            {"fragment", "fsh"}, {"geometry", "gsh"}, {"tesc", "tcs"}, {"tese", "tes"}};
        for (auto& pass : catalog["passes"]) {
            const auto pass_id = pass.at("pass_id").get<std::string>();
            const auto pass_program = pass_id.substr(pass_id.find_last_of('/') + 1);
            for (auto& program : pass["programs"]) {
                const auto id = program.value("program_id", std::string{});
                const auto stage = program.value("stage", std::string{});
                const auto& extensions = catalog["program_stage_extensions"];
                const auto& defaults = catalog["program_defaults"];
                if (defaults.contains("entry_file")) {
                    if (program.value("entry_file", std::string{}) == defaults["entry_file"].get<std::string>()) program.erase("entry_file");
                } else if (extensions.contains(stage) && program.value("entry_file", std::string{}) ==
                    "/" + id + "." + extensions[stage].get<std::string>()) program.erase("entry_file");
                if (defaults.contains("main_source") && program.value("main_source", std::string{}) == defaults["main_source"].get<std::string>())
                    program.erase("main_source");
                if (id == pass_program) program.erase("program_id");
                if (stage == "compute") program.erase("stage");
            }
            pass.erase("stage"); pass.erase("program_id");
        }
    }
    auto* experiment = &result;
    if (result.contains("result") && result["result"].is_object()) experiment = &result["result"];
    if (experiment->value("kind", std::string{}) == "profile_matrix" && experiment->contains("table") &&
        arguments.value("result_detail", experiment->value("result_detail", std::string("metrics"))) != "full")
        experiment->erase("cases");
    if (tool == "vibris_job" && !arguments.contains("event_cursor")) {
        const auto state = result.value("workflow_state", std::string{});
        if (state == "completed" || state == "failed" || state == "cancelled") result.erase("events");
    }
    if (tool == "vibris_list_presets" && result.contains("presets")) {
        for (auto& preset : result["presets"]) {
            const auto context = preset.value("context", nlohmann::json::object());
            nlohmann::json row{{"preset_id", preset.at("preset_id")},
                {"description", preset.value("description", std::string{})},
                {"tags", preset.value("tags", nlohmann::json::array())},
                {"preset_sha256", preset.value("preset_sha256", std::string{})}};
            for (const auto& [output, input] : {std::pair{"dimension", "dimension_id"},
                std::pair{"resolution", "resolution"}, std::pair{"time", "time_preset_id"},
                std::pair{"weather", "weather_preset_id"}})
                if (context.contains(input)) row[output] = context[input];
            if (preset.contains("thumbnail_path")) row["thumbnail_path"] = preset["thumbnail_path"];
            preset = std::move(row);
        }
    }
    std::map<std::string, std::string> settings, artifacts, resources;
    deduplicate_result(result, "", settings, artifacts, resources);
    return result;
}

}
