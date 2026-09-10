#include "job_protocol.hpp"
#include "result_mapper.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v2;

constexpr std::uint64_t queue_timeout_ms = 15 * 60'000;
constexpr std::uint64_t execution_timeout_ms = 120'000;

std::string lower_enum_name(std::string value, std::string_view prefix) {
    if (value.starts_with(prefix)) value.erase(0, prefix.size());
    std::ranges::transform(value, value.begin(), [](const unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

proto::ArtifactFormat artifact_format(const std::string_view value) {
    if (value == "png") return proto::ARTIFACT_FORMAT_PNG;
    if (value == "exr") return proto::ARTIFACT_FORMAT_EXR;
    if (value == "bin") return proto::ARTIFACT_FORMAT_BIN;
    if (value == "text") return proto::ARTIFACT_FORMAT_TEXT;
    if (value == "json") return proto::ARTIFACT_FORMAT_JSON;
    if (value == "csv") return proto::ARTIFACT_FORMAT_CSV;
    throw std::invalid_argument("unsupported artifact format");
}

proto::TextureView texture_view(const std::string_view value) {
    if (value == "current") return proto::TEXTURE_VIEW_CURRENT;
    if (value == "alternate") return proto::TEXTURE_VIEW_ALTERNATE;
    if (value == "main") return proto::TEXTURE_VIEW_MAIN;
    if (value == "alt") return proto::TEXTURE_VIEW_ALT;
    throw std::invalid_argument("unsupported texture view");
}

proto::ResourceKind resource_kind(const std::string_view value) {
    if (value == "final_framebuffer") return proto::RESOURCE_KIND_FINAL_FRAMEBUFFER;
    if (value == "texture") return proto::RESOURCE_KIND_TEXTURE;
    if (value == "buffer") return proto::RESOURCE_KIND_BUFFER;
    if (value == "patched_shaders") return proto::RESOURCE_KIND_PATCHED_SHADERS;
    throw std::invalid_argument("unsupported resource kind");
}

std::string config_value(const Json& value) {
    return value.is_string() ? value.get<std::string>() : value.dump();
}

proto::ShaderConfig shader_config(const Json* values, const bool preserve) {
    proto::ShaderConfig result;
    result.set_preserve_current(preserve);
    if (values != nullptr) {
        for (const auto& [key, value] : values->items()) {
            (*result.mutable_values())[key] = config_value(value);
        }
    }
    return result;
}

using SourceMap = std::unordered_map<std::string, const proto::PreparedSourceRef*>;
using ConfigMap = std::unordered_map<std::string, proto::ShaderConfig>;

SourceMap source_map(const Json& arguments, const std::span<const proto::PreparedSourceRef> sources) {
    SourceMap result;
    if (const auto declared = arguments.find("sources"); declared != arguments.end()) {
        const bool has_baseline = arguments.value("recipe", std::string{}) == "compile_validate" &&
            arguments.contains("baseline");
        if (!declared->is_array() || declared->size() + (has_baseline ? 1U : 0U) != sources.size()) {
            throw std::invalid_argument("prepared source count does not match named declarations");
        }
        for (std::size_t index = 0; index < sources.size(); ++index) {
            if (index == declared->size()) break;
            result.emplace((*declared)[index].at("id").get<std::string>(), &sources[index]);
        }
        if (has_baseline) result.emplace("baseline", &sources.back());
        return result;
    }
    if (arguments.value("recipe", std::string{}) == "compile_validate" && arguments.contains("baseline")) {
        if (sources.size() != 2) throw std::invalid_argument("compile validation source count is invalid");
        result.emplace("source", &sources[0]);
        result.emplace("baseline", &sources[1]);
        return result;
    }
    if (sources.size() == 1) result.emplace("source", &sources.front());
    if (sources.size() == 2) {
        result.emplace("a", &sources[0]);
        result.emplace("b", &sources[1]);
        result.emplace("baseline", &sources[0]);
        result.emplace("candidate", &sources[1]);
    }
    return result;
}

ConfigMap config_map(const Json& arguments) {
    ConfigMap result;
    if (const auto declared = arguments.find("configs"); declared != arguments.end()) {
        for (const auto& item : *declared) {
            const auto values = item.find("values");
            result.emplace(item.at("id").get<std::string>(),
                shader_config(values == item.end() ? nullptr : &*values, false));
        }
    } else if (const auto values = arguments.find("config"); values != arguments.end()) {
        result.emplace("config", shader_config(&*values, false));
    } else {
        result.emplace("config", shader_config(nullptr, false));
    }
    if (const auto values = arguments.find("baseline_config"); values != arguments.end()) {
        result.emplace("baseline_config", shader_config(&*values, false));
    }
    return result;
}

const proto::PreparedSourceRef& require_source(const SourceMap& sources, const std::string& id) {
    const auto source = sources.find(id);
    if (source == sources.end()) throw std::invalid_argument("action references an unknown source");
    return *source->second;
}

const proto::ShaderConfig& require_config(const ConfigMap& configs, const std::string& id) {
    const auto config = configs.find(id);
    if (config == configs.end()) throw std::invalid_argument("action references an unknown config");
    return config->second;
}

void copy_filter(const Json& input, proto::ResourceFilter& filter) {
    for (const auto& kind : input.value("kinds", Json::array())) {
        filter.add_kinds(resource_kind(kind.get<std::string>()));
    }
    if (input.contains("logical_name")) filter.set_logical_name(input.at("logical_name").get<std::string>());
    if (input.contains("pass_id")) filter.set_pass_id(input.at("pass_id").get<std::string>());
}

void copy_texture_selector(const Json& input, proto::ResourceSelector& selector) {
    selector.set_logical_name(input.at("logical_name").get<std::string>());
    selector.set_view(texture_view(input.at("view").get<std::string>()));
    selector.set_mip_level(input.value("mip_level", std::uint32_t{0}));
    selector.set_layer(input.value("layer", std::uint32_t{0}));
}

void append_action(const Json& input, const SourceMap& sources, const ConfigMap& configs,
    proto::ActionSequence& sequence, const std::string_view artifact_prefix = {}) {
    auto* action = sequence.add_actions();
    const auto type = input.at("type").get<std::string>();
    if (type == "reset_temporal_state") {
        action->mutable_reset_temporal_state();
    } else if (type == "wait_frames") {
        action->mutable_wait_frames()->set_frame_count(input.at("frames").get<std::uint32_t>());
    } else if (type == "take_screenshot") {
        auto* value = action->mutable_take_screenshot();
        value->set_artifact_name(std::string(artifact_prefix) + input.value("artifact_name", "screenshot"));
        value->set_format(artifact_format(input.value("format", "png")));
        value->set_after_frames(input.value("after_frames", std::uint32_t{0}));
    } else if (type == "dump_texture") {
        auto* value = action->mutable_dump_texture();
        copy_texture_selector(input.at("resource"), *value->mutable_resource());
        value->set_format(artifact_format(input.at("format").get<std::string>()));
        value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
    } else if (type == "dump_buffer") {
        auto* value = action->mutable_dump_buffer();
        value->mutable_resource()->set_logical_name(input.at("resource").at("logical_name").get<std::string>());
        value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
    } else if (type == "dump_texture_after_pass") {
        auto* value = action->mutable_dump_texture_after_pass();
        value->set_pass_id(input.at("pass_id").get<std::string>());
        copy_texture_selector(input.at("resource"), *value->mutable_resource());
        value->set_format(artifact_format(input.at("format").get<std::string>()));
        value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
    } else if (type == "dump_buffer_after_pass") {
        auto* value = action->mutable_dump_buffer_after_pass();
        value->set_pass_id(input.at("pass_id").get<std::string>());
        value->mutable_resource()->set_logical_name(
            input.at("resource").at("logical_name").get<std::string>());
        value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
    } else if (type == "get_capture_status") {
        action->mutable_get_capture_status();
    } else if (type == "load_shader") {
        const auto source_id = input.at("source_id").get<std::string>();
        const auto config_id = input.at("config_id").get<std::string>();
        auto* value = action->mutable_load_shader();
        value->set_source_uuid(require_source(sources, source_id).source_uuid());
        value->set_source_id(source_id);
        value->set_config_id(config_id);
        value->mutable_config()->CopyFrom(require_config(configs, config_id));
    } else if (type == "nsight_gpu_trace") {
        auto* value = action->mutable_nsight_gpu_trace();
        const auto& capture = input.at("capture");
        if (capture.at("mode") == "single") {
            value->set_pass_id(capture.at("pass_id").get<std::string>());
        } else {
            value->set_capture_type(capture.at("capture_type").get<std::string>());
        }
        value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
        value->set_replay_backend(input.value("replay_backend", "gl"));
        value->set_architecture(input.at("architecture").get<std::string>());
        value->set_metric_set_name(input.value("metric_set_name", "Throughput Metrics"));
        value->set_replay_frames(input.value("replay_frames", std::uint32_t{300}));
        value->set_start_after_ms(input.value("start_after_ms", std::uint32_t{1'000}));
        value->set_max_duration_ms(input.value("max_duration_ms", std::uint32_t{1'000}));
        value->set_timeout_seconds(input.value("timeout_seconds", std::uint32_t{300}));
        value->set_time_every_action(input.value("time_every_action", true));
        value->set_gpu_clocks(input.value("gpu_clocks", "base"));
    } else if (type == "inspect_shader") {
        action->mutable_inspect_shader();
    } else if (type == "get_gpu_metrics") {
        auto* value = action->mutable_get_gpu_metrics();
        value->set_frames(input.at("frames").get<std::uint32_t>());
        for (const auto& metric : input.value("metric_ids", Json::array())) {
            value->add_metric_ids(metric.get<std::string>());
        }
    } else if (type == "list_resources") {
        copy_filter(input, *action->mutable_list_resources()->mutable_filter());
    } else if (type == "get_patched_shaders") {
        action->mutable_get_patched_shaders()->set_artifact_name(
            std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
    } else {
        throw std::invalid_argument("unsupported v2 action type");
    }
}

void append_actions(const Json& inputs, const SourceMap& sources, const ConfigMap& configs,
    proto::ActionSequence& sequence, const std::string_view artifact_prefix = {}) {
    for (const auto& input : inputs) append_action(input, sources, configs, sequence, artifact_prefix);
}

void append_load(proto::ActionSequence& sequence, const proto::PreparedSourceRef& source,
    std::string source_id, std::string config_id, const proto::ShaderConfig& config) {
    auto* action = sequence.add_actions();
    action->set_prelude(true);
    auto* value = action->mutable_load_shader();
    value->set_source_uuid(source.source_uuid());
    value->set_source_id(std::move(source_id));
    value->set_config_id(std::move(config_id));
    value->mutable_config()->CopyFrom(config);
}

void append_reset(proto::ActionSequence& sequence) {
    sequence.add_actions()->mutable_reset_temporal_state();
}

void append_wait(proto::ActionSequence& sequence, const std::uint32_t frames) {
    if (frames != 0) sequence.add_actions()->mutable_wait_frames()->set_frame_count(frames);
}

std::uint32_t append_capture(proto::ActionSequence& sequence, const Json& capture, std::string artifact_name) {
    const auto action_index = static_cast<std::uint32_t>(sequence.actions_size());
    auto* action = sequence.add_actions();
    const auto type = capture.at("type").get<std::string>();
    if (type == "screenshot") {
        auto* value = action->mutable_take_screenshot();
        value->set_artifact_name(std::move(artifact_name));
        value->set_format(artifact_format(capture.value("format", "png")));
    } else if (type == "texture") {
        auto* value = action->mutable_dump_texture();
        value->mutable_resource()->set_logical_name(capture.at("name").get<std::string>());
        value->mutable_resource()->set_view(proto::TEXTURE_VIEW_CURRENT);
        value->set_format(artifact_format(capture.value("format", "png")));
        value->set_artifact_name(std::move(artifact_name));
    } else if (type == "buffer") {
        auto* value = action->mutable_dump_buffer();
        value->mutable_resource()->set_logical_name(capture.at("name").get<std::string>());
        value->set_artifact_name(std::move(artifact_name));
    } else {
        throw std::invalid_argument("unsupported capture type");
    }
    return action_index;
}

void copy_visual_thresholds(const Json& input, proto::VisualThresholds& output) {
    output.set_pixel_error_threshold(input.value("pixel_error_threshold", 0.0));
    if (input.contains("max_mean_absolute_error")) {
        output.set_max_mean_absolute_error(input.at("max_mean_absolute_error").get<double>());
    }
    if (input.contains("max_root_mean_square_error")) {
        output.set_max_root_mean_square_error(input.at("max_root_mean_square_error").get<double>());
    }
    if (input.contains("max_p95_absolute_error")) {
        output.set_max_p95_absolute_error(input.at("max_p95_absolute_error").get<double>());
    }
    if (input.contains("max_absolute_error")) {
        output.set_max_absolute_error(input.at("max_absolute_error").get<double>());
    }
    if (input.contains("max_threshold_pixel_ratio")) {
        output.set_max_threshold_pixel_ratio(input.at("max_threshold_pixel_ratio").get<double>());
    }
    if (input.contains("min_ssim")) output.set_min_ssim(input.at("min_ssim").get<double>());
}

void configure_result_artifacts(const Json& arguments, proto::ResultArtifactOptions& options) {
    options.set_write_json(true);
    options.set_write_csv(arguments.value("result_csv", false));
    for (const auto& unit : arguments.value("converted_units", Json::array())) {
        options.add_converted_units(unit.get<std::string>());
    }
}

void build_matrix(const Json& arguments, const SourceMap& sources, const ConfigMap& configs,
    const Json& template_actions, proto::MatrixRequest& matrix) {
    matrix.set_max_retries(arguments.value("max_retries", std::uint32_t{0}));
    for (const auto& source_value : arguments.at("matrix").at("sources")) {
        const auto source_id = source_value.get<std::string>();
        static_cast<void>(require_source(sources, source_id));
        for (const auto& config_value : arguments.at("matrix").at("configs")) {
            const auto config_id = config_value.get<std::string>();
            auto* value = matrix.add_cases();
            value->set_case_id(source_id + "--" + config_id);
            value->set_source_id(source_id);
            value->set_config_id(config_id);
            value->mutable_config()->CopyFrom(require_config(configs, config_id));
            append_load(*value->mutable_actions(), require_source(sources, source_id),
                source_id, config_id, value->config());
            append_actions(template_actions, sources, configs, *value->mutable_actions(), value->case_id() + "--");
        }
    }
}

void build_recipe(const Json& arguments, const JobContext& config, const SourceMap& sources,
    const ConfigMap& configs, proto::JobSpec& job) {
    const auto recipe = arguments.at("recipe").get<std::string>();
    if (recipe == "recover_runtime") {
        job.mutable_recover_runtime();
        return;
    }
    if (recipe == "profile_matrix") {
        Json actions = Json::array();
        const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
        if (warmup != 0) actions.push_back({{"type", "wait_frames"}, {"frames", warmup}});
        actions.push_back({{"type", "get_gpu_metrics"}, {"frames", arguments.at("frames")},
            {"metric_ids", arguments.value("metric_filter", Json::array())}});
        build_matrix(arguments, sources, configs, actions, *job.mutable_matrix());
        return;
    }
    if (recipe == "compile_validate") {
        auto* validation = job.mutable_compile_validation();
        const auto append_case = [&](const std::string& case_id, const std::string& source_id,
                                     const std::string& config_id, const proto::ShaderConfig& shader) {
            auto* value = validation->add_cases();
            value->set_case_id(case_id);
            value->set_source_id(require_source(sources, source_id).source_uuid());
            value->set_config_id(config_id);
            value->mutable_config()->CopyFrom(shader);
        };
        if (arguments.contains("matrix")) {
            for (const auto& source_value : arguments.at("matrix").at("sources")) {
                const auto source_id = source_value.get<std::string>();
                for (const auto& config_value : arguments.at("matrix").at("configs")) {
                    const auto config_id = config_value.get<std::string>();
                    append_case(source_id + "--" + config_id, source_id, config_id,
                        require_config(configs, config_id));
                }
            }
        } else {
            append_case(arguments.value("__vibris_case_id", std::string("source--config")), "source",
                arguments.value("__vibris_config_id", std::string("config")), require_config(configs, "config"));
        }
        if (arguments.contains("baseline")) {
            auto* baseline = validation->mutable_baseline();
            baseline->set_case_id("baseline");
            baseline->set_source_id(require_source(sources, "baseline").source_uuid());
            baseline->set_config_id(arguments.contains("baseline_config") ? "baseline_config" : "config");
            baseline->mutable_config()->CopyFrom(arguments.contains("baseline_config")
                ? require_config(configs, "baseline_config")
                : require_config(configs, arguments.contains("matrix")
                    ? arguments.at("matrix").at("configs").front().get<std::string>() : "config"));
        }
        return;
    }
    if (recipe == "benchmark_ab") {
        auto* benchmark = job.mutable_benchmark();
        benchmark->set_baseline_source_id("baseline");
        benchmark->set_candidate_source_id("candidate");
        benchmark->mutable_baseline_config()->CopyFrom(require_config(configs, "config"));
        benchmark->mutable_candidate_config()->CopyFrom(require_config(configs, "config"));
        benchmark->set_warmup_frames(arguments.value("warmup_frames", config.default_warmup_frames));
        benchmark->set_sample_frames(arguments.at("frames").get<std::uint32_t>());
        benchmark->set_repetitions(arguments.value("rounds", std::uint32_t{2}));
        const auto order = arguments.value("order", std::string("abba"));
        benchmark->set_order(order == "abab" ? proto::BENCHMARK_ORDER_ABAB :
            order == "randomized" ? proto::BENCHMARK_ORDER_RANDOMIZED : proto::BENCHMARK_ORDER_ABBA);
        benchmark->set_run_same_source_control(true);
        for (const auto& input : arguments.at("metrics")) {
            auto* metric = benchmark->add_metrics();
            metric->set_metric_id(input.at("metric_id").get<std::string>());
            const auto role = input.at("role").get<std::string>();
            metric->set_role(role == "sibling" ? proto::BENCHMARK_METRIC_ROLE_SIBLING :
                role == "sentinel" ? proto::BENCHMARK_METRIC_ROLE_SENTINEL :
                proto::BENCHMARK_METRIC_ROLE_TARGET);
            if (input.contains("max_regression_ratio")) {
                metric->set_max_regression_ratio(input.at("max_regression_ratio").get<double>());
            }
        }
        return;
    }

    auto* sequence = job.mutable_action_sequence();
    if (recipe == "profile" || recipe == "load_and_screenshot" || recipe == "capture_debug_bundle") {
        const auto& source = require_source(sources, "source");
        const auto& shader = require_config(configs, "config");
        append_load(*sequence, source, "source", "config", shader);
        if (arguments.value("__vibris_compile_gate", false)) sequence->add_actions()->mutable_inspect_shader();
        append_wait(*sequence, arguments.value("warmup_frames", config.default_warmup_frames));
        if (recipe == "profile") {
            auto* metrics = sequence->add_actions()->mutable_get_gpu_metrics();
            metrics->set_frames(arguments.at("frames").get<std::uint32_t>());
            for (const auto& id : arguments.value("metric_filter", Json::array())) {
                metrics->add_metric_ids(id.get<std::string>());
            }
        } else if (recipe == "load_and_screenshot" || arguments.value("screenshot", false)) {
            auto* capture = sequence->add_actions()->mutable_take_screenshot();
            capture->set_artifact_name("screenshot");
            capture->set_format(proto::ARTIFACT_FORMAT_PNG);
        }
        if (recipe == "capture_debug_bundle") {
            for (const auto& texture : arguments.value("textures", Json::array())) {
                auto* dump = sequence->add_actions()->mutable_dump_texture();
                dump->mutable_resource()->set_logical_name(texture.get<std::string>());
                dump->mutable_resource()->set_view(proto::TEXTURE_VIEW_CURRENT);
                dump->set_format(proto::ARTIFACT_FORMAT_BIN);
                dump->set_artifact_name(texture.get<std::string>());
            }
            for (const auto& buffer : arguments.value("buffers", Json::array())) {
                auto* dump = sequence->add_actions()->mutable_dump_buffer();
                dump->mutable_resource()->set_logical_name(buffer.get<std::string>());
                dump->set_artifact_name(buffer.get<std::string>());
            }
        }
        return;
    }
    if (recipe == "ab_compare") {
        const auto shader = require_config(configs, "config");
        append_load(*sequence, require_source(sources, "a"), "a", "config", shader);
        append_reset(*sequence);
        append_wait(*sequence, arguments.value("warmup_frames", config.default_warmup_frames));
        std::vector<std::uint32_t> baseline_captures;
        std::size_t capture_index = 0;
        for (const auto& capture : arguments.at("captures")) {
            baseline_captures.push_back(
                append_capture(*sequence, capture, "a-" + std::to_string(capture_index++)));
        }
        append_load(*sequence, require_source(sources, "b"), "b", "config", shader);
        append_reset(*sequence);
        append_wait(*sequence, arguments.value("warmup_frames", config.default_warmup_frames));
        std::vector<std::uint32_t> candidate_captures;
        capture_index = 0;
        for (const auto& capture : arguments.at("captures")) {
            candidate_captures.push_back(
                append_capture(*sequence, capture, "b-" + std::to_string(capture_index++)));
        }
        for (std::size_t index = 0; index < baseline_captures.size(); ++index) {
            auto* compare = sequence->add_actions()->mutable_compare_captures();
            compare->set_baseline_action_index(baseline_captures[index]);
            compare->set_candidate_action_index(candidate_captures[index]);
            compare->set_baseline_label(arguments.at("a").at("label").get<std::string>());
            compare->set_candidate_label(arguments.at("b").at("label").get<std::string>());
            if (const auto thresholds = arguments.find("visual_thresholds"); thresholds != arguments.end()) {
                copy_visual_thresholds(*thresholds, *compare->mutable_thresholds());
            }
        }
        return;
    }
    throw std::invalid_argument("unsupported recipe");
}

std::uint64_t rendered_frames(const proto::ActionSequence& sequence) {
    std::uint64_t result = 0;
    for (const auto& action : sequence.actions()) {
        if (action.has_wait_frames()) result += action.wait_frames().frame_count();
        else if (action.has_get_gpu_metrics()) result += action.get_gpu_metrics().frames();
        else if (action.has_take_screenshot()) result += action.take_screenshot().after_frames();
    }
    return result;
}

std::uint64_t nsight_timeout_ms(const proto::ActionSequence& sequence) {
    std::uint64_t result = 0;
    for (const auto& action : sequence.actions()) {
        if (!action.has_nsight_gpu_trace()) continue;
        const auto milliseconds = static_cast<std::uint64_t>(action.nsight_gpu_trace().timeout_seconds()) * 1'000;
        result = result > std::numeric_limits<std::uint64_t>::max() - milliseconds
            ? std::numeric_limits<std::uint64_t>::max()
            : result + milliseconds;
    }
    return result;
}

void scale_timeouts(proto::JobSpec& job) {
    std::uint64_t frames = job.has_action_sequence() ? rendered_frames(job.action_sequence()) : 0;
    std::uint64_t nsight = job.has_action_sequence() ? nsight_timeout_ms(job.action_sequence()) : 0;
    if (job.has_matrix()) {
        for (const auto& value : job.matrix().cases()) {
            frames += rendered_frames(value.actions());
            const auto case_nsight = nsight_timeout_ms(value.actions());
            nsight = nsight > std::numeric_limits<std::uint64_t>::max() - case_nsight
                ? std::numeric_limits<std::uint64_t>::max()
                : nsight + case_nsight;
        }
    }
    constexpr std::uint64_t setup_ms = 60'000;
    constexpr std::uint64_t ms_per_frame = 1'000;
    const auto measured = frames > (std::numeric_limits<std::uint64_t>::max() - setup_ms) / ms_per_frame
        ? std::numeric_limits<std::uint64_t>::max()
        : setup_ms + frames * ms_per_frame;
    const auto nsight_measured = nsight > std::numeric_limits<std::uint64_t>::max() - setup_ms
        ? std::numeric_limits<std::uint64_t>::max()
        : setup_ms + nsight;
    const auto execution = std::max({execution_timeout_ms, measured, nsight_measured});
    job.mutable_timeouts()->set_queue_timeout_ms(queue_timeout_ms);
    job.mutable_timeouts()->set_execution_timeout_ms(execution);
    job.mutable_timeouts()->set_total_timeout_ms(
        execution > std::numeric_limits<std::uint64_t>::max() - queue_timeout_ms
            ? std::numeric_limits<std::uint64_t>::max()
            : execution + queue_timeout_ms);
}

} // namespace

proto::ClientMessage JobProtocol::request(const std::string_view tool_name, const Json& arguments,
    const JobContext& config, const proto::SceneContext& context,
    const std::span<const proto::PreparedSourceRef> sources, std::string request_id) {
    if (request_id.empty() || config.workspace_id.empty()) throw std::invalid_argument("job identity is missing");
    proto::ClientMessage message;
    message.mutable_protocol_version()->set_major(3);
    message.set_message_id("job-" + request_id);
    message.set_request_id(request_id);
    message.set_workspace_id(config.workspace_id);

    auto* job = message.mutable_submit_job()->mutable_job();
    job->set_job_id(request_id);
    job->set_preset_id(arguments.value("preset_id", std::string{}));
    job->set_scheduling_group_id(arguments.value("__vibris_workflow_id", std::string{}));
    if (const auto preset = arguments.find("__vibris_preset");
        preset != arguments.end() && preset->is_object()) {
        job->set_preset_sha256(preset->value("preset_sha256", std::string{}));
    }
    job->mutable_context()->CopyFrom(context);
    job->mutable_context()->set_fov(config.fov);
    const auto restore = arguments.find("restore_state");
    job->mutable_restore_state()->set_on_success(
        restore == arguments.end() || restore->value("on_success", true));
    job->mutable_restore_state()->set_on_error(
        restore == arguments.end() || restore->value("on_error", true));
    configure_result_artifacts(arguments, *job->mutable_result_artifacts());
    for (const auto& source : sources) job->add_sources()->CopyFrom(source);

    const auto sources_by_id = source_map(arguments, sources);
    const auto configs_by_id = config_map(arguments);
    if (tool_name == "vibris_run_actions") {
        append_actions(arguments.at("actions"), sources_by_id, configs_by_id, *job->mutable_action_sequence());
    } else if (tool_name == "vibris_run_matrix") {
        build_matrix(arguments, sources_by_id, configs_by_id, arguments.at("actions"), *job->mutable_matrix());
    } else if (tool_name == "vibris_run_recipe") {
        build_recipe(arguments, config, sources_by_id, configs_by_id, *job);
    } else {
        throw std::invalid_argument("unsupported job tool");
    }
    scale_timeouts(*job);
    return message;
}

bool JobProtocol::is_terminal(const proto::ServerMessage& message) noexcept {
    return message.has_job_completed() || message.has_job_failed();
}

ToolOutcome JobProtocol::terminal(const proto::ServerMessage& message) {
    if (message.has_job_completed()) {
        return Json{{"success", true}, {"job_id", message.job_completed().job_id()},
            {"request_id", message.job_completed().request_id()},
            {"result", ResultMapper::message(message.job_completed().result())}};
    }
    if (message.has_job_failed()) {
        const auto& failed = message.job_failed();
        const auto mapped = ResultMapper::message(failed);
        Json details{{"job_id", failed.job_id()}, {"request_id", failed.request_id()},
            {"artifacts", mapped.value("artifacts", Json::array())},
            {"action_receipts", mapped.value("action_receipts", Json::array())},
            {"prelude_receipts", mapped.value("prelude_receipts", Json::array())}};
        if (mapped.contains("restoration")) details["restoration"] = mapped.at("restoration");
        for (const auto& [key, value] : failed.error().details()) details[key] = value;
        return ToolFailure{
            lower_enum_name(proto::ErrorCode_Name(failed.error().code()), "ERROR_CODE_"),
            failed.error().message(), failed.error().retryable(), std::move(details)};
    }
    throw std::invalid_argument("server message is not terminal");
}

} // namespace vibris::mcp
