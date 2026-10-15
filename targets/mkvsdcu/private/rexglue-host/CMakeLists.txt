# mkvsdcu - ReXGlue Recompiled Project
#
# SDK boilerplate lives in generated/rexglue.cmake.

cmake_minimum_required(VERSION 3.25)
project(mkvsdcu LANGUAGES CXX)

set(MKVSDCU_CODEGEN_MANIFEST "${CMAKE_CURRENT_SOURCE_DIR}/mkvsdcu_manifest.toml"
    CACHE FILEPATH "Manifest for the validated local game folder")

# A configure that fails early can leave empty per-config flags in the cache,
# which silently builds the whole recompiled game at -O0 (several times slower).
foreach(_mkvsdcu_lang CXX)
    if(CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT CMAKE_${_mkvsdcu_lang}_FLAGS_RELEASE MATCHES "-O")
        message(WARNING "CMAKE_${_mkvsdcu_lang}_FLAGS_RELEASE was empty; restoring -O3 -DNDEBUG")
        set(CMAKE_${_mkvsdcu_lang}_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    endif()
endforeach()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(generated/rexglue.cmake)

# Sources
# MKVDCU's HD device initialization hardcodes 1280x720 at 825F55BC.
# Build a derived translation unit; codegen output stays intact and regeneration
# is detected by CMake. Only these two immediates are replaced, before globals,
# presentation parameters and render-target allocations consume the dimensions.
if(ANDROID)
    set(_resolution_source "${CMAKE_CURRENT_SOURCE_DIR}/generated/default/mkvsdcu_recomp.65.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_resolution_source}")
    file(READ "${_resolution_source}" _resolution_code)
    set(_resolution_original "loc_825F55BC:\n\t// li r30,1280\n\tctx.r30.s64 = 1280;\n\t// li r26,720\n\tctx.r26.s64 = 720;")
    string(REPLACE "\r\n" "\n" _resolution_code "${_resolution_code}")
    string(FIND "${_resolution_code}" "${_resolution_original}" _resolution_offset)
    if(_resolution_offset EQUAL -1)
        message(FATAL_ERROR "MKVDCU HD resolution anchor changed; inspect codegen before building")
    endif()
    string(REPLACE "${_resolution_original}"
        "loc_825F55BC:\n\tctx.r30.s64 = mkvdcu_internal_width();\n\tctx.r26.s64 = mkvdcu_internal_height();\n\tREX_STORE_U32(0x82FC4D48, ctx.r30.u32);\n\tREX_STORE_U32(0x82FC4D4C, ctx.r26.u32);"
        _resolution_code "${_resolution_code}")
    # The alternate scene mode overwrites 82F4D17C/180 with 1024x576. Keep
    # resource creation and later viewport/copy consumers on the same scale.
    set(_scene_original "\t// li r11,1024\n\tctx.r11.s64 = 1024;\n\t// li r10,576\n\tctx.r10.s64 = 576;\n\t// stw r11,-11908(r8)")
    string(FIND "${_resolution_code}" "${_scene_original}" _scene_offset)
    if(_scene_offset EQUAL -1)
        message(FATAL_ERROR "MKVDCU alternate scene resolution anchor changed; inspect codegen")
    endif()
    string(REPLACE "${_scene_original}"
        "\tctx.r11.u64 = mkvdcu_scale_scene_dimension(1024);\n\tctx.r10.u64 = mkvdcu_scale_scene_dimension(576);\n\t// stw r11,-11908(r8)"
        _resolution_code "${_resolution_code}")
    set(_resolution_code "extern \"C\" unsigned int mkvdcu_internal_width();\nextern \"C\" unsigned int mkvdcu_internal_height();\nextern \"C\" unsigned int mkvdcu_scale_scene_dimension(unsigned int);\n${_resolution_code}")
    set(_resolution_derived "${CMAKE_CURRENT_BINARY_DIR}/mkvsdcu_recomp_resolution.cpp")
    file(CONFIGURE OUTPUT "${_resolution_derived}" CONTENT "${_resolution_code}" @ONLY)
    set_source_files_properties("${_resolution_derived}" PROPERTIES COMPILE_OPTIONS "${REXGLUE_RECOMP_OPTIONS}")

    # This label is reached only after 827F6160 proved the ring has no room.
    # Keep the original watchdog, memory reads and completion predicate intact.
    set(_ring_source "${CMAKE_CURRENT_SOURCE_DIR}/generated/default/mkvsdcu_recomp.157.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_ring_source}")
    file(READ "${_ring_source}" _ring_code)
    string(REPLACE "\r\n" "\n" _ring_code "${_ring_code}")
    set(_ring_anchor "loc_827F6150:\n\t// addi r3,r1,80")
    string(FIND "${_ring_code}" "${_ring_anchor}" _ring_offset)
    if(_ring_offset EQUAL -1)
        message(FATAL_ERROR "MKVDCU GPU ring wait anchor changed; inspect codegen")
    endif()
    string(REPLACE "${_ring_anchor}"
        "loc_827F6150:\n\tmkvdcu_ring_wait_backoff(REX_LOAD_U32(REX_LOAD_U32(ctx.r31.u32 + 10896)));\n\t// addi r3,r1,80"
        _ring_code "${_ring_code}")
    set(_ring_code "extern \"C\" void mkvdcu_ring_wait_backoff(unsigned int);\n${_ring_code}")
    set(_ring_derived "${CMAKE_CURRENT_BINARY_DIR}/mkvsdcu_recomp_ring_wait.cpp")
    file(CONFIGURE OUTPUT "${_ring_derived}" CONTENT "${_ring_code}" @ONLY)
    set_source_files_properties("${_ring_derived}" PROPERTIES COMPILE_OPTIONS "${REXGLUE_RECOMP_OPTIONS}")
endif()

set(MKVSDCU_SOURCES
    src/main.cpp
    src/mkvsdcu_app.cpp
    src/port_menu/port_menu.cpp
    src/port_menu/port_config.cpp
    src/port_menu/settings_catalog.cpp
    src/host_tweaks.cpp
    src/input/controller_filter.cpp
    src/mods/mod_overlay.cpp
    src/telemetry/frame_telemetry.cpp
    src/telemetry/android_health.cpp
    src/telemetry/system_telemetry.cpp
    src/telemetry/perf_overlay.cpp
    src/telemetry/guest_profiler.cpp
)

if(ANDROID)
    add_library(mkvdcu SHARED ${MKVSDCU_SOURCES})
    set(MKVSDCU_TARGET mkvdcu)
elseif(WIN32)
    add_executable(mkvsdcu WIN32 ${MKVSDCU_SOURCES})
    set(MKVSDCU_TARGET mkvsdcu)
else()
    add_executable(mkvsdcu ${MKVSDCU_SOURCES})
    set(MKVSDCU_TARGET mkvsdcu)
endif()

# This is target-private host plumbing. The plugin remains selectable at
# launch via --gpu_plugin xenos. The Android translation unit above adjusts
# the game's HD device initialization dimensions before surface creation.
rexglue_setup_target(${MKVSDCU_TARGET} GPU_PLUGINS xenos)
if(ANDROID)
    get_target_property(_recomp_sources ${MKVSDCU_TARGET}_recomp SOURCES)
    list(TRANSFORM _recomp_sources REPLACE "(^|.*/)mkvsdcu_recomp\\.65\\.cpp$" "${_resolution_derived}")
    list(TRANSFORM _recomp_sources REPLACE "(^|.*/)mkvsdcu_recomp\\.157\\.cpp$" "${_ring_derived}")
    set_property(TARGET ${MKVSDCU_TARGET}_recomp PROPERTY SOURCES "${_recomp_sources}")
endif()

if(ANDROID)
    # rexruntime already owns rexui and ImGui. Relinking their OBJECT targets
    # here creates another GImGui: the HUD sets the game's copy, while XAM
    # dialogs enter the runtime's copy with no context and fault after START.
    # Only consume the headers; all UI/ImGui calls resolve to rexruntime.
    target_include_directories(${MKVSDCU_TARGET} PRIVATE
        $<TARGET_PROPERTY:imgui::imgui,INTERFACE_INCLUDE_DIRECTORIES>)
else()
    target_link_libraries(${MKVSDCU_TARGET} PRIVATE rexui imgui::imgui)
endif()

if(ANDROID)
    target_link_libraries(mkvdcu PRIVATE android log vulkan OpenSLES EGL)
endif()

if(WIN32)
    # Performance overlay: GPU engine counters and video memory queries.
    target_link_libraries(mkvsdcu PRIVATE pdh dxgi winmm dbghelp)

    # rexglue_setup_target copies the FidelityFX DLLs only for in-tree target
    # names; an installed SDK exports them as rex::amd_fidelityfx_*, so the
    # runtime would fail to load without this.
    foreach(_mkvsdcu_fx rex::amd_fidelityfx_dx12 rex::amd_fidelityfx_vk)
        if(TARGET ${_mkvsdcu_fx})
            add_custom_command(TARGET mkvsdcu POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    $<TARGET_FILE:${_mkvsdcu_fx}> $<TARGET_FILE_DIR:mkvsdcu>
                VERBATIM)
        endif()
    endforeach()
endif()
