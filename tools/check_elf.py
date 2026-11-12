#!/usr/bin/env python3
"""Gate: every native lib in an APK (or dirs) must only need libraries that exist on Android.

Usage: check_elf.py <apk-or-dir> [...]
Fails (exit 1) on any DT_NEEDED that is not an Android NDK system library and not bundled in the
same ABI dir (e.g. libz.so.1, libc.so.6, libpthread.so.0), and on executables whose interpreter
is not the bionic linker.
"""
import io, os, sys, zipfile
from elftools.elf.elffile import ELFFile
from elftools.elf.dynamic import DynamicSection

ANDROID_SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libjnigraphics.so",
    "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libOpenMAXAL.so",
    "libmediandk.so", "libvulkan.so", "libnativewindow.so", "libcamera2ndk.so", "libaaudio.so",
    "libneuralnetworks.so", "libsync.so", "libbinder_ndk.so", "libicu.so", "libamidi.so",
}


def inspect(name, data):
    elf = ELFFile(io.BytesIO(data))
    needed, interp = [], None
    for seg in elf.iter_segments():
        if seg["p_type"] == "PT_INTERP":
            interp = seg.get_interp_name()
    for sec in elf.iter_sections():
        if isinstance(sec, DynamicSection):
            needed += [t.needed for t in sec.iter_tags() if t.entry.d_tag == "DT_NEEDED"]
    return needed, interp


def groups(path):
    """{abi: {libname: bytes}}"""
    out = {}
    if path.endswith(".apk"):
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.startswith("lib/") and n.endswith(".so"):
                    _, abi, lib = n.split("/", 2)
                    out.setdefault(abi, {})[lib] = z.read(n)
    else:
        for abi in sorted(os.listdir(path)):
            d = os.path.join(path, abi)
            if os.path.isdir(d):
                for lib in os.listdir(d):
                    if lib.endswith(".so"):
                        out.setdefault(abi, {})[lib] = open(os.path.join(d, lib), "rb").read()
    return out


def main(paths):
    bad = 0
    for p in paths:
        for abi, libs in groups(p).items():
            for lib, data in sorted(libs.items()):
                needed, interp = inspect(lib, data)
                errs = [n for n in needed if n not in ANDROID_SYSTEM and n not in libs]
                if interp and not interp.startswith("/system/bin/linker"):
                    errs.append("interp=" + interp)
                status = "FAIL " + ",".join(errs) if errs else "ok"
                print(f"{os.path.basename(p)} {abi}/{lib}: NEEDED={','.join(needed)} interp={interp or '-'} -> {status}")
                bad += bool(errs)
    if bad:
        print(f"ELF CHECK FAILED: {bad} lib(s) need libraries Android does not have")
        return 1
    print("ELF CHECK OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
