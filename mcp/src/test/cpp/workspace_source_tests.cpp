#include "result_mapper.hpp"
#include "source_delivery.hpp"
#include "workspace_source_fixture.hpp"

#include <array>
#include <exception>
#include <fstream>
#include <iostream>
#include <nlohmann/json.hpp>
#include <string_view>
#include <utility>

namespace {
namespace fs = std::filesystem;
using Json = nlohmann::json;
using vibris::mcp::LocalSourceDelivery;
using vibris::mcp::ResultMapper;
using vibris::mcp::SourceDestinationLayout;
using vibris::mcp::SourcePreparer;
using vibris::mcp::test::WorkspaceFixture;
using vibris::mcp::test::capture_state_error;
using vibris::mcp::test::file_totals;
using vibris::mcp::test::generous_limits;
using vibris::mcp::test::pending_has_no_sources;
using vibris::mcp::test::require;
using vibris::mcp::test::run_git;

bool zstd_checksum_enabled(const fs::path& archive) {
    std::ifstream input(archive, std::ios::binary);
    std::array<unsigned char, 5> header{};
    input.read(reinterpret_cast<char*>(header.data()), header.size());
    return input.gcount() == static_cast<std::streamsize>(header.size()) &&
        header[0] == 0x28 && header[1] == 0xb5 && header[2] == 0x2f && header[3] == 0xfd &&
        (header[4] & 0x04) != 0;
}

void workspace_snapshot_tracked_untracked_ignored_and_retry() {
    WorkspaceFixture fixture;
    std::size_t hooks = 0;
    const auto timestamp = fs::last_write_time(fixture.live_file());
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits(),
        SourceDestinationLayout::pending_directory, [&] {
            ++hooks;
            if (hooks == 1) {
                vibris::mcp::test::write_file(fixture.live_file(), "live-1");
                fs::last_write_time(fixture.live_file(), timestamp);
            }
        });
    const auto prepared = preparer.prepare_workspace();
    const auto& reference = prepared.reference();
    const auto [expected_files, expected_bytes] = file_totals(fixture.shaders());
    require(hooks == 2 && prepared.attempts() == 2,
        "Same-size, same-time mutation did not trigger one retry.");
    require(reference.file_count() == expected_files && reference.total_bytes() == expected_bytes,
        "Archive metadata did not describe the accepted input.");
    require(reference.archive_format() == vibris::control::v2::SOURCE_ARCHIVE_FORMAT_TAR_ZSTD &&
            reference.compressed_bytes() == fs::file_size(prepared.archive()) &&
            reference.snapshot_sha256().size() == 64 && zstd_checksum_enabled(prepared.archive()),
        "Prepared source omitted tar.zst metadata or source hash.");
    require(prepared.directory() == fixture.pending() / reference.source_uuid() &&
            prepared.archive() == prepared.directory() / "source.tar.zst" &&
            fs::is_regular_file(prepared.archive()) && !fs::exists(prepared.directory() / "tree") &&
            !fs::exists(fixture.pending() / ".staging"),
        "Workspace preparation created staging or an unpacked tree.");
}

void direct_final_archive_release() {
    WorkspaceFixture fixture;
    fs::path released;
    {
        SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
        auto prepared = preparer.prepare_workspace();
        released = prepared.directory();
        prepared.release();
        require(fs::is_regular_file(released / "source.tar.zst") &&
                std::distance(fs::directory_iterator(released), fs::directory_iterator{}) == 1,
            "Pending source did not contain exactly one directly written archive.");
    }
    require(fs::is_directory(released), "Released source package was deleted.");
}

void mutation_twice_fails() {
    WorkspaceFixture fixture;
    std::size_t hooks = 0;
    const auto timestamp = fs::last_write_time(fixture.live_file());
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits(),
        SourceDestinationLayout::pending_directory, [&] {
            ++hooks;
            vibris::mcp::test::write_file(fixture.live_file(), hooks == 1 ? "live-1" : "live-2");
            fs::last_write_time(fixture.live_file(), timestamp);
        });
    const auto error = capture_state_error([&] { static_cast<void>(preparer.prepare_workspace()); });
    require(error.code == "SOURCE_CHANGED_DURING_SNAPSHOT" && hooks == 2,
        "Two mutations did not exhaust the single retry boundary.");
    require(pending_has_no_sources(fixture.pending()), "Failed packaging retained a partial archive.");
}

void missing_pending_root_is_rejected() {
    WorkspaceFixture fixture;
    const auto missing = fixture.pending() / "missing";
    const auto error = capture_state_error([&] {
        SourcePreparer preparer(fixture.worktree(), missing, generous_limits());
        static_cast<void>(preparer.prepare_workspace());
    });
    require(error.code == "SERVER_NOT_READY" && !fs::exists(missing),
        "Missing destination was created or returned the wrong error.");
}

void checked_file_swap_does_not_read_reparse_target() {
    WorkspaceFixture fixture;
    const auto target = fixture.worktree() / "outside.glsl";
    vibris::mcp::test::write_file(target, "outside");
    const auto link = fixture.shaders() / "linked.glsl";
    if (!CreateSymbolicLinkW(link.c_str(), target.c_str(), SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE)) {
        throw std::runtime_error("Could not create source reparse fixture.");
    }
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
    const auto error = capture_state_error([&] { static_cast<void>(preparer.prepare_workspace()); });
    require(error.code == "SOURCE_CONTAINS_REPARSE_POINT" && pending_has_no_sources(fixture.pending()),
        "Workspace packaging followed a reparse point.");
}

void source_soak() {
    WorkspaceFixture fixture;
    for (std::size_t index = 0; index < 25; ++index) {
        {
            SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
            const auto prepared = preparer.prepare_workspace();
            require(fs::is_regular_file(prepared.archive()), "Soak packaging omitted its archive.");
        }
        require(pending_has_no_sources(fixture.pending()), "Soak packaging retained an owned archive.");
    }
}

void queued_snapshot_materializes_with_stable_provenance() {
    WorkspaceFixture fixture;
    vibris::mcp::test::TempDirectory durable("queued-source");
    vibris::mcp::test::TempDirectory server("queued-server");
    SourcePreparer freezer(fixture.worktree(), durable.path(), generous_limits(),
        SourceDestinationLayout::archive_file);
    auto frozen = freezer.prepare_workspace();
    vibris::mcp::test::write_file(fixture.live_file(), "changed-after-queue");
    LocalSourceDelivery delivery(server.path());
    auto delivered = delivery.deliver(frozen.archive(), frozen.reference());
    require(delivered.reference().source_uuid() != frozen.reference().source_uuid() &&
            delivered.reference().snapshot_sha256() == frozen.reference().snapshot_sha256() &&
            delivered.reference().compressed_bytes() == frozen.reference().compressed_bytes(),
        "Queued delivery changed immutable source provenance.");
    require(fs::is_regular_file(delivered.archive()) &&
            fs::file_size(delivered.archive()) == frozen.reference().compressed_bytes() &&
            !fs::exists(delivered.directory() / "tree"),
        "Queued delivery did not copy exactly one compressed archive.");
}

void detached_workspace_records_empty_branch_and_exact_head() {
    WorkspaceFixture fixture;
    run_git(fixture.worktree(), "checkout --detach --quiet");
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
    const auto prepared = preparer.prepare_workspace();
    const auto& reference = prepared.reference();
    require(reference.vcs_checkout_state() == vibris::control::v2::VCS_CHECKOUT_STATE_DETACHED &&
            reference.branch().empty() && reference.start_head().size() == 40 &&
            reference.start_head() == reference.resolved_revision(),
        "Detached workspace provenance lost exact HEAD or empty branch.");
}

void cross_language_source_hash_vector() {
    WorkspaceFixture fixture;
    std::filesystem::remove_all(fixture.shaders());
    std::filesystem::create_directories(fixture.shaders());
    vibris::mcp::test::write_file(fixture.shaders() / "empty.glsl", {});
    vibris::mcp::test::write_file(fixture.shaders() / fs::path(L"lib/中文/data.bin"),
        std::string_view("\0\1\xff*", 4));
    vibris::mcp::test::write_file(fixture.shaders() / fs::path(L"supplementary/🚀.glsl"), "rocket");
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());

    const auto prepared = preparer.prepare_workspace();

    require(prepared.reference().snapshot_sha256() ==
            "090d0522c1a199a333ac1d81ba7463c48f51004780ba893b0da40a414c0fd14b",
        "Native packaging did not match the cross-language UTF-8 source hash vector.");
}

void make_clean(WorkspaceFixture& fixture) {
    run_git(fixture.worktree(), "add -f shaders");
    run_git(fixture.worktree(), "commit --quiet -m provenance-clean");
}

Json receipt(const vibris::control::v2::PreparedSourceRef& source) {
    return {{"result", {{"provenance", {
        {"workspace_id", "fixture-workspace"},
        {"worktree_root", source.origin().workspace().worktree_root()},
        {"vcs_checkout_state", vibris::control::v2::VcsCheckoutState_Name(source.vcs_checkout_state())},
        {"branch", source.branch()}, {"requested_revision", source.requested_revision()},
        {"resolved_revision", source.resolved_revision()}, {"start_head", source.start_head()},
        {"completion_head", source.start_head()}, {"head_changed", false}, {"stale", false},
        {"shader_tree_id", source.shader_tree_id()},
        {"dirty_shader_delta_sha256", source.dirty_shader_delta_sha256()},
        {"source_snapshot_sha256", source.snapshot_sha256()},
        {"active_source_uuid", source.source_uuid()},
    }}}}};
}

const Json& finalized(Json& value) {
    ResultMapper::finalize_provenance(value);
    return value.at("result").at("provenance");
}

void provenance_clean() {
    WorkspaceFixture fixture;
    make_clean(fixture);
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
    auto prepared = preparer.prepare_workspace();
    auto value = receipt(prepared.reference());
    const auto& provenance = finalized(value);
    require(!provenance.at("head_changed").get<bool>() && !provenance.at("stale").get<bool>(),
        "Unchanged workspace was not finalized as clean.");
}

void provenance_metadata_only() {
    WorkspaceFixture fixture;
    make_clean(fixture);
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
    auto prepared = preparer.prepare_workspace();
    run_git(fixture.worktree(), "commit --quiet --allow-empty -m metadata-only");
    auto value = receipt(prepared.reference());
    const auto& provenance = finalized(value);
    require(provenance.at("head_changed").get<bool>() && !provenance.at("stale").get<bool>(),
        "Metadata-only commit was treated as shader staleness.");
}

template <typename Mutation>
void require_stale_delta(std::string_view label, Mutation&& mutation) {
    WorkspaceFixture fixture;
    make_clean(fixture);
    SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
    auto prepared = preparer.prepare_workspace();
    std::forward<Mutation>(mutation)(fixture);
    auto first = receipt(prepared.reference());
    auto second = receipt(prepared.reference());
    const auto& a = finalized(first);
    const auto& b = finalized(second);
    require(a.at("stale").get<bool>() && a.at("dirty_shader_delta_sha256").get<std::string>().size() == 64 &&
            a.at("dirty_shader_delta_sha256") == b.at("dirty_shader_delta_sha256"),
        std::string(label) + " did not produce deterministic stale provenance.");
}

void provenance_tracked_change() {
    require_stale_delta("Tracked change", [](WorkspaceFixture& fixture) {
        vibris::mcp::test::write_file(fixture.shaders() / "composite.fsh", "tracked-change");
    });
}

void provenance_untracked_change() {
    require_stale_delta("Untracked change", [](WorkspaceFixture& fixture) {
        vibris::mcp::test::write_file(fixture.shaders() / "new.glsl", "untracked-change");
    });
}

void provenance_deletion() {
    require_stale_delta("Deletion", [](WorkspaceFixture& fixture) {
        require(fs::remove(fixture.shaders() / "composite.fsh"), "Could not delete source fixture.");
    });
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 14> test_cases{{
    {"WorkspaceSnapshotTrackedUntrackedIgnoredAndRetry", workspace_snapshot_tracked_untracked_ignored_and_retry},
    {"DirectFinalArchiveRelease", direct_final_archive_release}, {"MutationTwiceFails", mutation_twice_fails},
    {"MissingPendingRootRejected", missing_pending_root_is_rejected},
    {"CheckedFileSwapDoesNotReadReparseTarget", checked_file_swap_does_not_read_reparse_target},
    {"SourceSoak", source_soak},
    {"QueuedSnapshotMaterializesWithStableProvenance", queued_snapshot_materializes_with_stable_provenance},
    {"DetachedWorkspaceRecordsEmptyBranchAndExactHead", detached_workspace_records_empty_branch_and_exact_head},
    {"CrossLanguageSourceHashVector", cross_language_source_hash_vector},
    {"ProvenanceClean", provenance_clean}, {"ProvenanceMetadataOnly", provenance_metadata_only},
    {"ProvenanceTrackedChange", provenance_tracked_change},
    {"ProvenanceUntrackedChange", provenance_untracked_change}, {"ProvenanceDeletion", provenance_deletion},
}};
} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-workspace-source-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown workspace source test scenario: " << argv[1] << '\n';
    return 2;
}
