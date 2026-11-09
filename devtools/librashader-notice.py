"""Writes assets/licenses/librashader.txt: librashader's own notice, its MIT header, and every Rust crate built into
liblibrashader_capi.so (devtools/build-librashader.sh) with that crate's licence text, plus the C++ libraries two crates
bundle (glslang, SPIRV-Cross). Usage: python devtools/librashader-notice.py [librashader dir]"""
import glob, os, re, subprocess, sys

MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""

SRC = sys.argv[1] if len(sys.argv) > 1 else "C:/GithubProjects/librashader"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/assets/licenses/librashader.txt")
REG = glob.glob(os.path.expanduser("~/.cargo/registry/src/*"))
RULE = "=" * 72


def crate_dir(name, ver):
    for r in REG:
        g = glob.glob(os.path.join(r, f"{name}-{ver}")) + glob.glob(os.path.join(r, f"{name}-{ver}+*"))
        if g:
            return g[0]
    return None


def read(path):
    return open(path, encoding="utf-8", errors="replace").read().strip()


def native(pattern, title):
    for r in REG:
        g = glob.glob(os.path.join(r, pattern))
        if g:
            return f"Bundled {title}:\n{read(g[0])}\n"
    raise SystemExit("missing native licence " + pattern)


NATIVE = {
    "glslang-sys": native("glslang-sys-0.8.1+*/native/glslang/LICENSE.txt", "glslang (Khronos)"),
    "spirv-cross-sys": native("spirv-cross-sys-0.7.2+*/native/SPIRV-Cross/LICENSE", "SPIRV-Cross (Khronos), Apache 2.0")
    + native("spirv-cross-sys-0.7.2+*/native/SPIRV-Cross/LICENSES/LicenseRef-KhronosFreeUse.txt",
             "SPIRV-Cross files under the Khronos free-use licence"),
}

ORDER = [r"(LICENSES/)?(LICEN[CS]E[-_.])?MIT", r"LICEN[CS]E[-_.]ZLIB", r"LICEN[CS]E[-_.]BSD", r"COPYING",
         r"LICEN[CS]E(\.md|\.txt)?$", r"LICEN[CS]E[-_.]?(APACHE|A2)"]


def licence_file(d):
    files = sorted(os.listdir(d))
    if os.path.isdir(os.path.join(d, "LICENSES")):
        files = ["LICENSES/" + f for f in sorted(os.listdir(os.path.join(d, "LICENSES")))] + files
    for pat in ORDER:
        for f in files:
            if re.match(pat, f, re.I) and os.path.isfile(os.path.join(d, f)):
                return f, read(os.path.join(d, f))
    return None


def authors(d):
    m = re.search(r'^authors\s*=\s*\[(.*?)\]', open(os.path.join(d, "Cargo.toml"), encoding="utf-8").read(), re.M | re.S)
    if not m:
        return ""
    names = [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]
    return ", ".join(re.sub(r"\s*<[^>]*>", "", n) for n in names)


crates = {}
tree = subprocess.run(["cargo", "tree", "--offline", "-p", "librashader-capi", "--target", "aarch64-linux-android",
                       "--no-default-features", "--features", "runtime-vulkan,runtime-opengl", "-e", "normal",
                       "--prefix", "none", "-f", "{p}|{l}"], cwd=SRC, capture_output=True, text=True, check=True).stdout
for line in tree.splitlines():
    line = line.strip().replace(" (*)", "")
    if "|" not in line:
        continue
    p, lic = line.split("|", 1)
    parts = p.split()
    crates[(parts[0], parts[1].lstrip("v"))] = lic.strip()

out = ["librashader (github.com/SnowflakePowered/librashader), v0.12.1\n"
       "Copyright 2022 chyyran and contributors.\n"
       "Offered under the Mozilla Public License 2.0 or GPL 3.0; Joey64 uses it under the Mozilla Public\n"
       "License 2.0 (mpl-2.0.txt). Its source code is at the address above.\n\n"
       "librashader.h, the C header, is MIT:\n"]
hdr = read(os.path.join(SRC, "include/librashader.h"))
out.append(hdr[hdr.index("Copyright 2022"):hdr.index("*/")].strip() + "\n")
out.append(f"\n{RULE}\nRust crates built into librashader for Joey64\n{RULE}\n")
no_file = []
for (name, ver), lic in sorted(crates.items()):
    if name.startswith("librashader"):
        continue
    out.append(f"\n---- {name} {ver} ({lic})\n")
    d = crate_dir(name, ver)
    if d is None:
        raise SystemExit(f"crate not found: {name} {ver}")
    out.append(NATIVE.get(name, ""))
    t = licence_file(d)
    if t is None:
        no_file.append(name)
        who = authors(d)
        note = " (the MIT text is at the end)" if "MIT" in lic else " (apache-2.0.txt)" if "Apache" in lic else ""
        out.append((f"Copyright {who}. " if who else "") + "Licence: " + lic + note + "\n")
        continue
    f, text = t
    if re.search(r"APACHE|A2", f, re.I) or ("Apache License" in text[:300] and "Version 2.0" in text[:500]):
        out.append("Apache License 2.0 (apache-2.0.txt).\n")
    elif "Mozilla Public License" in text[:300]:
        out.append("Mozilla Public License 2.0 (mpl-2.0.txt).\n")
    else:
        out.append(text + "\n")
out.append(f"\n{RULE}\nThe MIT licence, for the crates above that name it without a text of their own\n{RULE}\n")
out.append(MIT)
open(OUT, "w", encoding="utf-8", newline="\n").write("".join(out))
print(len(crates), "crates; no licence file:", no_file)
