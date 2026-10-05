#pragma once

#include "vibris_control.pb.h"

#include <cstdint>
#include <string>
#include <string_view>

namespace vibris::mcp::test {

namespace proto = ::vibris::control::v2;

inline void fill_provenance(proto::ResultProvenance* provenance, const std::string_view source_uuid,
    const std::string_view source_sha256 = "source-sha", const std::string_view config_sha256 = "config-sha",
    const std::string_view scene_sha256 = "scene-sha") {
    provenance->set_workspace_id("workspace");
    provenance->set_worktree_root("I:/code/worktree");
    provenance->set_vcs_checkout_state(proto::VCS_CHECKOUT_STATE_ATTACHED);
    provenance->set_branch("main");
    provenance->set_requested_revision("HEAD");
    provenance->set_resolved_revision("0123456789abcdef");
    provenance->set_start_head("0123456789abcdef");
    provenance->set_completion_head("0123456789abcdef");
    provenance->set_head_changed(false);
    provenance->set_stale(false);
    provenance->set_shader_tree_id("shader-tree");
    provenance->set_dirty_shader_delta_sha256("dirty-sha");
    provenance->set_source_snapshot_sha256(source_sha256);
    provenance->set_active_source_uuid(source_uuid);
    provenance->set_config_sha256(config_sha256);
    provenance->set_preset_id("preset");
    provenance->set_preset_sha256("preset-sha");
    provenance->set_scene_sha256(scene_sha256);
    provenance->mutable_effective_settings()->set_settings_sha256(config_sha256);
    provenance->set_shader_loaded_at_unix_ms(1000);
    provenance->set_pass_mapping_sha256("mapping-sha");
    auto* environment = provenance->mutable_environment();
    environment->set_minecraft_version("1.21.11");
    environment->set_iris_version("iris-test");
    environment->set_vibris_version("vibris-test");
    environment->set_java_version("21");
    environment->set_operating_system("Windows");
    environment->set_gpu_vendor("GPU vendor");
    environment->set_gpu_renderer("GPU renderer");
    environment->set_opengl_version("4.6");
    environment->set_driver_version("driver");
}

inline void fill_restoration(proto::RestorationReceipt* restoration) {
    restoration->set_status(proto::RECEIPT_STATUS_OK);
    restoration->set_expected_source_uuid("original");
    restoration->set_actual_source_uuid("original");
    restoration->set_expected_source_sha256("original-sha");
    restoration->set_actual_source_sha256("original-sha");
    restoration->set_expected_settings_sha256("original-settings");
    restoration->set_actual_settings_sha256("original-settings");
    restoration->set_expected_scene_sha256("original-scene");
    restoration->set_actual_scene_sha256("original-scene");
    restoration->set_temporal_state_reset(true);
    restoration->set_verified_at_unix_ms(2000);
}

inline void fill_result_common(proto::JobResult* result, const std::string_view source_uuid = "candidate") {
    auto* timings = result->mutable_timings();
    timings->set_accepted_at_unix_ms(100);
    timings->set_started_at_unix_ms(200);
    timings->set_completed_at_unix_ms(300);
    timings->set_queue_ms(100);
    timings->set_execution_ms(100);
    timings->set_total_ms(200);
    fill_provenance(result->mutable_provenance(), source_uuid);
    fill_restoration(result->mutable_restoration());
    result->set_result_manifest_id("manifest-id");
}

inline proto::ActionReceipt* add_load_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::string_view source_uuid, const std::string_view source_sha256) {
    auto* receipt = result->add_prelude_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_LOAD_SHADER);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* mutation = receipt->mutable_runtime_mutation();
    mutation->set_source_uuid(source_uuid);
    mutation->set_source_sha256(source_sha256);
    mutation->mutable_effective_settings()->set_settings_sha256("config-sha");
    mutation->set_scene_sha256("scene-sha");
    mutation->set_completed_at_unix_ms(900 + action_index);
    return receipt;
}

inline proto::ActionReceipt* add_metrics_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t average_ns = 1000) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_GET_GPU_METRICS);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* metrics = receipt->mutable_gpu_metrics();
    metrics->set_timing_unit("ns");
    metrics->set_sampled_frames(3);
    auto* metric = metrics->add_metrics();
    metric->set_metric_id("composite_total");
    metric->set_program_id("composite");
    metric->set_pass_id("composite");
    metric->set_average_ns(average_ns);
    metric->set_p50_ns(average_ns - 100);
    metric->set_p95_ns(average_ns + 100);
    metric->add_samples_ns(average_ns - 100);
    metric->add_samples_ns(average_ns);
    metric->add_samples_ns(average_ns + 100);
    return receipt;
}

inline proto::ServerMessage completed_profile_message() {
    proto::ServerMessage message;
    message.set_request_id("request-profile");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-profile");
    completed->set_request_id("request-profile");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "candidate", "source-sha");
    add_metrics_receipt(result, 1);
    return message;
}

inline proto::ServerMessage completed_inspection_message() {
    proto::ServerMessage message;
    message.set_request_id("request-inspection");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-inspection");
    completed->set_request_id("request-inspection");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "candidate", "source-sha");
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(1);
    receipt->set_kind(proto::ACTION_KIND_INSPECT_SHADER);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* catalog = receipt->mutable_shader_inspection()->mutable_catalog();
    catalog->set_mapping_sha256("mapping-sha");
    catalog->set_shader_generation(7);
    auto* program = catalog->add_programs();
    program->set_program_id("composite");
    program->set_pass_id("composite");
    program->set_compile_state(proto::COMPILE_STATE_SUCCEEDED);
    program->set_link_state(proto::COMPILE_STATE_SUCCEEDED);
    return message;
}

inline void add_matrix_case(proto::MatrixResult* matrix, const std::string_view case_id,
    const std::string_view source_uuid, const std::uint64_t average_ns) {
    auto* value = matrix->add_cases();
    value->set_case_id(case_id);
    value->set_status(proto::RECEIPT_STATUS_OK);
    fill_provenance(value->mutable_provenance(), source_uuid);
    auto* receipt = value->add_action_receipts();
    receipt->set_action_index(0);
    receipt->set_kind(proto::ACTION_KIND_GET_GPU_METRICS);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* metrics = receipt->mutable_gpu_metrics();
    metrics->set_timing_unit("ns");
    metrics->set_sampled_frames(2);
    auto* metric = metrics->add_metrics();
    metric->set_metric_id("composite_total");
    metric->set_program_id("composite");
    metric->set_pass_id("composite");
    metric->set_average_ns(average_ns);
    metric->set_p50_ns(average_ns);
    metric->set_p95_ns(average_ns + 100);
    metric->add_samples_ns(average_ns);
    metric->add_samples_ns(average_ns + 100);
}

inline proto::ServerMessage completed_matrix_message() {
    proto::ServerMessage message;
    message.set_request_id("request-matrix");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-matrix");
    completed->set_request_id("request-matrix");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    auto* matrix = result->mutable_matrix();
    add_matrix_case(matrix, "baseline--default", "baseline", 1200);
    add_matrix_case(matrix, "candidate--default", "candidate", 1000);
    matrix->set_requested_cases(2);
    matrix->set_completed_cases(2);
    matrix->set_failed_cases(0);
    return message;
}

inline proto::ActionReceipt* add_capture_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t frame_id) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_TAKE_SCREENSHOT);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    receipt->mutable_capture()->set_frame_id(frame_id);
    return receipt;
}

inline proto::ActionReceipt* add_reset_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t completed_at_unix_ms) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_RESET_TEMPORAL_STATE);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    receipt->mutable_reset_temporal()->set_completed_at_unix_ms(completed_at_unix_ms);
    return receipt;
}

inline proto::ActionReceipt* add_wait_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t start_frame, const std::uint64_t end_frame) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_WAIT_FRAMES);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* wait = receipt->mutable_wait_frames();
    wait->set_requested_frames(static_cast<std::uint32_t>(end_frame - start_frame));
    wait->set_start_frame(start_frame);
    wait->set_end_frame(end_frame);
    wait->set_completed_frames(static_cast<std::uint32_t>(end_frame - start_frame));
    return receipt;
}

inline proto::ServerMessage completed_visual_message() {
    proto::ServerMessage message;
    message.set_request_id("request-visual");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-visual");
    completed->set_request_id("request-visual");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "baseline", "baseline-sha");
    add_load_receipt(result, 1, "candidate", "candidate-sha");
    add_reset_receipt(result, 0, 1001);
    add_wait_receipt(result, 1, 1, 41);
    add_capture_receipt(result, 2, 41);
    add_reset_receipt(result, 3, 1002);
    add_wait_receipt(result, 4, 2, 42);
    add_capture_receipt(result, 5, 42);
    auto* comparison = result->add_action_receipts();
    comparison->set_action_index(6);
    comparison->set_kind(proto::ACTION_KIND_GET_CAPTURE_STATUS);
    comparison->set_status(proto::RECEIPT_STATUS_OK);
    comparison->mutable_comparison()->set_passed(true);
    comparison->mutable_comparison()->mutable_metrics()->set_mean_absolute_error(0.0);
    comparison->mutable_comparison()->mutable_metrics()->set_sample_count(100);
    comparison->mutable_comparison()->mutable_metrics()->set_pixel_count(100);

    auto* metrics = result->add_artifacts();
    metrics->set_artifact_id("diff-json");
    metrics->set_relative_path("visual/diff.json");
    metrics->set_kind(proto::ARTIFACT_KIND_BENCHMARK_METRICS);
    metrics->set_format(proto::ARTIFACT_FORMAT_JSON);
    auto* heatmap = result->add_artifacts();
    heatmap->set_artifact_id("diff-heatmap");
    heatmap->set_relative_path("visual/diff.png");
    heatmap->set_kind(proto::ARTIFACT_KIND_HEATMAP);
    heatmap->set_format(proto::ARTIFACT_FORMAT_PNG);
    return message;
}

inline void fill_artifact(proto::ArtifactMetadata* artifact, const std::string_view artifact_id,
    const proto::ArtifactKind kind, const proto::ArtifactRole role, const std::string_view relative_path,
    const std::string_view sha256, const std::uint64_t byte_size, const proto::ArtifactFormat format,
    const std::string_view media_type) {
    artifact->set_artifact_id(artifact_id);
    artifact->set_job_id("job-screenshot");
    artifact->set_request_id("request-screenshot");
    artifact->set_relative_path(relative_path);
    artifact->set_kind(kind);
    artifact->set_format(format);
    artifact->set_role(role);
    artifact->set_media_type(media_type);
    artifact->set_byte_size(byte_size);
    artifact->set_sha256(sha256);
    artifact->set_created_at_unix_ms(400);
    artifact->set_expires_at_unix_ms(500);
}

inline proto::ServerMessage completed_screenshot_message() {
    proto::ServerMessage message;
    message.set_request_id("request-screenshot");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-screenshot");
    completed->set_request_id("request-screenshot");
    auto* result = completed->mutable_result();
    fill_result_common(result, "source-uuid");
    result->set_result_manifest_id("manifest-artifact");
    auto* provenance = result->mutable_provenance();
    provenance->set_requested_revision("workspace");
    provenance->set_resolved_revision("0123456789abcdef");
    provenance->set_preset_id("night-gi-1-720p");
    provenance->set_preset_sha256("preset-sha256");
    provenance->set_config_sha256("config-sha256");
    provenance->mutable_effective_settings()->set_settings_sha256("settings-sha256");
    auto* load = add_load_receipt(result, 0, "source-uuid", "source-sha256");
    load->mutable_runtime_mutation()->mutable_effective_settings()->set_settings_sha256("settings-sha256");
    for (std::size_t index = 0; index < 346; ++index) {
        const auto name = "SETTING_" + std::to_string(index);
        for (auto* settings : {provenance->mutable_effective_settings(),
                load->mutable_runtime_mutation()->mutable_effective_settings()}) {
            auto* setting = settings->add_settings();
            setting->set_name(name);
            setting->set_value("0");
            setting->set_default_value("0");
            setting->set_origin(proto::SHADER_SETTING_ORIGIN_DEFAULT);
            setting->set_changed_from_default(false);
        }
    }

    auto* wait = result->add_action_receipts();
    wait->set_action_index(0);
    wait->set_kind(proto::ACTION_KIND_WAIT_FRAMES);
    wait->set_status(proto::RECEIPT_STATUS_OK);
    wait->mutable_wait_frames()->set_requested_frames(32);
    wait->mutable_wait_frames()->set_completed_frames(32);
    wait->mutable_wait_frames()->set_start_frame(100);
    wait->mutable_wait_frames()->set_end_frame(132);

    auto* screenshot = result->add_action_receipts();
    screenshot->set_action_index(1);
    screenshot->set_kind(proto::ACTION_KIND_TAKE_SCREENSHOT);
    screenshot->set_status(proto::RECEIPT_STATUS_OK);
    auto* capture = screenshot->mutable_capture();
    capture->set_frame_id(133);
    auto* resource = capture->mutable_resource();
    resource->set_logical_name("beauty");
    resource->set_physical_name("beauty");
    resource->set_kind(proto::RESOURCE_KIND_FINAL_FRAMEBUFFER);
    resource->set_width(1280);
    resource->set_height(720);
    resource->set_depth(1);
    resource->set_mip_levels(1);
    resource->set_layers(1);
    resource->set_internal_format("RGBA8");
    resource->set_channel_count(4);
    resource->set_scalar_type(proto::SCALAR_TYPE_UINT8);
    resource->set_byte_size(3'686'400);
    resource->set_frame_id(133);

    constexpr std::string_view root =
        "I:/server/artifacts/workspace-id/job-screenshot/request-screenshot/";
    auto* screenshot_artifact = result->add_artifacts();
    fill_artifact(screenshot_artifact, "screenshot-artifact", proto::ARTIFACT_KIND_SCREENSHOT,
        proto::ARTIFACT_ROLE_PRIMARY, std::string(root) + "screenshot.png", "screenshot-sha256", 1'888'374,
        proto::ARTIFACT_FORMAT_PNG, "image/png");
    screenshot_artifact->mutable_resource()->CopyFrom(*resource);
    capture->add_artifacts()->CopyFrom(*screenshot_artifact);
    fill_artifact(result->add_artifacts(), "result-artifact", proto::ARTIFACT_KIND_RESULT,
        proto::ARTIFACT_ROLE_PRIMARY, std::string(root) + "result.json", "result-sha256", 192'710,
        proto::ARTIFACT_FORMAT_JSON, "application/json");
    fill_artifact(result->add_artifacts(), "shader-log", proto::ARTIFACT_KIND_SHADER_COMPILE_LOG,
        proto::ARTIFACT_ROLE_DIAGNOSTIC, std::string(root) + "shader.log", "shader-log-sha256", 25,
        proto::ARTIFACT_FORMAT_TEXT, "text/plain; charset=utf-8");
    fill_artifact(result->add_artifacts(), "manifest-artifact", proto::ARTIFACT_KIND_MANIFEST,
        proto::ARTIFACT_ROLE_METADATA, std::string(root) + "manifest.json", "manifest-sha256", 1'535,
        proto::ARTIFACT_FORMAT_JSON, "application/json");
    return message;
}

}
