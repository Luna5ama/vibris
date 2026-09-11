#include "job_protocol.hpp"
#include "pending_request_registry.hpp"

#include <google/protobuf/util/json_util.h>

#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace proto = ::vibris::control::v2;
using vibris::mcp::JobContext;
using vibris::mcp::JobProtocol;
using vibris::mcp::Json;
using vibris::mcp::PendingRequestRegistry;
using vibris::mcp::ToolFailure;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";
constexpr std::string_view request_id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

void require(const bool condition, std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

JobContext config() {
	return {.workspace_id = std::string(workspace_id), .save_id = "shader-test-world",
		.dimension_id = "minecraft:overworld", .time_preset_id = "noon",
		.camera_preset_id = "spawn", .fov = 70.0, .default_warmup_frames = 8};
}

proto::SceneContext scene() {
	proto::SceneContext result;
	result.set_save_id("shader-test-world");
	result.set_dimension_id("minecraft:overworld");
	result.set_time_preset_id("noon");
	result.set_camera_preset_id("spawn");
	result.set_fov(70.0);
	return result;
}

proto::PreparedSourceRef source() {
	proto::PreparedSourceRef result;
	result.set_source_uuid("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");
	result.set_vcs_checkout_state(proto::VCS_CHECKOUT_STATE_ATTACHED);
	result.set_branch("main");
	result.set_requested_revision("workspace");
	result.set_resolved_revision("0123456789abcdef");
	result.set_file_count(1);
	result.set_total_bytes(64);
	result.mutable_origin()->mutable_workspace()->set_display_name("fixture");
	return result;
}

proto::PreparedSourceRef source(std::string uuid) {
	auto result = source();
	result.set_source_uuid(std::move(uuid));
	return result;
}

void strict_v2_request_contains_typed_texture_and_buffer_actions() {
	const Json arguments{{"recipe", "capture_debug_bundle"}, {"source", {{"kind", "workspace"}}},
		{"textures", Json::array({"colortex0"})}, {"buffers", Json::array({"scene_ssbo"})},
		{"preset_id", "quality"}};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	require(request.protocol_version().major() == 3 && request.request_id() == request_id &&
		request.workspace_id() == workspace_id && request.has_submit_job(),
		"JobProtocol did not emit a strict v2 submit envelope");
	const auto& job = request.submit_job().job();
	require(job.job_id() == request_id && job.has_action_sequence() &&
		!job.restore_state().on_success() && job.restore_state().on_error(),
		"strict v2 job identity or restoration policy is missing");
	require(job.action_sequence().actions(0).has_load_shader() &&
		job.action_sequence().actions(0).prelude(),
		"recipe-generated shader load was exposed as an ordinary input action");
	bool texture = false;
	bool buffer = false;
	for (const auto& action : job.action_sequence().actions()) {
		if (action.has_dump_texture()) {
			texture = action.dump_texture().resource().logical_name() == "colortex0" &&
				action.dump_texture().format() == proto::ARTIFACT_FORMAT_BIN;
		}
		if (action.has_dump_buffer()) {
			buffer = action.dump_buffer().resource().logical_name() == "scene_ssbo";
		}
	}
	require(texture && buffer, "typed strict-v2 texture/buffer capture actions were not encoded");
}

void ordinary_actions_preserve_the_final_runtime_state_by_default() {
	const Json arguments{{"actions", Json::array({{{"type", "reset_temporal_state"}}})}};
	const auto request = JobProtocol::request(
		"vibris_run_actions", arguments, config(), scene(), {}, std::string(request_id));
	const auto& restore = request.submit_job().job().restore_state();
	require(!restore.on_success() && restore.on_error(),
		"ordinary jobs did not preserve the final runtime state by default");
}

void explicit_load_action_uses_declared_v2_ids() {
	const Json arguments{
		{"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "quality"}, {"values", {{"QUALITY", 2}}}}})},
		{"actions", Json::array({{{"type", "load_shader"},
			{"source_id", "candidate"}, {"config_id", "quality"}}})},
	};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_actions", arguments, config(), scene(), sources, std::string(request_id));
	const auto& actions = request.submit_job().job().action_sequence().actions();
	require(actions.size() == 1 && actions[0].has_load_shader(),
		"typed explicit load action was not encoded");
	const auto& load = actions[0].load_shader();
	require(!actions[0].prelude() && load.source_id() == "candidate" && load.config_id() == "quality" &&
		load.source_uuid() == sources[0].source_uuid() && !load.config().preserve_current() &&
		load.config().values().at("QUALITY") == "2",
		"explicit load action lost its declared source_id/config_id mapping");
}

void missing_config_values_use_pack_defaults() {
	const Json arguments{
		{"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "defaults"}}})},
		{"actions", Json::array({{{"type", "load_shader"},
			{"source_id", "candidate"}, {"config_id", "defaults"}}})},
	};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_actions", arguments, config(), scene(), sources, std::string(request_id));
	const auto& shader = request.submit_job().job().action_sequence().actions(0).load_shader().config();
	require(!shader.preserve_current() && shader.values().empty(),
		"a config without values did not select shaderpack defaults");
}

void omitted_recipe_config_uses_pack_defaults() {
	const Json arguments{{"recipe", "load_and_screenshot"},
		{"source", {{"kind", "workspace"}}}, {"warmup_frames", 0}, {"preset_id", "quality"}};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& shader = request.submit_job().job().action_sequence().actions(0).load_shader().config();
	require(!shader.preserve_current() && shader.values().empty(),
		"an omitted recipe config did not select shaderpack defaults");
}

void load_and_screenshot_emits_one_post_load_capture_sequence() {
	const Json arguments{{"recipe", "load_and_screenshot"}, {"source", {{"kind", "workspace"}}},
		{"config", {{"QUALITY", 2}}}, {"warmup_frames", 3}, {"preset_id", "quality"}};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& actions = request.submit_job().job().action_sequence().actions();
	require(actions.size() == 3 && actions[0].prelude() && actions[0].has_load_shader() &&
		actions[1].has_wait_frames() && actions[1].wait_frames().frame_count() == 3 &&
		actions[2].has_take_screenshot(),
		"load_and_screenshot did not emit one load prelude followed by wait and capture");
}

void ab_compare_resets_each_source_before_equal_warmup_and_capture() {
	const Json arguments{{"recipe", "ab_compare"}, {"preset_id", "night-gi-1-720p"},
		{"a", {{"label", "baseline"}, {"source", {{"kind", "workspace"}}}}},
		{"b", {{"label", "candidate"}, {"source", {{"kind", "commit"}, {"revision", "HEAD"}}}}},
		{"config", {{"QUALITY", 2}}}, {"warmup_frames", 6},
		{"captures", Json::array({{{"type", "screenshot"}, {"format", "png"}}})},
		{"visual_thresholds", {{"pixel_error_threshold", 0.01}, {"max_threshold_pixel_ratio", 0.001},
			{"min_ssim", 0.995}}}};
	const std::vector sources{
		source("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
		source("cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa"),
	};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& actions = request.submit_job().job().action_sequence().actions();
	require(actions.size() == 9 &&
		actions[0].prelude() && actions[0].has_load_shader() && actions[0].load_shader().source_id() == "a" &&
		!actions[1].prelude() && actions[1].has_reset_temporal_state() &&
		actions[2].has_wait_frames() && actions[2].wait_frames().frame_count() == 6 &&
		actions[3].has_take_screenshot() && actions[3].take_screenshot().artifact_name() == "a-0" &&
		actions[4].prelude() && actions[4].has_load_shader() && actions[4].load_shader().source_id() == "b" &&
		!actions[5].prelude() && actions[5].has_reset_temporal_state() &&
		actions[6].has_wait_frames() && actions[6].wait_frames().frame_count() == 6 &&
		actions[7].has_take_screenshot() && actions[7].take_screenshot().artifact_name() == "b-0" &&
		actions[8].has_compare_captures(),
		"ab_compare did not reset both sources immediately before equal warmup and capture phases");
	const auto& compare = actions[8].compare_captures();
	require(compare.baseline_action_index() == 3 && compare.candidate_action_index() == 7 &&
		compare.baseline_label() == "baseline" && compare.candidate_label() == "candidate" &&
		compare.thresholds().pixel_error_threshold() == 0.01 &&
		compare.thresholds().max_threshold_pixel_ratio() == 0.001 &&
		compare.thresholds().min_ssim() == 0.995,
		"ab_compare changed capture references, labels, or fail-closed visual thresholds");
}

void after_pass_actions_preserve_exact_pass_and_resource_selectors() {
	const Json arguments{{"preset_id", "quality"}, {"actions", Json::array({
		{{"type", "dump_texture_after_pass"}, {"pass_id", "composite/composite21"},
		 {"resource", {{"logical_name", "colortex0"}, {"view", "alternate"},
			 {"mip_level", 2}, {"layer", 3}}}, {"format", "png"}, {"artifact_name", "texture-after"}},
		{{"type", "dump_buffer_after_pass"}, {"pass_id", "prepare/prepare3"},
		 {"resource", {{"logical_name", "scene_ssbo"}}}, {"artifact_name", "buffer-after"}},
	})}};
	const auto request = JobProtocol::request(
		"vibris_run_actions", arguments, config(), scene(), {}, std::string(request_id));
	const auto& actions = request.submit_job().job().action_sequence().actions();
	require(actions.size() == 2, "after-pass actions were not preserved one-for-one");
	const auto& texture = actions[0].dump_texture_after_pass();
	require(texture.pass_id() == "composite/composite21" &&
		texture.resource().logical_name() == "colortex0" &&
		texture.resource().view() == proto::TEXTURE_VIEW_ALTERNATE &&
		texture.resource().mip_level() == 2 && texture.resource().layer() == 3 &&
		texture.format() == proto::ARTIFACT_FORMAT_PNG,
		"texture-after-pass selector was changed during protocol conversion");
	const auto& buffer = actions[1].dump_buffer_after_pass();
	require(buffer.pass_id() == "prepare/prepare3" &&
		buffer.resource().logical_name() == "scene_ssbo" &&
		buffer.resource().view() == proto::TEXTURE_VIEW_UNSPECIFIED &&
		buffer.resource().mip_level() == 0 && buffer.resource().layer() == 0,
		"buffer-after-pass request was not encoded as a full BIN resource selector");
}

void after_pass_terminal_mapping_preserves_complete_artifact_receipt() {
	proto::ServerMessage completed;
	auto* terminal = completed.mutable_job_completed();
	terminal->set_job_id(std::string(request_id));
	terminal->set_request_id(std::string(request_id));
	auto* result = terminal->mutable_result();
	result->set_result_manifest_id("manifest-after-pass");
	auto* receipt = result->add_action_receipts();
	receipt->set_action_index(0);
	receipt->set_kind(proto::ACTION_KIND_DUMP_TEXTURE_AFTER_PASS);
	receipt->set_status(proto::RECEIPT_STATUS_OK);
	auto* capture = receipt->mutable_capture();
	capture->set_frame_id(91);
	capture->set_pass_id("composite/composite21");
	capture->set_pass_occurrence(2);
	auto* resource = capture->mutable_resource();
	resource->set_logical_name("colortex0");
	resource->set_physical_name("colortex0.alt");
	resource->set_kind(proto::RESOURCE_KIND_TEXTURE);
	resource->add_available_views(proto::TEXTURE_VIEW_ALTERNATE);
	resource->set_width(1920);
	resource->set_height(1080);
	resource->set_internal_format("RGBA16F");
	resource->set_scalar_type(proto::SCALAR_TYPE_FLOAT16);
	resource->set_byte_size(16'588'800);
	resource->set_frame_id(91);
	auto* artifact = capture->add_artifacts();
	artifact->set_artifact_id("texture-after-pass");
	artifact->set_relative_path("I:/artifacts/texture-after.png");
	artifact->set_sha256(std::string(64, 'a'));
	auto* manifest = result->add_artifacts();
	manifest->set_artifact_id("manifest-after-pass");
	manifest->set_kind(proto::ARTIFACT_KIND_MANIFEST);
	manifest->set_relative_path("I:/artifacts/manifest.json");
	manifest->set_sha256(std::string(64, 'b'));

	const auto outcome = JobProtocol::terminal(completed);
	const auto* mapped = std::get_if<Json>(&outcome);
	require(mapped != nullptr, "after-pass completion did not map to a structured result");
	const auto& mapped_result = mapped->at("result");
	const auto& mapped_capture = mapped_result.at("action_receipts").at(0).at("capture");
	require(mapped_result.at("result_manifest_id") == "manifest-after-pass" &&
		mapped_capture.at("frame_id") == 91 && mapped_capture.at("frame_id").is_number_unsigned() &&
		mapped_capture.at("pass_id") == "composite/composite21" &&
		mapped_capture.at("pass_occurrence") == 2 &&
		mapped_capture.at("resource").at("logical_name") == "colortex0" &&
		mapped_capture.at("resource").at("physical_name") == "colortex0.alt" &&
		mapped_capture.at("resource").at("available_views").at(0) == "TEXTURE_VIEW_ALTERNATE" &&
		mapped_capture.at("resource").at("internal_format") == "RGBA16F" &&
		mapped_capture.at("artifacts").at(0).at("relative_path") == "I:/artifacts/texture-after.png" &&
		mapped_capture.at("artifacts").at(0).at("sha256") == std::string(64, 'a') &&
		mapped_result.at("artifacts").at(0).at("sha256") == std::string(64, 'b'),
		"after-pass terminal mapping dropped pass, view, GL metadata, artifact path, manifest, or hashes");
}

void terminal_integer_scalars_are_canonical_native_numbers() {
	proto::ServerMessage completed;
	auto* terminal = completed.mutable_job_completed();
	terminal->set_job_id(std::string(request_id));
	terminal->set_request_id(std::string(request_id));
	auto* result = terminal->mutable_result();
	auto* inspection = result->add_action_receipts();
	inspection->set_action_index(0);
	inspection->set_kind(proto::ACTION_KIND_INSPECT_SHADER);
	inspection->set_status(proto::RECEIPT_STATUS_OK);
	inspection->mutable_shader_inspection()->mutable_catalog()->set_mapping_sha256("mapping-sha");
	inspection->mutable_shader_inspection()->mutable_catalog()->set_shader_generation(35);
	auto* metrics = result->add_action_receipts();
	metrics->set_action_index(1);
	metrics->set_kind(proto::ACTION_KIND_GET_GPU_METRICS);
	metrics->set_status(proto::RECEIPT_STATUS_OK);
	auto* timing = metrics->mutable_gpu_metrics()->add_metrics();
	timing->set_metric_id("composite_total");
	timing->set_average_ns(1200);
	timing->set_p50_ns(1100);
	timing->set_p95_ns(1300);
	timing->add_samples_ns(1100);
	timing->add_samples_ns(1300);

	std::string raw_encoded;
	google::protobuf::util::JsonPrintOptions options;
	options.preserve_proto_field_names = true;
	options.always_print_fields_with_no_presence = true;
	const auto raw_status = google::protobuf::util::MessageToJsonString(*result, &raw_encoded, options);
	require(raw_status.ok(), "protobuf fixture could not produce its live wire JSON shape");
	const auto raw = Json::parse(raw_encoded);
	require(raw.at("action_receipts").at(0).at("shader_inspection").at("catalog")
		.at("shader_generation") == "35",
		"fixture did not reproduce protobuf JSON's quoted uint64 wire scalar");

	const auto outcome = JobProtocol::terminal(completed);
	const auto* mapped = std::get_if<Json>(&outcome);
	require(mapped != nullptr, "integer-bearing completion did not map to native JSON");
	const auto& receipts = mapped->at("result").at("action_receipts");
	const auto& generation = receipts.at(0).at("shader_inspection").at("catalog")
		.at("shader_generation");
	const auto& mapped_timing = receipts.at(1).at("gpu_metrics").at("metrics").at(0);
	require(generation.is_number_unsigned() && generation == 35 &&
		mapped_timing.at("average_ns").is_number_unsigned() && mapped_timing.at("average_ns") == 1200 &&
		mapped_timing.at("p50_ns") == 1100 && mapped_timing.at("p95_ns") == 1300 &&
		mapped_timing.at("samples_ns") == Json::array({1100, 1300}),
		"strict-v2 terminal mapping retained quoted or inconsistent uint64 scalars");
}

void matrix_auto_load_is_a_prelude_receipt_action() {
	const Json arguments{
		{"actions", Json::array({{{"type", "wait_frames"}, {"frames", 2}}})},
		{"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "defaults"}}})},
		{"matrix", {{"sources", Json::array({"candidate"})}, {"configs", Json::array({"defaults"})}}},
	};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_matrix", arguments, config(), scene(), sources, std::string(request_id));
	const auto& cases = request.submit_job().job().matrix().cases();
	require(cases.size() == 1 && cases[0].actions().actions_size() == 2,
		"matrix case did not contain one auto-load and one requested action");
	require(cases[0].actions().actions(0).has_load_shader() &&
		cases[0].actions().actions(0).prelude() &&
		!cases[0].actions().actions(0).load_shader().config().preserve_current() &&
		cases[0].actions().actions(0).load_shader().config().values().empty() &&
		cases[0].actions().actions(1).has_wait_frames() &&
		!cases[0].actions().actions(1).prelude(),
		"matrix auto-load did not use defaults or preserve prelude semantics");
}

void compile_validation_uses_typed_uuid_cases_without_render_actions() {
	const Json arguments{{"recipe", "compile_validate"}, {"preset_id", "quality"},
		{"source", {{"kind", "workspace"}}}, {"config", {{"QUALITY", 2}}},
		{"baseline", {{"kind", "commit"}, {"revision", "HEAD~1"}}},
		{"baseline_config", {{"QUALITY", 1}}}};
	const std::vector sources{
		source("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
		source("cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa"),
	};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& job = request.submit_job().job();
	require(job.has_compile_validation() && !job.has_action_sequence() && !job.has_matrix(),
		"compile_validate was not encoded as its typed workload");
	require(job.compile_validation().cases_size() == 1 && job.compile_validation().has_baseline(),
		"compile_validate omitted the requested case or baseline");
	require(job.compile_validation().cases(0).source_id() == sources[0].source_uuid() &&
		job.compile_validation().baseline().source_id() == sources[1].source_uuid(),
		"compile_validate used logical labels instead of prepared source UUIDs");
	require(job.compile_validation().cases(0).config().values().at("QUALITY") == "2" &&
		job.compile_validation().baseline().config().values().at("QUALITY") == "1" &&
		job.restore_state().on_success() && job.restore_state().on_error(),
		"compile_validate lost typed configs or mandatory restoration");

	proto::ServerMessage completed;
	auto* terminal = completed.mutable_job_completed();
	terminal->set_job_id(std::string(request_id));
	terminal->set_request_id(std::string(request_id));
	auto* result = terminal->mutable_result()->mutable_compile_validation()->add_cases();
	result->set_case_id("source--config");
	result->mutable_catalog()->set_mapping_sha256(std::string(64, 'a'));
	result->add_added_diagnostics()->set_fingerprint_sha256(std::string(64, 'b'));
	result->mutable_provenance()->set_workspace_id(std::string(workspace_id));
	const auto outcome = JobProtocol::terminal(completed);
	const auto* mapped = std::get_if<Json>(&outcome);
	require(mapped != nullptr && mapped->at("result").at("compile_validation").at("cases").at(0)
		.at("catalog").at("mapping_sha256") == std::string(64, 'a') &&
		mapped->at("result").at("compile_validation").at("cases").at(0)
		.at("added_diagnostics").at(0).at("fingerprint_sha256") == std::string(64, 'b'),
		"compile validation terminal mapping dropped catalog or diagnostic diff data");
}

void recovery_request_has_no_scene_or_source_dependency() {
	const auto request = JobProtocol::request("vibris_run_recipe", {{"recipe", "recover_runtime"}},
		config(), proto::SceneContext{}, {}, std::string(request_id));
	const auto& job = request.submit_job().job();
	require(job.has_recover_runtime() && job.sources().empty() && job.preset_id().empty(),
		"recover_runtime was not encoded as a source-free strict-v2 workload");

	proto::ServerMessage failed;
	failed.set_request_id(std::string(request_id));
	auto* value = failed.mutable_job_failed();
	value->set_job_id(std::string(request_id));
	value->set_request_id(std::string(request_id));
	value->mutable_error()->set_code(proto::ERROR_CODE_RECOVERY_FAILED);
	value->mutable_error()->set_message("manual repair required");
	value->mutable_restoration()->set_status(proto::RECEIPT_STATUS_FAILED);
	value->mutable_restoration()->set_expected_source_uuid("safe-source");
	auto* prelude = value->add_prelude_receipts();
	prelude->set_action_index(0);
	prelude->set_kind(proto::ACTION_KIND_LOAD_SHADER);
	prelude->set_status(proto::RECEIPT_STATUS_OK);
	prelude->mutable_runtime_mutation()->set_source_uuid("safe-source");
	auto* action = value->add_action_receipts();
	action->set_action_index(0);
	action->set_kind(proto::ACTION_KIND_WAIT_FRAMES);
	action->set_status(proto::RECEIPT_STATUS_FAILED);
	action->mutable_error()->set_code(proto::ERROR_CODE_EXECUTION_TIMEOUT);
	const auto outcome = JobProtocol::terminal(failed);
	const auto* error = std::get_if<ToolFailure>(&outcome);
	require(error != nullptr && error->details.contains("restoration") &&
		error->details.at("restoration").at("expected_source_uuid") == "safe-source" &&
		error->details.at("prelude_receipts").size() == 1 &&
		error->details.at("action_receipts").size() == 1 &&
		error->details.at("action_receipts").at(0).at("action_index") == 0,
		"recovery failure mapping dropped restoration or ordered action receipts");
}

void explicit_restore_policy_is_not_overridden() {
	const Json arguments{{"actions", Json::array()},
		{"restore_state", {{"on_success", false}, {"on_error", false}}}};
	const auto request = JobProtocol::request("vibris_run_actions", arguments,
		config(), scene(), {}, std::string(request_id));
	const auto& restore = request.submit_job().job().restore_state();
	require(!restore.on_success() && !restore.on_error(),
		"explicit restore_state policy was replaced by hard-coded defaults");
}

void round_robin_queue_window_is_bounded_but_not_one_turn() {
	const Json arguments{{"recipe", "profile"}, {"source", {{"kind", "workspace"}}},
		{"frames", 4}, {"__vibris_workflow_id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"}};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& timeouts = request.submit_job().job().timeouts();
	require(request.submit_job().job().scheduling_group_id() ==
			"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" &&
		timeouts.queue_timeout_ms() == 15 * 60'000 &&
		timeouts.execution_timeout_ms() >= 120'000 &&
		timeouts.total_timeout_ms() == timeouts.queue_timeout_ms() + timeouts.execution_timeout_ms(),
		"job timeouts do not leave a bounded multi-turn round-robin admission window");
}

void accepted_request_reconnects_with_resume_only() {
	PendingRequestRegistry registry(2);
	const std::vector sources{source()};
	auto request = JobProtocol::request("vibris_run_recipe",
		{{"recipe", "profile"}, {"source", {{"kind", "workspace"}}}, {"frames", 4}},
		config(), scene(), sources, std::string(request_id));
	std::size_t callbacks = 0;
	bool terminal = false;
	require(registry.add(std::move(request), [&](const grpc::Status& status, const proto::ServerMessage& message) {
		require(status.ok(), "registry callback received transport failure");
		++callbacks;
		terminal = JobProtocol::is_terminal(message);
	}), "registry rejected a valid strict-v2 request");
	proto::ServerMessage accepted;
	accepted.set_request_id(std::string(request_id));
	accepted.mutable_job_accepted()->set_job_id(std::string(request_id));
	accepted.mutable_job_accepted()->set_request_id(std::string(request_id));
	require(registry.resolve(accepted) && callbacks == 1 && !terminal && registry.size() == 1,
		"accepted request was not retained as nonterminal");
	const auto reconnect = registry.requests();
	require(reconnect.size() == 1 && reconnect.front().has_resume_job() &&
		!reconnect.front().has_submit_job() && reconnect.front().resume_job().job_id() == request_id &&
		reconnect.front().request_id() == request_id && reconnect.front().workspace_id() == workspace_id,
		"reconnect blindly resubmitted an accepted side effect instead of issuing ResumeJob");
	proto::ServerMessage completed;
	completed.set_request_id(std::string(request_id));
	completed.mutable_job_completed()->set_job_id(std::string(request_id));
	completed.mutable_job_completed()->set_request_id(std::string(request_id));
	require(registry.resolve(completed) && callbacks == 2 && terminal && registry.size() == 0,
		"terminal completion did not retire the resumed request");
}

void resume_registration_and_terminal_mapping_are_strict_v2() {
	PendingRequestRegistry registry(1);
	bool called = false;
	require(registry.add_resume(std::string(request_id), std::string(workspace_id),
		[&](const grpc::Status&, const proto::ServerMessage&) { called = true; }),
		"explicit resume registration failed");
	const auto requests = registry.requests();
	require(requests.size() == 1 && requests.front().protocol_version().major() == 3 &&
		requests.front().has_resume_job() && requests.front().resume_job().job_id() == request_id,
		"explicit resume registration did not construct strict-v2 ResumeJob");
	proto::ServerMessage failed;
	failed.set_request_id(std::string(request_id));
	failed.mutable_job_failed()->set_job_id(std::string(request_id));
	failed.mutable_job_failed()->set_request_id(std::string(request_id));
	failed.mutable_job_failed()->mutable_error()->set_code(proto::ERROR_CODE_SERVER_RESTARTED);
	failed.mutable_job_failed()->mutable_error()->set_message("restart");
	failed.mutable_job_failed()->mutable_error()->set_retryable(true);
	require(registry.resolve(failed) && called, "resumed terminal failure was not delivered");
	const auto outcome = JobProtocol::terminal(failed);
	const auto* error = std::get_if<ToolFailure>(&outcome);
	require(error != nullptr && error->code == "server_restarted" && error->retryable &&
		error->details.at("request_id").get<std::string>() == request_id,
		"strict-v2 terminal failure mapping lost request identity or retryability");
}

} // namespace

int main() {
	try {
		strict_v2_request_contains_typed_texture_and_buffer_actions();
		ordinary_actions_preserve_the_final_runtime_state_by_default();
		explicit_load_action_uses_declared_v2_ids();
		missing_config_values_use_pack_defaults();
		omitted_recipe_config_uses_pack_defaults();
		load_and_screenshot_emits_one_post_load_capture_sequence();
		ab_compare_resets_each_source_before_equal_warmup_and_capture();
		after_pass_actions_preserve_exact_pass_and_resource_selectors();
		after_pass_terminal_mapping_preserves_complete_artifact_receipt();
		terminal_integer_scalars_are_canonical_native_numbers();
		matrix_auto_load_is_a_prelude_receipt_action();
		compile_validation_uses_typed_uuid_cases_without_render_actions();
		recovery_request_has_no_scene_or_source_dependency();
		explicit_restore_policy_is_not_overridden();
		round_robin_queue_window_is_bounded_but_not_one_turn();
		accepted_request_reconnects_with_resume_only();
		resume_registration_and_terminal_mapping_are_strict_v2();
		std::cout << "PASS JobProtocolStrictV2Resume\n";
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL JobProtocolStrictV2Resume: " << error.what() << '\n';
		return 1;
	}
}
