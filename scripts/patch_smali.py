#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 apktool 反编译目录中 NetworkManager 的两个分区取数方法，
替换为转发到 com.acac.fix.Patch 的一行调用。

    python3 scripts/patch_smali.py build/decoded

改动仅限这两个方法的实现体，方法签名、注解、类结构、其它业务代码一律不动，
因此原 APK 的其它逻辑（推荐页、详情页、播放、弹幕）完全不受影响。
"""

import os
import sys

TARGET = os.path.join("smali", "com", "xx", "afaf", "network", "NetworkManager.smali")

MARK = "Lcom/acac/fix/Patch;->"

# header: smali 方法声明行; sig: 泛型签名注解里的类型串; call: 转发语句
PATCHES = [
    {
        "header": ".method public final getCategory(ILo9/a;)V",
        "sig": ["(I", "Lo9/a;", ")V"],
        "call": "invoke-static {p1, p2}, Lcom/acac/fix/Patch;->category(ILo9/a;)V",
    },
    {
        "header": ".method public final getCategoryDetail(IILo9/a;)V",
        "sig": ["(II", "Lo9/a;", ")V"],
        "call": "invoke-static {p1, p2, p3}, Lcom/acac/fix/Patch;->categoryDetail(IILo9/a;)V",
    },
]


def build_body(p):
    sig = "\n".join('            "%s",' % s for s in p["sig"])
    # 去掉最后一行多出来的逗号
    sig = sig.rstrip(",")
    return (
        "\n"
        "    .locals 0\n"
        "    .annotation system Ldalvik/annotation/Signature;\n"
        "        value = {\n"
        "%s\n"
        "        }\n"
        "    .end annotation\n"
        "\n"
        "    %s\n"
        "\n"
        "    return-void\n" % (sig, p["call"])
    )


def patch_one(src, p):
    header = p["header"]
    start = src.find(header)
    if start < 0:
        raise SystemExit("找不到方法: %s" % header)

    end = src.find(".end method", start)
    if end < 0:
        raise SystemExit("方法未正常结束: %s" % header)
    end += len(".end method")

    if MARK in src[start:end]:
        return src, False

    new = header + build_body(p) + ".end method"
    return src[:start] + new + src[end:], True


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)

    root = sys.argv[1]
    path = os.path.join(root, TARGET)
    if not os.path.isfile(path):
        raise SystemExit("找不到 %s（先执行 apktool d 反编译原始 APK）" % path)

    src = open(path, encoding="utf-8").read()
    changed = 0
    for p in PATCHES:
        src, ok = patch_one(src, p)
        changed += 1 if ok else 0
        print(("  patched  " if ok else "  skipped  ") + p["header"])

    if changed:
        open(path, "w", encoding="utf-8", newline="\n").write(src)
    print("完成，改动方法数 = %d" % changed)


if __name__ == "__main__":
    main()
