# Item 28 (2026-11-05): run spirv-opt over every shader embedded in parallel-RDP's slangmosh.hpp and write a new
# header with the optimized SPIR-V and the new offsets/sizes. Usage: python -I spvopt.py <in.hpp> <out.hpp> <flag>...
# Offsets in the header are in words, sizes in bytes. Each optimized blob is checked with spirv-val (Vulkan 1.1);
# a blob that fails to optimize or validate keeps its original SPIR-V (reported).
import re, subprocess, sys, tempfile, os

NDK_TOOLS = r"C:\Users\Joey\AppData\Local\Android\Sdk\ndk\27.3.13750724\shader-tools\windows-x86_64"
OPT = os.path.join(NDK_TOOLS, "spirv-opt.exe")
VAL = os.path.join(NDK_TOOLS, "spirv-val.exe")

src, dst, flags = sys.argv[1], sys.argv[2], sys.argv[3:]
text = open(src, "rb").read().decode()
m = re.search(r"static const uint32_t spirv_bank\[\] =\s*\{(.*?)\};", text, re.S)
words = [int(w, 16) for w in re.findall(r"0x([0-9a-fA-F]+)u", m.group(1))]
calls = [(mm.group(0), int(mm.group(2)), int(mm.group(3)))
         for mm in re.finditer(r"request_(program|shader)\(spirv_bank \+ (\d+), (\d+)", text)]

tmp = tempfile.mkdtemp(prefix="spvopt")
new_words, remap = [], {}
before = after = kept = 0
for i, (call, off, size) in enumerate(calls):
    if (off, size) in remap:
        continue
    blob = words[off:off + size // 4]
    inp, out = os.path.join(tmp, f"{i}.spv"), os.path.join(tmp, f"{i}.opt.spv")
    with open(inp, "wb") as f:
        f.write(b"".join(w.to_bytes(4, "little") for w in blob))
    ok = subprocess.run([OPT, "--target-env=vulkan1.1", *flags, inp, "-o", out], capture_output=True, text=True)
    res = blob
    if ok.returncode == 0:
        val = subprocess.run([VAL, "--target-env", "vulkan1.1", out], capture_output=True, text=True)
        if val.returncode == 0:
            data = open(out, "rb").read()
            res = [int.from_bytes(data[j:j + 4], "little") for j in range(0, len(data), 4)]
        else:
            print(f"blob {i} @{off}: spirv-val failed, kept original: {val.stdout.strip()[:200]} {val.stderr.strip()[:200]}")
            kept += 1
    else:
        print(f"blob {i} @{off}: spirv-opt failed, kept original: {ok.stderr.strip()[:300]}")
        kept += 1
    remap[(off, size)] = (len(new_words), len(res) * 4)
    before += size; after += len(res) * 4
    new_words += res

def fix(mm):
    off, size = int(mm.group(2)), int(mm.group(3))
    no, ns = remap[(off, size)]
    return f"request_{mm.group(1)}(spirv_bank + {no}, {ns}"
text = re.sub(r"request_(program|shader)\(spirv_bank \+ (\d+), (\d+)", fix, text)
lines = []
for j in range(0, len(new_words), 8):
    lines.append("\t" + ", ".join(f"0x{w:08x}u" for w in new_words[j:j + 8]) + ",")
bank = "static const uint32_t spirv_bank[] =\n{\n" + "\n".join(lines) + "\n};"
text = text[:m.start()] + bank + text[m.end():]
open(dst, "wb").write(text.encode())
print(f"{len(remap)} shaders, {before} -> {after} bytes ({100.0 * after / before:.1f}%), {kept} kept as they were; flags {' '.join(flags)}")
