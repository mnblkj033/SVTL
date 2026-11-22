#!/usr/bin/env python3
"""把启动器 Activity 写进游戏的 AndroidManifest.xml（二进制 AXML），
并摘掉游戏自己的启动图标。纯 Python，不依赖 aapt/apktool。
"""
import struct
import sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
from axml import Axml, START_EL, END_EL, ATTR_STRING, ATTR_INT, ATTR_BOOL

NS_ANDROID = 49
IDX_NAME = 3
IDX_LABEL = 1
IDX_EXPORTED = 4
IDX_SCREEN_ORIENTATION = 8


def chunks(body):
    """返回 [(offset, type, size, name_index or None)]"""
    out = []
    p = 0
    while p + 8 <= len(body):
        t, hsz, size = struct.unpack_from("<HHI", body, p)
        name = None
        if t in (START_EL, END_EL):
            name = struct.unpack_from("<I", body, p + 20)[0]
        out.append((p, t, size, name))
        if size <= 0:
            break
        p += size
    return out


def patch(data: bytes) -> bytes:
    a = Axml(data)
    body = a.body
    cs = chunks(body)

    # 1) 找到游戏 Activity 里的 intent-filter（MAIN/LAUNCHER），删掉
    remove = None
    depth_activity = None
    for i, (off, t, size, name) in enumerate(cs):
        nm = a.strings[name] if name is not None and name < len(a.strings) else ""
        if t == START_EL and nm == "activity":
            # 找它下面的 intent-filter
            j = i + 1
            level = 1
            while j < len(cs):
                o2, t2, s2, n2 = cs[j]
                nm2 = a.strings[n2] if n2 is not None and n2 < len(a.strings) else ""
                if t2 == START_EL:
                    if nm2 == "intent-filter" and level == 1:
                        start = o2
                        end = None
                        k = j
                        d = 0
                        while k < len(cs):
                            _, t3, s3, n3 = cs[k]
                            if t3 == START_EL:
                                d += 1
                            elif t3 == END_EL:
                                d -= 1
                                if d == 0:
                                    end = cs[k][0] + s3
                                    break
                            k += 1
                        if end:
                            remove = (start, end)
                    level += 1
                elif t2 == END_EL:
                    level -= 1
                    if level == 0:
                        break
                j += 1
        if remove:
            break

    if remove:
        body = body[: remove[0]] + body[remove[1]:]

    # 2) 追加需要的字符串
    s_cls = a.idx("com.svtl.launcher.LauncherActivity")
    s_label = a.idx("SVTL")

    # 3) 构造 activity 元素
    attrs = [
        (NS_ANDROID, IDX_NAME, s_cls, ATTR_STRING, s_cls),
        (NS_ANDROID, IDX_EXPORTED, 0xFFFFFFFF, ATTR_BOOL, 0xFFFFFFFF),
        (NS_ANDROID, IDX_LABEL, s_label, ATTR_STRING, s_label),
        (NS_ANDROID, IDX_SCREEN_ORIENTATION, 0xFFFFFFFF, ATTR_INT, 1),
    ]
    el = bytearray()
    el += a.build_start(a.idx("activity"), attrs)
    el += a.build_start(a.idx("intent-filter"), [])
    el += a.build_start(a.idx("action"), [(NS_ANDROID, IDX_NAME, 33, ATTR_STRING, 33)])
    el += a.build_end(a.idx("action"))
    el += a.build_start(a.idx("category"), [(NS_ANDROID, IDX_NAME, 34, ATTR_STRING, 34)])
    el += a.build_end(a.idx("category"))
    el += a.build_end(a.idx("intent-filter"))
    el += a.build_end(a.idx("activity"))

    # 4) 插到 </application> 前
    insert_at = None
    for off, t, size, name in chunks(body):
        nm = a.strings[name] if name is not None and name < len(a.strings) else ""
        if t == END_EL and nm == "application":
            insert_at = off
    assert insert_at is not None, "找不到 </application>"
    body = body[:insert_at] + bytes(el) + body[insert_at:]
    a.body = body

    # 5) 保证字符串索引有效后再生成池
    return a.to_bytes()


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    out = patch(open(src, "rb").read())
    open(dst, "wb").write(out)
    print(f"{src} -> {dst}  {len(out)} 字节")
