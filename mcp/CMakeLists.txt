cmake_minimum_required(VERSION 3.25)

project(vibris_mcp_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(VIBRIS_ENABLE_SANITIZERS "Enable native address and undefined-behavior sanitizers" OFF)
if(VIBRIS_ENABLE_SANITIZERS)
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
        add_link_options(-fsanitize=address,undefined)
        if(WIN32)
            add_compile_definitions(
                VIBRIS_SANITIZER_BUILD
                _DISABLE_STRING_ANNOTATION
                _DISABLE_VECTOR_ANNOTATION)
            execute_process(
                COMMAND "${CMAKE_CXX_COMPILER}" -print-resource-dir
                OUTPUT_VARIABLE VIBRIS_CLANG_RESOURCE_DIR
                OUTPUT_STRIP_TRAILING_WHITESPACE
                COMMAND_ERROR_IS_FATAL ANY)
            set(VIBRIS_SANITIZER_RUNTIME_DIR "${VIBRIS_CLANG_RESOURCE_DIR}/lib/windows")
        endif()
    elseif(MSVC)
        add_compile_options(/fsanitize=address /Oy-)
        add_link_options(/INCREMENTAL:NO)
    else()
        message(FATAL_ERROR "VIBRIS_ENABLE_SANITIZERS requires Clang, GCC, or MSVC")
    endif()
endif()

find_package(Protobuf CONFIG REQUIRED)
find_package(gRPC CONFIG REQUIRED)
find_package(LibArchive REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)

if(WIN32 AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang" AND NOT MSVC)
    get_property(VIBRIS_IMPORTED_TARGETS DIRECTORY PROPERTY IMPORTED_TARGETS)
    foreach(VIBRIS_IMPORTED_TARGET IN LISTS VIBRIS_IMPORTED_TARGETS)
        if(VIBRIS_IMPORTED_TARGET MATCHES "^absl::")
            get_target_property(VIBRIS_IMPORTED_LINK_LIBRARIES
                "${VIBRIS_IMPORTED_TARGET}" INTERFACE_LINK_LIBRARIES)
            if(VIBRIS_IMPORTED_LINK_LIBRARIES)
                list(FILTER VIBRIS_IMPORTED_LINK_LIBRARIES EXCLUDE REGEX
                    "^(\\$<LINK_ONLY:)?-ignore:4221>?$")
                set_target_properties("${VIBRIS_IMPORTED_TARGET}" PROPERTIES
                    INTERFACE_LINK_LIBRARIES "${VIBRIS_IMPORTED_LINK_LIBRARIES}")
            endif()
        endif()
    endforeach()
    unset(VIBRIS_IMPORTED_TARGETS)
    unset(VIBRIS_IMPORTED_TARGET)
    unset(VIBRIS_IMPORTED_LINK_LIBRARIES)
endif()

if(DEFINED VCPKG_INSTALLED_DIR AND DEFINED VCPKG_TARGET_TRIPLET)
    set(VIBRIS_LIBARCHIVE_ROOT "${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}")
    set_target_properties(LibArchive::LibArchive PROPERTIES
        IMPORTED_CONFIGURATIONS "DEBUG;RELEASE"
        IMPORTED_LOCATION_DEBUG "${VIBRIS_LIBARCHIVE_ROOT}/debug/lib/archive.lib"
        IMPORTED_LOCATION_RELEASE "${VIBRIS_LIBARCHIVE_ROOT}/lib/archive.lib"
        MAP_IMPORTED_CONFIG_MINSIZEREL RELEASE
        MAP_IMPORTED_CONFIG_RELWITHDEBINFO RELEASE)
    unset(VIBRIS_LIBARCHIVE_ROOT)
endif()

set(PROTO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
set(PROTO_FILE "${PROTO_ROOT}/vibris_control.proto")
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(GENERATED_SOURCES
    "${GENERATED_DIR}/vibris_control.pb.cc"
    "${GENERATED_DIR}/vibris_control.grpc.pb.cc")
set(GENERATED_HEADERS
    "${GENERATED_DIR}/vibris_control.pb.h"
    "${GENERATED_DIR}/vibris_control.grpc.pb.h")

add_custom_command(
    OUTPUT ${GENERATED_SOURCES} ${GENERATED_HEADERS}
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}"
    COMMAND protobuf::protoc
        "--proto_path=${PROTO_ROOT}"
        "--cpp_out=${GENERATED_DIR}"
        "--grpc_out=${GENERATED_DIR}"
        "--plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>"
        "${PROTO_FILE}"
    DEPENDS "${PROTO_FILE}" protobuf::protoc gRPC::grpc_cpp_plugin
    VERBATIM)

add_library(vibris_control_protocol STATIC ${GENERATED_SOURCES} ${GENERATED_HEADERS})
target_include_directories(vibris_control_protocol SYSTEM PUBLIC "${GENERATED_DIR}")
target_link_libraries(vibris_control_protocol PUBLIC gRPC::grpc++ protobuf::libprotobuf)

add_executable(vibris-protocol-smoke src/main/cpp/protocol_smoke.cpp)
target_link_libraries(vibris-protocol-smoke PRIVATE vibris_control_protocol)

add_executable(vibris-descriptor-dump src/main/cpp/descriptor_dump.cpp)
target_link_libraries(vibris-descriptor-dump PRIVATE vibris_control_protocol)

add_library(vibris_mcp_core STATIC
    src/main/cpp/config_document.cpp
    src/main/cpp/workspace_artifact_link.cpp
    src/main/cpp/workspace_identity_store.cpp
    src/main/cpp/commit_extractor.cpp
    src/main/cpp/git_repository.cpp
    src/main/cpp/grpc_client.cpp
    src/main/cpp/grpc_control_stream.cpp
    src/main/cpp/grpc_unary_calls.cpp
    src/main/cpp/job_protocol.cpp
    src/main/cpp/mcp_stdio_server.cpp
    src/main/cpp/native_metrics.cpp
    src/main/cpp/nsight_analyzer.cpp
    src/main/cpp/paired_benchmark.cpp
    src/main/cpp/pending_request_registry.cpp
    src/main/cpp/profile_matrix_workflow.cpp
    src/main/cpp/mcp_backend.cpp
    src/main/cpp/source_handler.cpp
    src/main/cpp/source_archive.cpp
    src/main/cpp/source_delivery.cpp
    src/main/cpp/prepared_source.cpp
    src/main/cpp/result_mapper.cpp
    src/main/cpp/scene_context_resolver.cpp
    src/main/cpp/source_path_policy.cpp
    src/main/cpp/source_preparer.cpp
    src/main/cpp/synchronous_job_runner.cpp
    src/main/cpp/tool_argument_policy.cpp
    src/main/cpp/tool_registry.cpp
    src/main/cpp/workspace_copier.cpp
    src/main/cpp/workspace_binding.cpp)
target_include_directories(vibris_mcp_core PUBLIC src/main/cpp)
target_link_libraries(vibris_mcp_core PUBLIC
    vibris_control_protocol
    LibArchive::LibArchive
    nlohmann_json::nlohmann_json
    Advapi32
    Bcrypt)

add_executable(vibris-mcp src/main/cpp/main.cpp)
target_link_libraries(vibris-mcp PRIVATE vibris_mcp_core)

if(MSVC)
    target_compile_options(vibris-protocol-smoke PRIVATE /W4 /permissive-)
    target_compile_options(vibris-descriptor-dump PRIVATE /W4 /permissive-)
    target_compile_options(vibris_mcp_core PRIVATE /W4 /permissive-)
    target_compile_options(vibris-mcp PRIVATE /W4 /permissive-)
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(vibris-state-tests src/test/cpp/state_tests.cpp)
    target_link_libraries(vibris-state-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-protocol-version-tests src/test/cpp/protocol_version_tests.cpp)
    target_include_directories(vibris-protocol-version-tests PRIVATE src/main/cpp)
    target_link_libraries(vibris-protocol-version-tests PRIVATE vibris_control_protocol)

    add_executable(vibris-worktree-discovery-tests src/test/cpp/worktree_discovery_tests.cpp)
    target_link_libraries(vibris-worktree-discovery-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-workspace-source-tests src/test/cpp/workspace_source_tests.cpp)
    target_include_directories(vibris-workspace-source-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-workspace-source-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-commit-source-tests src/test/cpp/commit_source_tests.cpp)
    target_link_libraries(vibris-commit-source-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-git-repository-security-tests src/test/cpp/git_repository_security_tests.cpp)
    target_link_libraries(vibris-git-repository-security-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-stdio-fixture-test src/test/cpp/stdio_fixture_test.cpp)
    target_link_libraries(vibris-stdio-fixture-test PRIVATE nlohmann_json::nlohmann_json)

    add_executable(vibris-stdio-v2-server src/test/cpp/stdio_v2_server.cpp)
    target_link_libraries(vibris-stdio-v2-server PRIVATE vibris_control_protocol)

    add_executable(vibris-grpc-tests src/test/cpp/grpc_reconnect_tests.cpp)
    target_include_directories(vibris-grpc-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-grpc-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-grpc-planned-restart-tests src/test/cpp/grpc_planned_restart_tests.cpp)
    target_link_libraries(vibris-grpc-planned-restart-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-grpc-request-ownership-tests src/test/cpp/grpc_request_ownership_tests.cpp)
    target_link_libraries(vibris-grpc-request-ownership-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-grpc-source-ownership-tests src/test/cpp/grpc_source_ownership_tests.cpp)
    target_include_directories(vibris-grpc-source-ownership-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-grpc-source-ownership-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-grpc-accepted-resume-tests src/test/cpp/grpc_accepted_resume_tests.cpp)
    target_include_directories(vibris-grpc-accepted-resume-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-grpc-accepted-resume-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-job-protocol-tests src/test/cpp/job_protocol_tests.cpp)
    target_link_libraries(vibris-job-protocol-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-synchronous-job-runner-tests src/test/cpp/synchronous_job_runner_tests.cpp)
    target_include_directories(vibris-synchronous-job-runner-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-synchronous-job-runner-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-workspace-artifact-link-tests src/test/cpp/workspace_artifact_link_tests.cpp)
    target_link_libraries(vibris-workspace-artifact-link-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-action-schema-tests src/test/cpp/action_schema_tests.cpp)
    target_link_libraries(vibris-action-schema-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-nsight-analyzer-tests src/test/cpp/nsight_analyzer_tests.cpp)
    target_link_libraries(vibris-nsight-analyzer-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-durable-workflow-tests src/test/cpp/profile_matrix_workflow_tests.cpp)
    target_include_directories(vibris-durable-workflow-tests PRIVATE src/test/cpp)
    target_link_libraries(vibris-durable-workflow-tests PRIVATE vibris_mcp_core)

    add_executable(vibris-paired-benchmark-tests src/test/cpp/paired_benchmark_tests.cpp)
    target_link_libraries(vibris-paired-benchmark-tests PRIVATE vibris_mcp_core)

    if(MSVC)
        target_compile_options(vibris-protocol-version-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-state-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-worktree-discovery-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-workspace-source-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-commit-source-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-git-repository-security-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-stdio-fixture-test PRIVATE /W4 /permissive-)
        target_compile_options(vibris-stdio-v2-server PRIVATE /W4 /permissive-)
        target_compile_options(vibris-grpc-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-grpc-planned-restart-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-grpc-request-ownership-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-grpc-source-ownership-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-grpc-accepted-resume-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-job-protocol-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-synchronous-job-runner-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-workspace-artifact-link-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-action-schema-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-nsight-analyzer-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-durable-workflow-tests PRIVATE /W4 /permissive-)
        target_compile_options(vibris-paired-benchmark-tests PRIVATE /W4 /permissive-)
    endif()

    add_custom_target(mcp-tests
        DEPENDS vibris-protocol-smoke vibris-descriptor-dump vibris-protocol-version-tests
                vibris-state-tests vibris-worktree-discovery-tests vibris-workspace-source-tests
                vibris-commit-source-tests vibris-git-repository-security-tests
                vibris-stdio-fixture-test vibris-stdio-v2-server
                vibris-grpc-tests vibris-grpc-planned-restart-tests
                vibris-grpc-request-ownership-tests
                vibris-grpc-source-ownership-tests vibris-grpc-accepted-resume-tests)
    add_dependencies(mcp-tests vibris-job-protocol-tests vibris-action-schema-tests)
    add_dependencies(mcp-tests vibris-nsight-analyzer-tests)
    add_dependencies(mcp-tests vibris-synchronous-job-runner-tests)
    add_dependencies(mcp-tests vibris-workspace-artifact-link-tests)
    add_dependencies(mcp-tests vibris-durable-workflow-tests)
    add_dependencies(mcp-tests vibris-paired-benchmark-tests)

    add_test(
        NAME descriptor-dump
        COMMAND vibris-descriptor-dump
            --output "$<TARGET_FILE_DIR:vibris-descriptor-dump>/vibris_control_descriptor.bin")
    add_test(NAME protocol-smoke-cli-validation COMMAND vibris-protocol-smoke)
    set_tests_properties(protocol-smoke-cli-validation PROPERTIES WILL_FAIL TRUE)
    add_test(NAME protocol-version-contract COMMAND vibris-protocol-version-tests)
    add_test(
        NAME protocol-smoke-reject-v1
        COMMAND vibris-protocol-smoke
            --host 127.0.0.1 --port 1 --protocol-major 1 --protocol-minor 0
            --message-id reject-v1 --scenario hello)
    set_tests_properties(protocol-smoke-reject-v1 PROPERTIES
        PASS_REGULAR_EXPRESSION "UNSUPPORTED_VERSION")
    add_test(
        NAME protocol-smoke-reject-missing-version
        COMMAND vibris-protocol-smoke
            --host 127.0.0.1 --port 1
            --message-id reject-missing --scenario hello)
    set_tests_properties(protocol-smoke-reject-missing-version PROPERTIES
        PASS_REGULAR_EXPRESSION "UNSUPPORTED_VERSION")

    add_test(
        NAME stdio-fixture-contract
        COMMAND vibris-stdio-fixture-test
            "${CMAKE_CURRENT_SOURCE_DIR}/../integration-tests/fixtures/mcp/happy-core-tools.jsonl")

    foreach(scenario
            WorkspaceIdentityConcurrentFirstUse
            WorkspaceIdentityDeterministicPublication
            WorkspaceIdentityIoFailuresCleanup
            WorkspaceIdentityMalformedWinner
            WorkspaceIdentityRetriesOnlyPublicationLoser
            WorkspaceIdentityBlocksTempSubstitution
            WorkspaceIdentityPreservesCreateCollision
            WorkspaceIdentityRejectsDuplicateKeys
            WorkspaceIdentityRejectsUnsupportedVersion
            ConfigRejectsUnsupportedVersion
            WorkspaceIdentityEnforcesJsonDepth
            WorkspaceIdentityClassifiesNonregularState
            WorkspaceIdentityRejectsReparseState
            WorkspaceIdentityMapsAccessErrors
            WorkspaceIdentityRejectsActiveWriter
            SameWorktreeBackendsCoexist
            RequestScopedWorktreeRouting
            TypedPresetDiscoveryAndRequestScene
            ToolMetadataIsRequestScoped
            ResourceListsAreEmpty
            StatusLeaseTransitionMapping)
        add_test(NAME ${scenario} COMMAND vibris-state-tests ${scenario})
    endforeach()

    foreach(scenario
            ValidRelativeArchiveEntry
            RejectArchiveTraversal
            RejectSymlinkOrReparse
            RejectWin32ArchiveComponents
            RejectWin32DeviceNameCommitEntry
            CommitArchiveStreaming50MiB
            DistinctUuidWithoutDedup)
        add_test(NAME ${scenario} COMMAND vibris-commit-source-tests ${scenario})
    endforeach()

    add_test(NAME WorktreeDiscovery COMMAND vibris-worktree-discovery-tests)

    foreach(scenario
            WorkspaceSnapshotTrackedUntrackedIgnoredAndRetry
            DirectFinalArchiveRelease
            MutationTwiceFails
            MissingPendingRootRejected
            CheckedFileSwapDoesNotReadReparseTarget
            QueuedSnapshotMaterializesWithStableProvenance
            ProvenanceClean
            ProvenanceMetadataOnly
            ProvenanceTrackedChange
            ProvenanceUntrackedChange
            ProvenanceDeletion
            DetachedWorkspaceRecordsEmptyBranchAndExactHead
            CrossLanguageSourceHashVector
            SourceSoak)
        add_test(NAME ${scenario} COMMAND vibris-workspace-source-tests ${scenario})
    endforeach()

    add_test(NAME WorkspaceLocalGitExecutableIgnored COMMAND vibris-git-repository-security-tests)
    find_program(VIBRIS_PWSH_EXECUTABLE pwsh.exe REQUIRED)
    add_test(
        NAME GitProcessSecurityProbe
        COMMAND "${VIBRIS_PWSH_EXECUTABLE}" -NoLogo -NoProfile -File
            "${CMAKE_CURRENT_SOURCE_DIR}/../integration-tests/scripts/git-process-security-probe.ps1"
            -Repository "${CMAKE_CURRENT_SOURCE_DIR}/.."
            -NativeSecurityFixture "$<TARGET_FILE:vibris-git-repository-security-tests>")
    set_tests_properties(GitProcessSecurityProbe PROPERTIES TIMEOUT 10)

    add_test(
        NAME AsyncGrpcReconnectAndBoundedRegistry
        COMMAND vibris-grpc-tests
            --scenario async-reconnect-registry
            --port 55063
            --request-count 256
            --drop-after 64
            --registry-limit 256)
    add_test(
        NAME NoDetachedThreads
        COMMAND vibris-grpc-tests
            --scenario no-detached-threads
            --port 55064
            --request-count 1
            --drop-after 0
            --registry-limit 1)
    add_test(
        NAME GrpcReconnect
        COMMAND vibris-grpc-tests
            --scenario async-reconnect-registry
            --port 55074
            --request-count 256
            --drop-after 64
            --registry-limit 256)
    set_tests_properties(
        AsyncGrpcReconnectAndBoundedRegistry NoDetachedThreads GrpcReconnect
        PROPERTIES RUN_SERIAL TRUE TIMEOUT 30)

    foreach(scenario
            AcceptedRequestOwnershipReconnectsWithResumeJob
            JobStateOwnershipSnapshotRemainsPending)
        add_test(NAME ${scenario} COMMAND vibris-grpc-request-ownership-tests ${scenario})
    endforeach()

    foreach(scenario
            SingleJobAcceptedTransfersAbSourcesOnce
            JobStateControlsSourceOwnership)
        add_test(NAME ${scenario} COMMAND vibris-grpc-source-ownership-tests ${scenario})
    endforeach()

    add_test(NAME AcceptedRequestResumesAfterDisconnect COMMAND vibris-grpc-accepted-resume-tests)
    set_tests_properties(AcceptedRequestResumesAfterDisconnect PROPERTIES RUN_SERIAL TRUE TIMEOUT 30)
    add_test(NAME PlannedRestartWaitsForReplacementRuntime COMMAND vibris-grpc-planned-restart-tests)
    set_tests_properties(PlannedRestartWaitsForReplacementRuntime PROPERTIES RUN_SERIAL TRUE TIMEOUT 30)

    add_test(NAME JobProtocolStrictV2Resume COMMAND vibris-job-protocol-tests)
    set_tests_properties(JobProtocolStrictV2Resume PROPERTIES RUN_SERIAL TRUE TIMEOUT 60)
    foreach(scenario
            StrictV2ResultShape
            ProfileNormalizationStrictV2
            ProvenanceCheckoutStateStrict
            ProfileRetryPresetIdentity
            MatrixNormalizationStrictV2
            VisualNormalizationStrictV2
            ScreenshotResultCompactStrictV2)
        add_test(NAME ${scenario} COMMAND vibris-synchronous-job-runner-tests ${scenario})
    endforeach()
    foreach(scenario
            ArtifactLinkRecursivelyLocalizesStrictV2Paths
            ArtifactLinkRejectsBeforePartialRewrite
            ArtifactLinkRejectsUnsafeTargets)
        add_test(NAME ${scenario} COMMAND vibris-workspace-artifact-link-tests ${scenario})
    endforeach()
    add_test(NAME ActionSchemaPassResourceV2Contract COMMAND vibris-action-schema-tests)
    add_test(NAME NsightAnalyzerReplayContract COMMAND vibris-nsight-analyzer-tests)
    add_test(NAME DurableMatrixCheckpointResume COMMAND vibris-durable-workflow-tests)
    add_test(NAME CompileValidationCheckpointResume COMMAND vibris-durable-workflow-tests)
    set_tests_properties(DurableMatrixCheckpointResume PROPERTIES RUN_SERIAL TRUE TIMEOUT 60)
    set_tests_properties(CompileValidationCheckpointResume PROPERTIES RUN_SERIAL TRUE TIMEOUT 60)
    foreach(scenario
            PairedOrderStrategies
            PairedAggregation
            MeasuredNoiseFloorRejection
            PairedMismatchGuards
            PairedVisualGate
            PairedVisualReceiptGuards
            TypedGuardrailRegression
            PairedDirectionReversal
            PairedTemporalDrift
            PairedCompileGate)
        add_test(NAME ${scenario} COMMAND vibris-paired-benchmark-tests ${scenario})
    endforeach()

    function(add_stdio_mcp_probe test_name)
        add_test(
            NAME ${test_name}
            COMMAND powershell.exe -NoLogo -NoProfile -File
                "${CMAKE_CURRENT_SOURCE_DIR}/../integration-tests/scripts/stdio-mcp-probe.ps1"
                -Exe "$<TARGET_FILE:vibris-mcp>"
                -WorkspaceRoot
                    "${CMAKE_CURRENT_SOURCE_DIR}/../.vibris/tmp/stdio-v2-tools/worktree"
                -FakeServerExe "$<TARGET_FILE:vibris-stdio-v2-server>"
                -Requests
                    "${CMAKE_CURRENT_SOURCE_DIR}/../integration-tests/fixtures/mcp/happy-core-tools.jsonl")
        set_tests_properties(${test_name} PROPERTIES RUN_SERIAL TRUE TIMEOUT 60)
    endfunction()

    add_stdio_mcp_probe(StdioMcpToolHappyPath)
    add_stdio_mcp_probe(RestartRetainsWorkspaceIdentity)
    add_stdio_mcp_probe(FakeServerStatus)
    add_stdio_mcp_probe(StdioLifecycle)

    if(VIBRIS_ENABLE_SANITIZERS AND VIBRIS_SANITIZER_RUNTIME_DIR)
        get_property(VIBRIS_TESTS DIRECTORY PROPERTY TESTS)
        set_tests_properties(${VIBRIS_TESTS} PROPERTIES
            ENVIRONMENT_MODIFICATION
                "PATH=path_list_prepend:${VIBRIS_SANITIZER_RUNTIME_DIR};ASAN_OPTIONS=set:detect_container_overflow=0")
        unset(VIBRIS_TESTS)
    endif()
endif()
