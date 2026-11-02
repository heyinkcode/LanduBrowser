#!/usr/bin/env python3
"""Syntax-check staged C++ with the pinned Android compiler, without altering checkout."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

from isolated_mojo import IsolatedMojo


TARGETS = {
    "third_party/blink/renderer/modules/mediastream/media_device_info.cc":
        "obj/third_party/blink/renderer/modules/mediastream/mediastream/media_device_info.o",
    "third_party/blink/renderer/modules/mediastream/input_device_info.cc":
        "obj/third_party/blink/renderer/modules/mediastream/mediastream/input_device_info.o",
    "third_party/blink/renderer/modules/mediastream/media_stream_track_impl.cc":
        "obj/third_party/blink/renderer/modules/mediastream/mediastream/media_stream_track_impl.o",
    "content/browser/media/media_devices_util.cc":
        "obj/content/browser/browser/media_devices_util.o",
    "third_party/blink/renderer/core/dom/element.cc":
        "obj/third_party/blink/renderer/core/core_hot/element.o",
    "third_party/blink/renderer/core/dom/range.cc":
        "obj/third_party/blink/renderer/core/core/range.o",
    "third_party/blink/renderer/modules/webaudio/offline_audio_context.cc":
        "obj/third_party/blink/renderer/modules/webaudio/webaudio/offline_audio_context.o",
    "third_party/blink/renderer/modules/webaudio/analyser_node.cc":
        "obj/third_party/blink/renderer/modules/webaudio/webaudio/analyser_node.o",
    "third_party/blink/renderer/modules/webaudio/audio_worklet_processor.cc":
        "obj/third_party/blink/renderer/modules/webaudio/webaudio/audio_worklet_processor.o",
    "chrome/browser/landu_geolocation_provider.cc":
        "obj/chrome/browser/core/landu_geolocation_provider.o",
    "components/permissions/permission_context_base.cc":
        "obj/components/permissions/permissions/permission_context_base.o",
    "chrome/browser/chrome_content_browser_client.cc":
        "obj/chrome/browser/core/chrome_content_browser_client.o",
    "chrome/browser/android/storage_collection_synchronizer_android.cc":
        "obj/chrome/browser/android/android/storage_collection_synchronizer_android.o",
    "chrome/browser/android/tab_state_storage_service_android.cc":
        "obj/chrome/browser/android/android/tab_state_storage_service_android.o",
    "third_party/blink/renderer/modules/webgl/webgl2_rendering_context_base.cc":
        "obj/third_party/blink/renderer/modules/webgl/webgl/webgl2_rendering_context_base.o",
    "third_party/blink/renderer/modules/webgl/webgl_rendering_context_base.cc":
        "obj/third_party/blink/renderer/modules/webgl/webgl/webgl_rendering_context_base.o",
    "third_party/blink/renderer/modules/webgpu/gpu_adapter.cc":
        "obj/third_party/blink/renderer/modules/webgpu/webgpu/gpu_adapter.o",
    "components/embedder_support/user_agent_utils.cc":
        "obj/components/embedder_support/user_agent/user_agent_utils.o",
    "chrome/browser/prefs/chrome_command_line_pref_store.cc":
        "obj/chrome/browser/prefs/impl/chrome_command_line_pref_store.o",
    "third_party/blink/renderer/platform/graphics/image_data_buffer.cc":
        "obj/third_party/blink/renderer/platform/platform/image_data_buffer.o",
    "third_party/blink/renderer/core/html/canvas/canvas_async_blob_creator.cc":
        "obj/third_party/blink/renderer/core/core/canvas_async_blob_creator.o",
    "third_party/blink/renderer/core/html/canvas/html_canvas_element.cc":
        "obj/third_party/blink/renderer/core/core/html_canvas_element.o",
    "third_party/blink/renderer/modules/canvas/canvas2d/base_rendering_context_2d.cc":
        "obj/third_party/blink/renderer/modules/canvas/canvas/base_rendering_context_2d.o",
    "content/browser/renderer_host/render_process_host_impl.cc":
        "obj/content/browser/browser/render_process_host_impl.o",
    "third_party/blink/renderer/core/timezone/timezone_controller.cc":
        "obj/third_party/blink/renderer/core/core/timezone_controller.o",
    "chrome/browser/profiles/android/profile_manager_utils.cc":
        "obj/chrome/browser/profiles/misc/profile_manager_utils.o",
    "third_party/blink/renderer/platform/fonts/font_platform_data_cache.cc":
        "obj/third_party/blink/renderer/platform/platform/font_platform_data_cache.o",
    "third_party/blink/common/device_memory/approximated_device_memory.cc":
        "obj/third_party/blink/common/common/approximated_device_memory.o",
    "third_party/blink/renderer/core/frame/navigator_concurrent_hardware.cc":
        "obj/third_party/blink/renderer/core/core/navigator_concurrent_hardware.o",
}


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def prepare_blink_buildflags(source, build, output):
    relative = "third_party/blink/public/common/buildflags.h"
    text = (build / "toolchain.ninja").read_text(encoding="utf-8")
    rules = re.findall(r"^build gen/" + re.escape(relative) + r": ([^\s]+)", text, re.MULTILINE)
    if len(rules) != 1:
        raise RuntimeError("Missing or ambiguous Blink buildflags generation rule")
    blocks = re.findall(r"^rule " + re.escape(rules[0]) + r"\n((?:  [^\n]*\n)+)", text, re.MULTILINE)
    if len(blocks) != 1:
        raise RuntimeError("Missing Blink buildflags rule body")
    values = re.findall(r"^  rspfile_content = (.+)$", blocks[0], re.MULTILINE)
    if len(values) != 1:
        raise RuntimeError("Missing Blink buildflags values")
    flags = shlex.split(values[0])
    expected = {"ENABLE_JXL_DECODER", "RTC_USE_H265"}
    if flags[0] != "--flags" or len(flags) != 3 or {flag.split("=")[0] for flag in flags[1:]} != expected:
        raise RuntimeError("Unexpected Blink buildflags schema")
    if any(not re.fullmatch(r"[A-Z0-9_]+=(true|false)", flag) for flag in flags[1:]):
        raise RuntimeError("Unexpected Blink buildflag value")
    response = output / "blink-buildflags.rsp"
    response.write_text(values[0], encoding="utf-8")
    # Chromium's diagnostics classify generated code by the /gen/ path segment.
    # Keep that classification without disabling checks for staged hand-written code.
    generated = output / "gen"
    (generated / relative).parent.mkdir(parents=True)
    subprocess.run([sys.executable, "-B", str(source / "build/write_buildflag_header.py"),
                    "--output", relative, "--rulename", "//third_party/blink/public/common:buildflags",
                    "--gen-dir", str(generated), "--definitions", str(response)], check=True)
    return generated


def prepare_font_headers(source, build, generated):
    """Use the pinned generators in a separate gen tree, never build outputs."""
    text = (build / "toolchain.ninja").read_text(encoding="utf-8")
    scripts = source / "third_party/blink/renderer/build/scripts"
    destination = generated / "third_party/blink/renderer/platform"
    destination.mkdir(parents=True, exist_ok=True)
    (generated / "third_party/blink/renderer/build/scripts").mkdir(parents=True, exist_ok=True)
    for target, script, input_file in (
        ("font_family_names", "make_names.py", "fonts/font_family_names.json5"),
        ("runtime_enabled_features", "make_runtime_features.py", "runtime_enabled_features.json5"),
    ):
        rule = "__third_party_blink_renderer_platform_" + target + "___build_toolchain_android_android_clang_arm64__rule"
        matches = re.findall(r"^rule " + re.escape(rule) + r"\n  command = (.+)$", text, re.MULTILINE)
        if len(matches) != 1:
            raise RuntimeError("Missing pinned generator rule: " + target)
        original = shlex.split(matches[0])
        expected = [
            "../../third_party/cpython3/host/bin/python3",
            "../../third_party/blink/renderer/build/scripts/run_with_pythonpath.py",
            "-I", "../../third_party/blink/renderer/build/scripts",
            "-I", "../../third_party", "-I", "../../third_party/pyjson5/src", "-I", "../../tools",
            "../../third_party/blink/renderer/build/scripts/" + script,
            "../../third_party/blink/renderer/platform/" + input_file,
            "--output_dir", "gen/third_party/blink/renderer/platform",
        ]
        if original != expected:
            raise RuntimeError("Pinned generator arguments changed: " + target)
        command = [str(source / "third_party/cpython3/host/bin/python3"), "-B",
                   str(scripts / "run_with_pythonpath.py")]
        for include in (scripts, source / "third_party", source / "third_party/pyjson5/src", source / "tools"):
            command.extend(["-I", str(include)])
        command.extend([str(scripts / script),
                        str(source / "third_party/blink/renderer/platform" / input_file),
                        "--output_dir", str(destination)])
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        subprocess.run(command, cwd=generated, env=env, check=True, timeout=60)


def prepare_profile_jni(source, build, stage, generated, tab=False):
    group = "chrome/browser/tab" if tab else "chrome/browser/profiles/android"
    relative = Path(group + "/jni_headers")
    catalogs = build / ("gen/" + str(relative) + "__type_catalogs.json")
    if json.loads(catalogs.read_text(encoding="utf-8")) != []:
        raise RuntimeError("Profile JNI type catalogs changed; review the pinned generator")
    prefix = "__" + group.replace("/", "_") + "_jni_headers__action___build_toolchain_android_android_clang_arm64__rule"
    text = (build / "toolchain.ninja").read_text(encoding="utf-8")
    rules = re.findall(r"^rule " + re.escape(prefix) + r"\n  command = (.+)$", text, re.MULTILINE)
    if len(rules) != 1:
        raise RuntimeError("Missing pinned Profile JNI rule")
    names = (("CollectionSaveForwarder", "CollectionStorageObserverFactory", "StorageCollectionSynchronizer", "StorageLoadedData",
              "StorageRestoreOrchestratorFactory", "TabGroupCollectionData", "TabStateStorageService", "TabStateStorageServiceFactory",
              "TrustedCdn", "WebContentsState", "state/LevelDBPersistedDataStorage", "state/PersistedTabData", "state/SendTabToSelfTabCardLabelData")
             if tab else ("OtrProfileId", "Profile", "ProfileKey", "ProfileKeyUtil", "ProfileManager", "ProfileManagerUtils", "ProfileResolver"))
    expected = ["../../third_party/cpython3/host/bin/python3", "../../third_party/jni_zero/jni_zero.py", "from-source",
                "--output-dir", "gen/jni_headers/" + str(relative), "--enable-safe-pointers",
                "--type-catalogs-file", "gen/" + str(relative) + "__type_catalogs.json",
                "--srcjar-path", "gen/" + str(relative) + ".srcjar",
                "--placeholder-srcjar-path", "gen/" + str(relative) + "_placeholder.srcjar",
                "--output-type-catalog", "gen/" + str(relative) + ".type_catalog.json",
                "--depfile", "gen/" + str(relative) + ".type_catalog.d",
                "--enable-jni-multiplexing", "--allow-private-called-by-natives"]
    for name in names:
        simple = Path(name).name
        expected.extend(["--shared-header-name", simple + "_shared_jni.h", "--unshared-header-name", simple + "_jni.h"])
    java_dir = Path(group + "/java/src/org/chromium/chrome/browser/" + ("tab" if tab else "profiles"))
    expected.extend("--input-file=../../" + str(java_dir / (name + ".java")) for name in names)
    if shlex.split(rules[0]) != expected:
        raise RuntimeError("Profile JNI generator arguments changed")
    (generated / relative).parent.mkdir(parents=True, exist_ok=True)
    command = [str(source / "third_party/cpython3/host/bin/python3"), "-B",
               str(source / "third_party/jni_zero/jni_zero.py"), "from-source",
               "--output-dir", str(Path("jni_headers") / relative), "--enable-safe-pointers",
               "--type-catalogs-file", str(catalogs),
               "--srcjar-path", str(relative) + ".srcjar",
               "--placeholder-srcjar-path", str(relative) + "_placeholder.srcjar",
               "--output-type-catalog", str(relative) + ".type_catalog.json",
               "--depfile", str(relative) + ".type_catalog.d",
               "--enable-jni-multiplexing", "--allow-private-called-by-natives"]
    for name in names:
        simple = Path(name).name
        command.extend(["--shared-header-name", simple + "_shared_jni.h", "--unshared-header-name", simple + "_jni.h"])
    for name in names:
        patched = {"TabStateStorageService", "StorageCollectionSynchronizer"} if tab else {"ProfileManagerUtils"}
        root = stage / "files" if name in patched else source
        command.append("--input-file=" + str(root / java_dir / (name + ".java")))
    subprocess.run(command, cwd=generated, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"), check=True, timeout=60)


def prepare_android_buildflags(source, build, output, generated):
    text = (build / "toolchain.ninja").read_text(encoding="utf-8")
    blocks = re.findall(r"^rule (\S+___build_toolchain_android_android_clang_arm64__rule)\n((?:  [^\n]*\n)+)", text, re.MULTILINE)
    found = set()
    for rule, body in blocks:
        commands = re.findall(r"^  command = (.+)$", body, re.MULTILINE)
        if len(commands) != 1 or "../../build/write_buildflag_header.py " not in commands[0]:
            continue
        command = shlex.split(commands[0])
        if len(command) != 10 or command[:2] != ["../../third_party/cpython3/host/bin/python3", "../../build/write_buildflag_header.py"] \
                or command[2::2] != ["--output", "--rulename", "--gen-dir", "--definitions"] \
                or command[-3:] != ["gen", "--definitions", "${rspfile}"]:
            raise RuntimeError("Unexpected buildflag generation command: " + rule)
        relative, label = command[3], command[5]
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or path.suffix != ".h" or relative in found:
            raise RuntimeError("Unsafe or repeated buildflag destination")
        found.add(relative)
        values = re.findall(r"^  rspfile_content = (.+)$", body, re.MULTILINE)
        if len(values) != 1:
            raise RuntimeError("Missing buildflags values: " + rule)
        flags = shlex.split(values[0])
        if not flags or flags[0] != "--flags" or any(not re.fullmatch(r"[A-Z0-9_]+=[^\r\n]+", flag) for flag in flags[1:]):
            raise RuntimeError("Unexpected buildflags schema: " + rule)
        response = output / "android-buildflags.rsp"
        response.write_text(values[0], encoding="utf-8")
        (generated / path).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, "-B", str(source / "build/write_buildflag_header.py"),
                        "--output", relative, "--rulename", label,
                        "--gen-dir", str(generated), "--definitions", str(response)], check=True)
    if "chrome/common/buildflags.h" not in found:
        raise RuntimeError("Missing Chrome buildflags rule")
    print(json.dumps({"isolatedAndroidBuildflags": len(found)}), flush=True)


def prepare_fixed_mojo_headers(source, build, generated, family="content"):
    """Regenerate this fixed header group from existing module metadata, away from the live build."""
    if family == "storage-public":
        directory = "components/services/storage/public/mojom"
        names = ["blob_storage_context", "cache_storage_control", "file_system_access_context",
                 "local_storage_control", "quota_client", "service_worker_database",
                 "service_worker_storage_control", "session_storage_control", "storage_policy_update",
                 "storage_service", "storage_usage_info"]
    elif family == "storage-privileged":
        directory = "components/services/storage/privileged/mojom"
        names = ["indexed_db_control", "indexed_db_control_test"]
    elif family == "renderer":
        directory = "content/common"
        names = ["agent_scheduling_group", "associated_interfaces", "child_process",
                 "dom_automation_controller", "download/mhtml_file_writer", "field_trial_recorder",
                 "frame", "frame_messages", "input/input_injector", "media/media_log_records",
                 "navigation_client", "render_message_filter", "renderer", "renderer_host",
                 "renderer_variations_configuration", "synthetic_trial_configuration", "web_ui",
                 "gin_java_bridge"]
    elif family == "chrome-renderer":
        directory = "chrome/common"
        names = ["actor", "chrome_render_frame", "google_accounts_private_api_extension",
                 "media/webrtc_logging", "net/net_error_page_support", "net_benchmarking",
                 "network_diagnostics", "network_easter_egg", "open_search_description_document_handler",
                 "plugin", "readaloud/read_aloud", "renderer_configuration",
                 "tab_context_decryption_token_extension", "trusted_vault_encryption_keys_extension",
                 "sandbox_status_extension_android", "mhtml_page_notifier"]
    elif family == "content":
        directory = "content/public/common"
        names = ["alternative_error_page_override_info", "drop_data", "extra_mojo_js_features",
                 "resource_usage_reporter", "webplugininfo", "window_container_type"]
    else:
        raise RuntimeError("Unreviewed Mojo header family")
    exported = family in ("content", "renderer")
    group = directory + ("/mojo_bindings" if family in ("renderer", "chrome-renderer") else
                         "/interfaces" if family == "content" else "/mojom")
    requested = directory + "/" + names[0] + ".mojom.h"
    command = subprocess.check_output(
        ["ninja", "-C", str(build), "-t", "commands", "-s",
         "gen/" + requested], text=True)
    expected = ["../../third_party/cpython3/host/bin/python3",
                "../../mojo/public/tools/bindings/mojom_bindings_generator.py",
                "--use_bundled_pylibs", "-o", "gen", "generate", "-d", "../../", "-I", "../../",
                "--bytecode_path", "gen/mojo/public/tools/bindings"]
    if exported:
        expected += ["--export_attribute", "CONTENT_EXPORT", "--export_header", "content/common/content_export.h"]
    expected += ["--filelist=gen/" + group + "__generator.rsp", "-g", "c++", "--typemap", "gen/" + group + "__type_mappings"]
    if shlex.split(command) != expected:
        raise RuntimeError("Content Mojo generator rule changed")
    files = (build / ("gen/" + group + "__generator.rsp")).read_text().splitlines()
    if files != ["../../" + directory + "/" + name + ".mojom" for name in names]:
        raise RuntimeError("Content Mojo input set changed")
    if any(not (build / "gen" / directory / (name + ".mojom-module")).is_file() for name in names):
        parser_command = subprocess.check_output(
            ["ninja", "-C", str(build), "-t", "commands", "-s",
             "gen/" + directory + "/" + names[0] + ".mojom-module"], text=True)
        expected_parser = ["../../third_party/cpython3/host/bin/python3",
            "../../mojo/public/tools/mojom/mojom_parser.py", "--input-root", "../..",
            "--input-root", "gen", "--output-root", "gen",
            "--mojom-file-list=gen/" + group + "__parser_action.rsp",
            "--check-imports", "gen/" + group + ".build_metadata"]
        features = (["skip_android_unmigrated_actor_files", "enable_offline_pages"]
                    if family == "chrome-renderer" else []) + ["is_posix", "is_android"]
        for feature in features:
            expected_parser += ["--enable-feature", feature]
        if shlex.split(parser_command) != expected_parser:
            raise RuntimeError("Mojo parser rule changed: " + family)
        if (build / ("gen/" + group + "__parser_action.rsp")).read_text().splitlines() != files:
            raise RuntimeError("Mojo parser input set changed: " + family)
        prepared_parser = [str(source / expected_parser[0].removeprefix("../../")), "-B",
            str(source / expected_parser[1].removeprefix("../../")), *expected_parser[2:]]
        prepared_parser[prepared_parser.index("--output-root") + 1] = str(generated)
        prepared_parser += ["--module-root", "gen"]
        subprocess.run(prepared_parser, cwd=build,
                       env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"), check=True, timeout=60)
    for name in names:
        relative = Path(directory + "/" + name + ".mojom-module")
        (generated / relative).parent.mkdir(parents=True, exist_ok=True)
        if not (generated / relative).is_file():
            shutil.copyfile(build / "gen" / relative, generated / relative)
    prepared = [str(source / "third_party/cpython3/host/bin/python3"), "-B",
                str(source / "mojo/public/tools/bindings/mojom_bindings_generator.py"), *expected[2:]]
    prepared[prepared.index("-o") + 1] = str(generated)
    subprocess.run(prepared, cwd=build, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"), check=True, timeout=60)
    shared_group = group + "_shared"
    shared = subprocess.check_output(
        ["ninja", "-C", str(build), "-t", "commands", "-s",
         "gen/" + directory + "/" + names[0] + ".mojom-features.h"], text=True)
    expected_shared = expected[:12] + ["--filelist=gen/" + shared_group + "__generator.rsp",
        "--generate_non_variant_code", "-g", "c++"]
    if exported:
        expected_shared += ["--export_attribute", "COMPONENT_EXPORT(MOJOM_SHARED_CONTENT_EXPORT)",
                            "--export_header", "base/component_export.h"]
    if shlex.split(shared) != expected_shared or (build / ("gen/" + shared_group + "__generator.rsp")).read_text().splitlines() != files:
        raise RuntimeError("Content Mojo shared generator rule changed")
    prepared = [str(source / "third_party/cpython3/host/bin/python3"), "-B",
                str(source / "mojo/public/tools/bindings/mojom_bindings_generator.py"), *expected_shared[2:]]
    prepared[prepared.index("-o") + 1] = str(generated)
    subprocess.run(prepared, cwd=build, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"), check=True, timeout=60)
    print(json.dumps({"isolatedMojoFamily": family, "modules": len(names)}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--command-gn-report", type=Path,
                        help="Use matching private staged GN commands, including new translation units")
    parser.add_argument("--target", choices=[Path(name).stem for name in TARGETS], action="append")
    args = parser.parse_args()
    source, build, stage = (value.resolve(strict=True) for value in (args.source, args.build_dir, args.stage))
    cache = Path(os.environ["LANDU_TEST_CACHE"]).resolve(strict=True)
    if cache == source or cache.is_relative_to(source) or str(cache).startswith("/mnt/c/"):
        raise RuntimeError("Use a separate non-C cache directory")
    if not build.is_relative_to(source):
        raise RuntimeError("Build directory must belong to the pinned source checkout")
    manifest = json.loads((stage / "manifest.json").read_text(encoding="utf-8"))
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != manifest["revision"]:
        raise RuntimeError("Staged revision differs from build checkout")
    for item in manifest["files"]:
        file = stage / "files" / item["path"]
        if not file.resolve(strict=True).is_relative_to(stage / "files") or digest(file) != item["sha256"]:
            raise RuntimeError("Staged payload changed")
        if item["originalSha256"] is not None and digest(source / item["path"]) != item["originalSha256"]:
            raise RuntimeError("Build source changed since staging")
    for item in manifest.get("behaviorSources", []):
        file = source / item["path"]
        if not file.resolve(strict=True).is_relative_to(source) or digest(file) != item["sha256"]:
            raise RuntimeError("Backend behavior source changed since staging")
    output = Path(tempfile.mkdtemp(prefix="native-overlay-check-", dir=cache))
    command_build, command_source = build, source
    command_graph_sha = None
    if args.command_gn_report:
        graph_path = args.command_gn_report.resolve(strict=True)
        graph = json.loads(graph_path.read_text())
        if (graph.get("kind") != "whole-staged-gn-graph-private-source-overlay"
                or not graph.get("passed") or not graph.get("inputsStable")
                or graph.get("stageManifestSha256") != digest(stage / "manifest.json")
                or any(digest(Path(name)) != expected for name, expected in graph["inputFiles"].items())):
            raise RuntimeError("Use a passing private GN graph for this exact stage")
        command_build, command_source = Path(graph["buildDirectory"]), Path(graph["sourceOverlay"])
        if ((command_source / "out").is_symlink() or command_build.resolve().is_relative_to(source)
                or digest(command_build / "args.gn") != digest(build / "args.gn")):
            raise RuntimeError("Staged command graph does not match baseline arguments")
        command_graph_sha = digest(graph_path)
    print(json.dumps({"preparing": "buildflags", "output": str(output)}), flush=True)
    generated = prepare_blink_buildflags(source, build, output)
    selected = {name: target for name, target in TARGETS.items()
                if not args.target or Path(name).stem in args.target}
    staged_entries = {item['path']: item for item in manifest['files']}
    if not args.command_gn_report and any(staged_entries[name]['originalSha256'] is None for name in selected):
        raise RuntimeError('New native sources require --command-gn-report for their actual GN target commands')
    canvas_targets = ("image_data_buffer", "canvas_async_blob_creator", "html_canvas_element", "base_rendering_context_2d")
    gpu_targets = ("media_device_info", "input_device_info", "media_stream_track_impl", "element", "range", "offline_audio_context", "analyser_node", "webgl_rendering_context_base", "webgl2_rendering_context_base", "gpu_adapter")
    if any(Path(name).stem in ("font_platform_data_cache", "timezone_controller", *canvas_targets, *gpu_targets) for name in selected):
        print(json.dumps({"preparing": "pinned Blink generated headers"}), flush=True)
        prepare_font_headers(source, build, generated)
    if any(Path(name).stem in ("media_devices_util", "permission_context_base", "landu_geolocation_provider", "chrome_content_browser_client", "profile_manager_utils", "tab_state_storage_service_android", "storage_collection_synchronizer_android", "render_process_host_impl", "timezone_controller", *canvas_targets, *gpu_targets) for name in selected):
        print(json.dumps({"preparing": "Android buildflags"}), flush=True)
        prepare_android_buildflags(source, build, output, generated)
    if any(Path(name).stem == "profile_manager_utils" for name in selected):
        prepare_profile_jni(source, build, stage, generated)
    if any(Path(name).stem in ("profile_manager_utils", "render_process_host_impl") for name in selected):
        prepare_fixed_mojo_headers(source, build, generated, family="storage-public")
        prepare_fixed_mojo_headers(source, build, generated, family="storage-privileged")
    if any(Path(name).stem in ("tab_state_storage_service_android", "storage_collection_synchronizer_android") for name in selected):
        prepare_profile_jni(source, build, stage, generated, tab=True)
    if any(Path(name).stem in ("tab_state_storage_service_android", "storage_collection_synchronizer_android",
                              "render_process_host_impl") for name in selected):
        prepare_fixed_mojo_headers(source, build, generated)
    if any(Path(name).stem == "render_process_host_impl" for name in selected):
        prepare_fixed_mojo_headers(source, build, generated, family="renderer")
    compiler = source / "third_party/llvm-build/Release+Asserts/bin/clang++"
    checks = []
    generated_inputs = {}
    mojo = None
    mojo_failure = None
    initial_manifest_sha = digest(stage / "manifest.json")
    for relative, target in selected.items():
        command = subprocess.check_output(
            ["ninja", "-C", str(command_build), "-t", "commands", "-s", target], text=True)
        lines = [line for line in command.splitlines() if line.strip()]
        if len(lines) != 1:
            raise RuntimeError("Expected one compiler command, without wrapper commands")
        original = shlex.split(lines[0])
        if (command_build / original[0]).resolve() != compiler.resolve():
            raise RuntimeError("Unexpected compiler; refusing to execute command")
        for argument in original:
            if argument.startswith(('-fmodule-map-file=', '-fmodule-file=')):
                name = argument.split('=', 1)[1]
                if argument.startswith('-fmodule-file=') and '=' in name:
                    name = name.split('=', 1)[1]
                path = (build / name).resolve(strict=True)
                if not path.is_relative_to(source) or path.stat().st_size == 0:
                    raise RuntimeError('Original generated module input is not ready: ' + name)
                if str(path) not in generated_inputs:
                    generated_inputs[str(path)] = digest(path)
        prepared = [str(compiler), "-I" + str(stage / "files"), "-I" + str(generated),
                    "-I" + str(generated / "jni_headers"), "-I" + str(build / "gen"),
                    "-I" + str(source)]
        iterator = iter(original[1:])
        replaced_source = False
        for argument in iterator:
            if argument in ("-MF", "-MT", "-MQ", "-o"):
                next(iterator)
            elif argument in ("-MMD", "-MD", "-MP"):
                continue
            elif argument == "-c":
                prepared.append("-fsyntax-only")
            elif argument.startswith("-fcrash-diagnostics-dir="):
                prepared.append("-fcrash-diagnostics-dir=" + str(output))
            elif argument.startswith("-fmodules-cache-path="):
                prepared.append("-fmodules-cache-path=" + str(output / "modules"))
            elif argument.endswith(".cc") and (command_build / argument).resolve() == (command_source / relative).resolve():
                prepared.append(str(stage / "files" / relative))
                replaced_source = True
            else:
                prepared.append(argument)
        if not replaced_source or "-fsyntax-only" not in prepared:
            raise RuntimeError("Cannot isolate the compiler command safely")
        attempts = []
        missing_headers = []
        for attempt in range(13):
            log = output / (Path(relative).stem + ".attempt-" + str(attempt) + ".log")
            with log.open("w", encoding="utf-8") as stream:
                # Keep original relative include/module paths tied to the live
                # build's real generated inputs. Stage headers and generated
                # private headers remain first; syntax-only emits no objects.
                result = subprocess.run(prepared, cwd=build, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
            attempts.append({"exitCode": result.returncode, "log": str(log)})
            if result.returncode == 0:
                break
            diagnostic = re.sub(r"\x1b\[[0-9;]*m", "", log.read_text())
            missing = re.search(r"fatal error: '([^'\n]+\.mojom(?:-[^'/\n]+)?\.h)' file not found", diagnostic)
            proto = re.search(r"fatal error: '([^'\n]+\.pb\.h)' file not found", diagnostic)
            proto_header = proto[1] if proto else None
            if not missing and not proto_header:
                for error_file in re.findall(r"^([^\n]+?):\d+:\d+: error:", diagnostic, re.MULTILINE):
                    error_path = (build / error_file).resolve()
                    if (not error_path.is_file() or not
                            (error_path.is_relative_to(source) or error_path.is_relative_to(stage))):
                        continue
                    for name in re.findall(r'^\s*#\s*include\s+"([^"]+\.pb\.h)"',
                                           error_path.read_text(), re.MULTILINE):
                        local = generated / name
                        baseline = build / 'gen' / name
                        if (not local.is_file() and (not baseline.is_file() or baseline.stat().st_size == 0)):
                            proto_header = name
                            break
                    if proto_header:
                        break
            requested = missing[1] if missing else proto_header
            if not requested or requested in missing_headers or attempt == 12:
                break
            if any(item["path"].endswith(".mojom") for item in manifest["files"]):
                raise RuntimeError("Regenerate modified Mojo definitions from a staged build graph")
            missing_headers.append(requested)
            print(json.dumps({"preparingMissingGeneratedHeader": requested, "attempt": attempt}), flush=True)
            try:
                if mojo is None:
                    mojo = IsolatedMojo(source, build, generated)
                if proto_header:
                    mojo.prepare_proto_header(proto_header)
                else:
                    mojo.prepare_header(requested)
            except Exception as error:
                mojo_failure = str(error)
                print(json.dumps({"mojoGenerationFailure": mojo_failure}), flush=True)
                break
        checks.append({"source": relative, "exitCode": result.returncode, "log": str(log),
                       "attempts": attempts, "preparedMissingMojoHeaders": missing_headers,
                       "command": prepared, "workingDirectory": str(build)})
        print(json.dumps({k: v for k, v in checks[-1].items() if k != 'command'}), flush=True)
    mojo_report, mojo_passed = mojo.report(mojo_failure) if mojo else (None, True)
    stable = (digest(stage / "manifest.json") == initial_manifest_sha
              and all(digest(stage / "files" / item["path"]) == item["sha256"] for item in manifest["files"])
              and all(digest(Path(name)) == expected for name, expected in generated_inputs.items()))
    report = {"revision": revision, "stageManifestSha256": digest(stage / "manifest.json"),
              "buildArgsSha256": digest(build / "args.gn"), "syntaxOnly": True, "checks": checks,
              "passed": stable and mojo_passed and all(check["exitCode"] == 0 for check in checks),
              "stageInputsStable": stable, "mojoReport": str(mojo_report) if mojo_report else None,
              "commandBuildDirectory": str(command_build), "commandGraphSha256": command_graph_sha,
              "originalGeneratedModuleInputs": generated_inputs,
              "chromiumCheckoutModified": False, "apkBuilt": False, "deviceTested": False}
    (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(output / "report.json"), "passed": report["passed"]}), flush=True)
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
