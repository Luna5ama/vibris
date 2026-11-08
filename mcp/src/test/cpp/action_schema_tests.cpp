#include "tool_registry.hpp"
#include "mcp_stdio_server.hpp"
#include <sstream>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include "result_mapper.hpp"
#include "artifact_prune.hpp"

#include <algorithm>
#include <array>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <filesystem>
#include <fstream>

namespace {

using vibris::mcp::InvocationError;
using vibris::mcp::Json;
using vibris::mcp::ToolRegistry;

void require(const bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

const Json& definition(const ToolRegistry& registry, const std::string_view name) {
    const auto found = std::find_if(registry.definitions().begin(), registry.definitions().end(),
        [name](const Json& value) { return value.at("name").get<std::string>() == name; });
    if (found == registry.definitions().end()) throw std::runtime_error("missing tool definition");
    return *found;
}

void verify_typed_schema(const Json& schema, const std::string& path) {
    require(schema.is_object(), path + " is not an object schema");
    require(!schema.contains("oneOf"), path + " exposes an unflattened oneOf");
    if (schema.contains("oneOf")) {
        require(schema.at("oneOf").is_array() && !schema.at("oneOf").empty(), path + " has an empty oneOf");
        for (std::size_t index = 0; index < schema.at("oneOf").size(); ++index) {
            verify_typed_schema(schema.at("oneOf")[index], path + ".oneOf[" + std::to_string(index) + "]");
        }
        return;
    }
    require(schema.contains("type") && schema.at("type").is_string(), path + " has no concrete type");
    if (schema.value("type", std::string{}) == "object" && schema.contains("properties")) {
        require(!schema.at("properties").contains("args"), path + " exposes args: unknown");
        for (const auto& [name, property] : schema.at("properties").items()) {
            verify_typed_schema(property, path + ".properties." + name);
        }
    }
    if (schema.value("type", std::string{}) == "array") {
        require(schema.contains("items"), path + " has untyped array items");
        verify_typed_schema(schema.at("items"), path + ".items");
    }
}

void exact_v2_tool_catalog() {
    const ToolRegistry registry;
    const std::array expected{
        "vibris_get_status",
        "vibris_restart",
        "vibris_list_presets",
        "vibris_list_resources",
        "mcp_vibiris_nsight_analyze",
        "vibris_run_recipe",
        "vibris_run_actions",
        "vibris_run_matrix",
        "vibris_job",
        "vibris_artifacts",
        "vibris_compare",
        "vibris_preview_presets",
    };
    require(registry.definitions().size() == expected.size(), "tools/list exposes an unexpected tool count");
    std::unordered_set<std::string> names;
    for (const auto& tool : registry.definitions()) {
        const auto name = tool.at("name").get<std::string>();
        names.insert(name);
        require(tool.at("schema_version") == 2, "tool metadata omitted schema_version 2");
        require(tool.contains("inputSchema") && tool.contains("outputSchema"), "tool omitted a schema");
        require(!tool.at("inputSchema").contains("oneOf"), "tool has a forbidden top-level oneOf");
        const auto& required = tool.at("inputSchema").at("required");
        require(std::find(required.begin(), required.end(), "worktree_root") != required.end(),
            "tool omitted required worktree_root");
        verify_typed_schema(tool.at("inputSchema"), name + ".inputSchema");
        verify_typed_schema(tool.at("outputSchema"), name + ".outputSchema");
        if (name == "vibris_get_status" || name == "vibris_run_recipe" ||
            name == "vibris_run_actions" || name == "vibris_run_matrix") {
            const auto description = tool.at("description").get<std::string>();
            require(description.find("round-robin") != std::string::npos,
                name + " omitted round-robin admission guidance");
            require(description.find("never wait for a global idle lease") != std::string::npos,
                name + " omitted the hard no-global-idle-wait rule");
            if (name != "vibris_get_status") {
                require(description.find("operation=wait") != std::string::npos,
                    name + " omitted durable blocking-wait guidance");
            }
        }
        if (name == "vibris_job") {
            const auto description = tool.at("description").get<std::string>();
            require(description.find("operation=wait") != std::string::npos &&
                    description.find("Never poll") != std::string::npos,
                "vibris_job omitted the blocking wait and hard no-poll guidance");
        }
        if (name == "vibris_restart") {
            require(tool.at("annotations").at("destructiveHint") == true,
                "vibris_restart must disclose its destructive lifecycle effect");
            const auto description = tool.at("description").get<std::string>();
            require(description.find("already accepted") != std::string::npos &&
                    description.find("wait") != std::string::npos,
                "vibris_restart omitted graceful drain and retry guidance");
        }
    }
    require(names == std::unordered_set<std::string>(expected.begin(), expected.end()),
        "tools/list exposed the wrong v2 names");
}

void exact_filters_and_job_control() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    const Json scope{{"worktree_root", "I:\\shader-worktree"}};
    const auto& ms = definition(registry, "vibris_run_matrix").at("inputSchema").at("properties");
    require(ms.contains("preset_id") && ms.contains("presets"), "matrix scene selectors missing");
    Json m{{"worktree_root", "I:\\shader-worktree"}, {"sources", Json::array({{{"id", "s"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "c"}, {"values", Json::object()}}})},
        {"matrix", {{"sources", Json::array({"s"})}, {"configs", Json::array({"c"})}}},
        {"actions", Json::array({{{"type", "wait_frames"}, {"frames", 1}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_matrix", m)), "matrix accepted no scene");
    m["preset_id"] = "scene";
    m["unfreeze_world"] = true;
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", m)), "matrix rejected preset_id");
    m["unfreeze_world"] = "true";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_matrix", m)),
        "matrix accepted a non-boolean unfreeze_world");
    m["unfreeze_world"] = true;
    auto actions = scope;
    actions["preset_id"] = "scene";
    actions["actions"] = m.at("actions");
    actions["unfreeze_world"] = true;
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", actions)),
        "actions rejected top-level unfreeze_world");
    actions["actions"][0]["unfreeze_world"] = true;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", actions)),
        "an individual action accepted unfreeze_world");
    m["presets"] = Json::array({"scene", "scene2"});
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_matrix", m)), "matrix accepted both selectors");
    m.erase("preset_id");
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", m)), "matrix rejected presets");

    auto status = scope;
    status["wait_until"] = "can_accept_job";
    require(std::holds_alternative<Json>(registry.invoke("vibris_get_status", status)),
        "round-robin admission wait was rejected");
    status["wait_until"] = "can_start_job";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_get_status", status)),
        "removed global-idle wait remained available");

    auto restart = scope;
    restart["reason"] = "deploy new Vibris build";
    require(std::holds_alternative<Json>(registry.invoke("vibris_restart", restart)),
        "typed graceful restart request was rejected");

    auto presets = scope;
    presets["preset_id"] = "sky-noon-1";
    presets["tags"] = Json::array({"sky"});
    require(std::holds_alternative<Json>(registry.invoke("vibris_list_presets", presets)),
        "exact preset filters were rejected");
    presets.erase("preset_id");
    presets["filter"] = "sky";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_list_presets", presets)),
        "legacy fuzzy preset filter was accepted");

    auto resources = scope;
    resources["kinds"] = Json::array({"texture", "buffer"});
    resources["logical_name"] = "colortex0";
    resources["pass_id"] = "composite/composite1";
    require(std::holds_alternative<Json>(registry.invoke("vibris_list_resources", resources)),
        "exact resource filters were rejected");
    resources["logical_name"] = "colortex0.main";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_list_resources", resources)),
        "physical texture suffix alias was accepted");

    auto job = scope;
    job["operation"] = "query";
    job["job_id"] = "job-1";
    require(std::holds_alternative<Json>(registry.invoke("vibris_job", job)), "typed job query was rejected");
    job["operation"] = "wait";
    job["timeout_ms"] = 300'000;
    require(std::holds_alternative<Json>(registry.invoke("vibris_job", job)), "typed job wait was rejected");
    auto old_control = scope;
    old_control["recipe"] = "profile_matrix";
    old_control["operation"] = "status";
    old_control["job_id"] = "job-1";
    old_control["preset_id"] = "scene";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", old_control)),
        "legacy recipe control operation was accepted");

    auto recovery = scope;
    recovery["recipe"] = "recover_runtime";
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", recovery)),
        "source-free recovery recipe was rejected");
    recovery["preset_id"] = "scene";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", recovery)),
        "recovery recipe accepted an unrelated scene preset");
    auto profile = scope;
    profile["recipe"] = "profile";
    profile["frames"] = 4;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", profile)),
        "scene recipe accepted a missing preset");
    auto benchmark = scope;
    benchmark["preset_id"] = "scene";
    benchmark["recipe"] = "benchmark_ab";
    benchmark["baseline"] = {{"kind", "workspace"}};
    benchmark["candidate"] = {{"kind", "workspace"}};
    benchmark["frames"] = 4;
    benchmark["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "benchmark accepted missing mandatory metric guards");
    benchmark["metrics"] = Json::array({
        {{"metric_id", "target"}, {"role", "target"}},
        {{"metric_id", "sibling"}, {"role", "sibling"}, {"max_regression_ratio", 0.03}},
        {{"metric_id", "sentinel"}, {"role", "sentinel"}, {"max_regression_ratio", 0.01}},
    });
    benchmark["visual"] = {{"max_threshold_pixel_ratio", 0.001}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", benchmark)),
        "typed benchmark metrics with explicit guardrails were rejected");
    benchmark["visual"] = {{"warmup_frames", 4}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "benchmark accepted a visual gate without a deterministic threshold");
    benchmark["visual"] = {{"max_threshold_pixel_ratio", 0.001}};
    benchmark["metrics"][0]["max_regression_ratio"] = 0.0;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "target metric accepted a sibling/sentinel regression threshold");
    benchmark["metrics"].erase(benchmark["metrics"].begin());
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "benchmark metrics without a target were accepted");
}

void typed_actions_reject_aliases() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json request{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"}};
    Json load = request;
    load["sources"] = Json::array({{{"id", "candidate"}, {"kind", "workspace"}}});
    load["configs"] = Json::array({{{"id", "quality"}, {"values", {{"QUALITY", 2}}}}});
    load["actions"] = Json::array({{{"type", "load_shader"},
        {"source_id", "candidate"}, {"config_id", "quality"}}});
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", load)),
        "typed source_id/config_id load action was rejected");
    load["actions"] = Json::array({{{"type", "load_shader"},
        {"source", "candidate"}, {"config", "quality"}}});
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", load)),
        "obsolete source/config load action was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_texture"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "alternate"}, {"mip_level", 1}, {"layer", 0}}},
         {"format", "bin"}, {"artifact_name", "color-bin"}},
        {{"type", "dump_buffer"}, {"resource", {{"logical_name", "sceneData"}}},
         {"artifact_name", "scene-data"}},
        {{"type", "dump_texture"}, {"pass_id", "composite/composite21"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "current"}, {"mip_level", 0}, {"layer", 0}}},
         {"format", "png"}, {"artifact_name", "shade-diffuse"}},
        {{"type", "dump_buffer"}, {"pass_id", "prepare/prepare3"},
         {"resource", {{"logical_name", "sceneData"}}}, {"artifact_name", "scene-data-after"}},
        {{"type", "list_resources"}, {"kinds", Json::array({"texture"})}},
    });
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "typed v2 resource actions were rejected");
    request["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "explicit transactional restore policy was rejected");
    request.erase("restore_state");

    request["actions"] = Json::array({
        {{"type", "nsight_gpu_trace"},
         {"capture", {{"mode", "single"}, {"pass_id", "composite/composite21"}}},
         {"artifact_name", "composite21-trace"}, {"architecture", "Ada"}},
        {{"type", "nsight_gpu_trace"},
         {"capture", {{"mode", "multi"}, {"capture_type", "composite"}}},
         {"artifact_name", "composite-trace"}, {"architecture", "Ada"},
         {"replay_backend", "gl"}, {"replay_frames", 300},
         {"start_after_ms", 1000}, {"max_duration_ms", 1000}},
    });
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "typed single/multi atomic Nsight actions were rejected");

    for (const auto* removed_type : {"capture_pass", "capture_multi"}) {
        request["actions"] = Json::array({{{"type", removed_type}, {"output_path", "removed"}}});
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
            "removed raw replay-capture action remained public");
    }

    for (const auto* old_type : {"list_textures", "list_buffers", "dump_texture_v2",
             "dump_texture_after_pass", "dump_buffer_after_pass"}) {
        request["actions"] = Json::array({{{"type", old_type}}});
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
            "legacy action alias was accepted");
    }
    request["actions"] = Json::array({
        {{"type", "dump_texture"},
         {"resource", {{"logical_name", "colortex0.alt"}, {"view", "current"}}},
         {"format", "png"}, {"artifact_name", "bad-alias"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "physical texture suffix alias was accepted in an action");

    request["actions"] = Json::array({
        {{"type", "dump_texture"}, {"pass_id", "composite/composite21"},
         {"resource", {{"logical_name", "colortex0"}}},
         {"format", "png"}, {"artifact_name", "missing-view"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "texture selector without an explicit view was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_buffer"}, {"pass_id", "prepare/prepare3"},
         {"resource", {{"logical_name", "sceneData"}, {"view", "current"}}},
         {"artifact_name", "buffer-view"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "buffer selector with a texture view was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_texture"}, {"pass_id", "composite21"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "current"}}},
         {"format", "png"}, {"artifact_name", "fuzzy-pass"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "non-canonical after-pass identifier was accepted");

    Json debug_bundle{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "capture_debug_bundle"}, {"textures", Json::array({"colortex0.main"})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", debug_bundle)),
        "physical texture suffix alias was accepted in a debug-bundle recipe");

    Json ab_compare{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "ab_compare"},
        {"a", {{"label", "baseline"}, {"source", {{"kind", "workspace"}}}}},
        {"b", {{"label", "candidate"}, {"source", {{"kind", "workspace"}}}}},
        {"captures", Json::array({{{"type", "texture"}, {"name", "colortex0.alt"}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", ab_compare)),
        "physical texture suffix alias was accepted in an A/B capture recipe");
}

void compile_validation_shape_is_strict_and_typed() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json single{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "compile_validate"}, {"execution", "sync"},
        {"source", {{"kind", "workspace"}}}, {"config", {{"QUALITY", 2}}},
        {"baseline", {{"kind", "commit"}, {"revision", "HEAD~1"}}},
        {"baseline_config", {{"QUALITY", 1}}}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", single)),
        "typed single compile_validate recipe was rejected");

    Json matrix{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "compile_validate"}, {"execution", "async"},
        {"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "quality"}, {"values", {{"QUALITY", 2}}}}})},
        {"matrix", {{"sources", Json::array({"candidate"})}, {"configs", Json::array({"quality"})}}}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", matrix)),
        "typed async compile validation matrix was rejected");

    auto invalid = single;
    invalid["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate rejected explicit no-restoration policy");
    invalid = single;
    invalid["frames"] = 4;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate accepted render sampling fields");
    invalid = matrix;
    invalid["source"] = {{"kind", "workspace"}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate mixed single and matrix source forms");
    invalid = single;
    invalid.erase("baseline");
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate accepted baseline_config without a baseline source");
}

void bounded_single_structured_result() {
    constexpr std::string_view marker = "FULL_PAYLOAD_MARKER";
    const ToolRegistry registry([](std::string_view, const Json&) {
        return Json{{"marker", marker}, {"large", std::string(16 * 1024, 'x')}};
    });
    const auto invocation = registry.invoke("vibris_get_status", {{"worktree_root", "I:\\shader-worktree"}});
    require(std::holds_alternative<Json>(invocation), "representative result invocation failed");
    const auto& result = std::get<Json>(invocation);
    require(result.at("content").size() == 1, "result must contain one bounded text summary");
    const auto summary = result.at("content").front().at("text").get<std::string>();
    require(summary.size() <= 2048 && summary.find(marker) == std::string::npos,
        "text content duplicated or exceeded the structured payload");
    const auto& structured = result.at("structuredContent");
    require(structured.at("schema_version") == 2 && structured.at("success") == true,
        "structured result omitted its v2 envelope");
    require(structured.at("result").at("marker").get<std::string>() == marker,
        "full result was not present exactly in structuredContent");
    require(result.dump().find(std::string(marker)) == result.dump().rfind(std::string(marker)),
        "full payload marker was duplicated outside structuredContent");
}

void local_nsight_analysis_shape_is_strict_and_typed() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json request{{"worktree_root", "I:\\shader-worktree"},
                 {"artifact_path", "I:\\shader-worktree\\.vibris\\artifact\\trace.nsight.bundle.json"},
                 {"query", {{"operation", "summary"}}}};
    require(std::holds_alternative<Json>(registry.invoke("mcp_vibiris_nsight_analyze", request)),
        "typed local Nsight summary query was rejected");
    request["query"] = {{"operation", "metric"}, {"name", "dramc__throughput"},
                        {"in_marker", "^Replay$"}, {"all_matches", true}};
    require(std::holds_alternative<Json>(registry.invoke("mcp_vibiris_nsight_analyze", request)),
        "typed local Nsight metric query was rejected");
    request["query"] = {{"operation", "not_a_query"}};
    require(std::holds_alternative<InvocationError>(registry.invoke("mcp_vibiris_nsight_analyze", request)),
        "unknown local Nsight query was accepted");
}

void agent_projection_preserves_full_results() {
    const ToolRegistry registry;
    const auto& resource = definition(registry, "vibris_run_actions").at("inputSchema").at("properties")
        .at("actions").at("items").at("properties").at("resource");
    const auto required = resource.value("required", Json::array());
    require(std::find(required.begin(), required.end(), "view") == required.end(),
        "flattened action schema incorrectly requires a texture view for buffer dumps");
    const auto action_forms = definition(registry, "vibris_run_actions").at("inputSchema")
        .at("properties").at("actions").at("items").at("description").get<std::string>();
    require(action_forms.find("dump_texture: required") != std::string::npos &&
        action_forms.find("resource requires [\"logical_name\",\"view\"]") != std::string::npos,
        "tools/list concealed conditional texture selector requirements");
    std::ifstream stream(std::filesystem::path(__FILE__).parent_path() / "alpha_piscium_result.json");
    require(stream.good(), "missing Alpha Piscium fixture");
    const auto original = Json::parse(stream);
    const auto before = original.dump();
    const auto projected = vibris::mcp::ResultMapper::agent_projection("vibris_job", original, Json::object());
    std::cout << "Alpha Piscium projected bytes=" << projected.dump().size() << '\n';
    require(original.dump() == before, "projection mutated full result");
    require(projected.dump().size() <= 15 * 1024, "Alpha Piscium projected result exceeds 15 KB: " + std::to_string(projected.dump().size()));
    const auto& full = original.at("result").at("provenance").at("effective_settings");
    const auto& compact = projected.at("result").at("provenance").at("effective_settings");
    require(full.at("settings_sha256") == compact.at("settings_sha256"), "settings SHA was lost");
    require(compact.at("setting_count") == full.at("settings").size(), "setting count changed");
    const auto& expanded = compact.contains("settings_ref")
        ? projected.at(Json::json_pointer(compact.at("settings_ref").get<std::string>())) : compact;
    for (const auto& item : expanded.at("non_default")) {
        const auto found = std::find_if(full.at("settings").begin(), full.at("settings").end(),
            [&](const auto& setting) { return setting.at("name") == item.at("name"); });
        require(found != full.at("settings").end(), "projection invented a setting");
        require(found->value("changed_from_default", false) || found->at("origin") == "SHADER_SETTING_ORIGIN_PRESET" ||
            found->at("origin") == "SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE", "default setting leaked");
    }
    Json terminal{{"workflow_state", "completed"}, {"events", Json::array({1})}};
    require(!vibris::mcp::ResultMapper::agent_projection("vibris_job", terminal, Json::object()).contains("events"),
        "terminal events leaked");
    require(vibris::mcp::ResultMapper::agent_projection("vibris_job", terminal, {{"event_cursor", 0}}).contains("events"),
        "explicit terminal events disappeared");
    std::ifstream catalog_stream(std::filesystem::path(__FILE__).parent_path() / "alpha_piscium_resources.json");
    const auto catalog = Json::parse(catalog_stream);
    const auto compact_catalog = vibris::mcp::ResultMapper::agent_projection("vibris_list_resources", catalog, Json::object());
    std::cout << "Alpha Piscium resource catalog bytes=" << compact_catalog.dump().size() << '\n';
      require(compact_catalog.dump().size() <= 25 * 1024, "Alpha resource catalog exceeds 25 KB");
      const ToolRegistry failed_registry([&](std::string_view, const Json&) -> vibris::mcp::ToolOutcome {
          return vibris::mcp::ToolFailure{"CAPTURE_FAILED", "fixture failure", false,
              {{"effective_settings", full}}};
      });
      const auto failed_result = std::get<Json>(failed_registry.invoke("vibris_get_status",
          {{"worktree_root", "I:\\shader-worktree"}}));
      const auto& failed_settings = failed_result["structuredContent"]["error"]["details"]["effective_settings"];
      require(failed_settings.contains("non_default") && !failed_settings.contains("settings"),
          "failure details bypassed the common projection");
      const auto& compressed = compact_catalog.at("catalog");
      for (std::size_t p = 0; p < compressed.at("passes").size(); ++p) {
          const auto& pass = compressed["passes"][p];
          const auto pass_id = pass.at("pass_id").get<std::string>();
          for (std::size_t i = 0; i < pass.at("programs").size(); ++i) {
              const auto& program = pass["programs"][i];
              const auto& original = catalog["catalog"]["passes"][p]["programs"][i];
              const auto& defaults = compressed.at("program_defaults");
              const auto id = program.value("program_id", pass_id.substr(pass_id.find_last_of('/') + 1));
              const auto stage = program.value("stage", defaults.at("stage").get<std::string>());
              const auto entry = program.contains("entry_file") ? program.at("entry_file").get<std::string>() :
                  defaults.contains("entry_file") ? defaults.at("entry_file").get<std::string>() :
                  "/" + id + "." + compressed.at("program_stage_extensions").at(stage).get<std::string>();
              const auto main = program.contains("main_source") ? program.at("main_source") : defaults.at("main_source");
              require(id == original.at("program_id").get<std::string>() && stage == original.at("stage").get<std::string>() &&
                  entry == original.at("entry_file").get<std::string>() && main == original.at("main_source"),
                  "program projection lost association or source paths");
          }
      }
    require(compressed.at("mapping_sha256") == catalog.at("catalog").at("mapping_sha256"), "catalog SHA was lost");
    for (std::size_t i = 0; i < compressed.at("resources").size(); ++i) {
        const auto& row = compressed["resources"][i];
        const auto& full_resource = catalog["catalog"]["resources"][i];
        const auto& layout = compressed["resource_layouts"].at(row.at("layout_ref").get<std::string>());
        require(row.at("logical_name") == full_resource.at("logical_name") &&
            layout.at("byte_size") == full_resource.at("byte_size"), "resource layout deduplication changed identity or size");
        if (layout.at("kind") != "BUFFER") require(layout.at("width") == full_resource.at("width") &&
            layout.at("height") == full_resource.at("height"), "resource layout lost texture dimensions");
    }
}

void branch_errors_and_prune_are_precise() {
    const ToolRegistry registry([](std::string_view, const Json& args) { return args; });
    Json request{{"worktree_root", "I:\\shader-worktree"}, {"artifact_path", "x"},
        {"query", {{"operation", "metric"}}}};
    auto result = registry.invoke("mcp_vibiris_nsight_analyze", request);
    require(std::holds_alternative<InvocationError>(result) &&
        std::get<InvocationError>(result).message == "arguments.query.name is required", "missing branch field is not precise");
    request["query"]["operation"] = "invalid";
    result = registry.invoke("mcp_vibiris_nsight_analyze", request);
    require(std::get<InvocationError>(result).message.find("summary") != std::string::npos,
        "invalid discriminator omitted valid choices");
    const Json manifests = Json::array({
        {{"manifest_id", "new"}, {"manifest_sha256", "sha-new"}, {"job_id", "job"}, {"recipe", "profile"},
            {"completed_at_unix_ms", 20}, {"total_bytes", 10}},
        {{"manifest_id", "old"}, {"manifest_sha256", "sha-old"}, {"job_id", "job"}, {"recipe", "profile"},
            {"completed_at_unix_ms", 10}, {"total_bytes", 30}},
        {{"manifest_id", "other"}, {"manifest_sha256", "sha-other"}, {"job_id", "other"}, {"recipe", "profile"},
            {"completed_at_unix_ms", 1}, {"total_bytes", 40}}});
    std::size_t deletes = 0;
    const auto manage = [&](const Json& args) -> vibris::mcp::ToolOutcome {
        if (args.at("operation") == "list") return Json{{"manifests", manifests}};
        ++deletes;
        require(args.at("manifest_id") == "old" && args.at("expected_manifest_sha256") == "sha-old",
            "prune selected wrong manifest or omitted SHA");
        return vibris::mcp::ToolFailure{"SHA_CONFLICT", "concurrent update"};
    };
    const Json args{{"job_ids", Json::array({"job"})}, {"keep_latest", 1}};
    const auto pruned = std::get<Json>(vibris::mcp::prune_artifacts({}, args, manage));
    require(deletes == 1 && pruned.at("failures").size() == 1 && pruned.at("freed_bytes") == 0,
        "prune counted a SHA conflict as deletion");
    auto dry = args;
    dry["dry_run"] = true;
    const auto preview = std::get<Json>(vibris::mcp::prune_artifacts({}, dry, manage));
    require(deletes == 1 && preview.at("selected_bytes") == 30, "dry run deleted or miscounted bytes");
}

void bounded_camera_motion_schema_and_restore_policy() {
    const ToolRegistry registry;
    Json arguments{{"worktree_root", "C:/fixture"}, {"preset_id", "quality"},
        {"actions", Json::array({{
            {"type", "camera_motion"}, {"artifact_prefix", "turn"},
            {"keyframes", Json::array({
                {{"frame_offset", 0}, {"yaw_delta_degrees", 0}, {"pitch_delta_degrees", 0}},
                {{"frame_offset", 15}, {"yaw_delta_degrees", 90}, {"pitch_delta_degrees", 0}},
            })},
        }})}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", arguments)),
        "valid camera motion was rejected");
    auto translation = arguments;
    translation["actions"][0]["keyframes"][1]["x_delta"] = 2.0;
    translation["actions"][0]["keyframes"][1]["y_delta"] = 1.0;
    translation["actions"][0]["keyframes"][1]["z_delta"] = -2.0;
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions",translation)),"valid XYZ trajectory rejected");
    for(const char* axis : {"x_delta","y_delta","z_delta"}) {
        auto bad=translation;bad["actions"][0]["keyframes"][1][axis]=17.0;
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions",bad)),"unbounded XYZ accepted");
        bad=translation;bad["actions"][0]["keyframes"][0][axis]=1.0;
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions",bad)),"initial XYZ jump accepted");
    }
    auto too_fast=translation;too_fast["actions"][0]["keyframes"][1]["frame_offset"]=1;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions",too_fast)),"unbounded XYZ speed accepted");
    auto invalid = arguments;
    invalid["actions"][0]["keyframes"][1]["frame_offset"] = 240;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", invalid)),
        "oversized camera motion was accepted");
    invalid = arguments;
    invalid["actions"][0]["keyframes"][1]["frame_offset"] = 0;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", invalid)),
        "duplicate frame offsets were accepted");
    invalid = arguments;
    invalid["restore_state"] = {{"on_success", false}, {"on_error", true}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", invalid)),
        "camera action required global success restoration");
    invalid = arguments;
    invalid["actions"][0]["keyframes"][0]["yaw_delta_degrees"] = 10;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", invalid)),
        "camera action accepted an initial instantaneous jump");
}

} // namespace

void stdio_wait_does_not_block_control() {
    std::mutex mutex;
    std::condition_variable changed;
    int waiting = 0;
    bool released = false;
    const ToolRegistry registry([&](std::string_view name, const Json&) -> vibris::mcp::ToolOutcome {
        std::unique_lock lock(mutex);
        if (name == "vibris_job") {
            ++waiting;
            changed.notify_all();
            if (!changed.wait_for(lock, std::chrono::seconds(2), [&] { return released; })) {
                throw std::runtime_error("control request was blocked by wait");
            }
        } else {
            require(changed.wait_for(lock, std::chrono::seconds(2), [&] { return waiting == 8; }),
                "wait requests did not reach the dispatcher");
            released = true;
            changed.notify_all();
        }
        return Json{{"ok", true}};
    });
    std::ostringstream requests;
    requests << Json{{"jsonrpc", "2.0"}, {"id", 0}, {"method", "tools/list"}}.dump() << '\n';
    for (int id = 1; id <= 9; ++id) requests << Json{{"jsonrpc", "2.0"}, {"id", id}, {"method", "tools/call"},
        {"params", {{"name", "vibris_job"}, {"arguments", {{"worktree_root", "C:/fixture"},
            {"job_id", "job"}, {"operation", "wait"}, {"timeout_ms", 1000}}}}}}.dump() << '\n';
    requests << Json{{"jsonrpc", "2.0"}, {"id", 10}, {"method", "tools/call"},
        {"params", {{"name", "vibris_get_status"}, {"arguments", {{"worktree_root", "C:/fixture"}}}}}}.dump() << '\n';
    std::istringstream input(requests.str());
    std::ostringstream output;
    vibris::mcp::McpStdioServer server(input, output, registry);
    require(server.run() == 0, "stdio server failed");
    std::istringstream responses(output.str());
    std::string line;
    std::unordered_set<int> ids;
    while (std::getline(responses, line)) {
        auto response = Json::parse(line);
        const int id = response.at("id").get<int>();
        if (id == 9) require(response.at("error").at("data").at("code") == "WAIT_CAPACITY",
            "wait capacity was not bounded");
        else require(!response.contains("error"), "stdio dispatch returned an error");
        if (id == 0) require(response.at("result").at("tools").is_array(), "tools/list failed");
        require(ids.insert(id).second, "duplicate response ID");
    }
    require(ids == std::unordered_set<int>({0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), "EOF lost pending wait responses");
}

void stdio_eof_interrupts_observers() {
    std::mutex mutex;
    std::condition_variable changed;
    bool closed = false;
    const ToolRegistry registry([&](std::string_view, const Json&) -> vibris::mcp::ToolOutcome {
        std::unique_lock lock(mutex);
        require(changed.wait_for(lock, std::chrono::seconds(2), [&] { return closed; }),
            "EOF did not interrupt the outstanding observer");
        return Json{{"wait_interrupted", true}};
    });
    std::istringstream input(Json{{"jsonrpc", "2.0"}, {"id", "pending"}, {"method", "tools/call"},
        {"params", {{"name", "vibris_job"}, {"arguments", {{"worktree_root", "C:/fixture"},
            {"job_id", "job"}, {"operation", "wait"}, {"timeout_ms", 240000}}}}}}.dump() + "\n");
    std::ostringstream output;
    vibris::mcp::McpStdioServer server(input, output, registry, [&] {
        std::scoped_lock lock(mutex);
        closed = true;
        changed.notify_all();
    });
    require(server.run() == 0, "EOF observer cleanup failed");
    const auto response = Json::parse(output.str());
    require(response.at("id") == "pending" &&
        response.at("result").at("structuredContent").at("result").at("wait_interrupted") == true,
        "EOF lost the observer response");
}

void large_durable_response_references_complete_file() {
    Json native{{"workflow_state", "completed"}, {"full_result_path", "C:/fixture/result.json"},
        {"result", {{"kind", "profile_matrix"}, {"completed_cases", 14},
            {"cases", Json::array({{{"payload", std::string(9 * 1024 * 1024, 'x')}}})}}}};
    const auto original = native;
    const auto projected = vibris::mcp::ResultMapper::agent_projection("vibris_job", native, Json::object());
    require(projected.dump().size() < 4096, "large durable result escaped response budget");
    require(projected.at("result").at("completed_cases") == 14, "summary lost case count");
    require(projected.at("full_result_path") == "C:/fixture/result.json", "full result reference lost");
    require(native == original, "response projection mutated the full result");
    const Json listing = {{"manifests", Json::array({Json{{"manifest_id", "one"},
        {"manifest_path", "C:/fixture/manifest.json"}, {"manifest_sha256", "hash"},
        {"files", Json::array({Json{{"artifact_id", "a"}}, Json{{"artifact_id", "b"}}})}}})}};
    const auto manifests = vibris::mcp::ResultMapper::agent_projection("vibris_artifacts", listing, {{"operation", "list"}});
    require(manifests.at("manifests")[0].at("file_count") == 2 &&
        !manifests.at("manifests")[0].contains("files") &&
        manifests.at("manifests")[0].at("manifest_path") == "C:/fixture/manifest.json",
        "artifact summary lost the complete manifest reference");
    require(vibris::mcp::ResultMapper::agent_projection("vibris_artifacts", listing, {{"operation", "get"}}) == listing,
        "artifact get lost file details");
}

void matrix_scene_forms_reach_dispatch() {
    int dispatched = 0;
    const ToolRegistry registry([&](std::string_view, const Json& args) { ++dispatched; return args; });
    const auto& definitions = registry.definitions();
    const auto found = std::find_if(definitions.begin(), definitions.end(), [](const Json& d) {
        return d.at("name") == "vibris_run_matrix";
    });
    require(found != definitions.end(), "matrix missing from tools/list");
    const auto& properties = found->at("inputSchema").at("properties");
    require(properties.contains("preset_id") && properties.contains("presets"), "scene forms not discoverable");
    Json request{{"worktree_root", "C:/fixture"},
        {"sources", Json::array({{{"id", "src"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "cfg"}, {"values", Json::object()}}})},
        {"matrix", {{"sources", Json::array({"src"})}, {"configs", Json::array({"cfg"})}}},
        {"actions", Json::array({{{"type", "wait_frames"}, {"frames", 1}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_matrix", request)), "missing scene accepted");
    request["preset_id"] = "scene";
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", request)), "single scene rejected");
    request["presets"] = Json::array({"scene", "second"});
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_matrix", request)), "ambiguous scenes accepted");
    request.erase("preset_id");
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", request)), "multiple scenes rejected");
    request["restore_state"] = {{"on_success", true}, {"on_error", true}};
    require(properties.contains("restore_state") &&
        std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", request)), "matrix explicit restoration was not discoverable or dispatchable");
    require(dispatched == 3, "invalid matrix reached backend");
}

int main() {
    try {
        large_durable_response_references_complete_file();
        stdio_wait_does_not_block_control();
        stdio_eof_interrupts_observers();
        matrix_scene_forms_reach_dispatch();
        bounded_camera_motion_schema_and_restore_policy();
        exact_v2_tool_catalog();
        exact_filters_and_job_control();
        typed_actions_reject_aliases();
        compile_validation_shape_is_strict_and_typed();
        local_nsight_analysis_shape_is_strict_and_typed();
        bounded_single_structured_result();
        agent_projection_preserves_full_results();
        branch_errors_and_prune_are_precise();
        std::cout << "PASS ActionSchemaV2ToolContract\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaV2ToolContract: " << error.what() << '\n';
        return 1;
    }
}