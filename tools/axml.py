#!/usr/bin/env python3
"""最小 AXML（二进制 AndroidManifest）读写：往 <application> 里加一个 <activity>。

只做需要的部分：解析字符串池与元素链，追加字符串，插入元素，重写文件头。
"""
import struct

XML_TYPE = 0x0003
POOL_TYPE = 0x0001
MAP_TYPE = 0x0180
START_NS = 0x0100
END_NS = 0x0101
START_EL = 0x0102
END_EL = 0x0103

ATTR_STRING = 0x03
ATTR_INT = 0x10
ATTR_BOOL = 0x12


class Axml:
    def __init__(self, data: bytes):
        self.data = data
        self.xml_type, self.xml_hsize, self.xml_size = struct.unpack_from("<HHI", data, 0)
        assert self.xml_type == XML_TYPE, "不是 AXML"
        self.pool_off = 8
        self.parse_pool()
        self.body = data[self.body_off:]  # 资源映射 + 元素

    def parse_pool(self):
        d = self.data
        t, hsz, size, count, scount, flags, sstart, sstyle = struct.unpack_from(
            "<HHIIIIII", d, self.pool_off
        )
        assert t == POOL_TYPE, "字符串池不对"
        self.pool_hsz = hsz
        self.pool_size = size
        self.pool_flags = flags
        self.utf8 = bool(flags & 0x100)
        offs = struct.unpack_from("<%dI" % count, d, self.pool_off + hsz)
        base = self.pool_off + sstart
        strings = []
        for off in offs:
            p = base + off
            if self.utf8:
                # u8 长度（utf16 长度）+ u8 字节长度
                _, p = self.read_u8len(d, p)
                blen, p = self.read_u8len(d, p)
                strings.append(d[p : p + blen].decode("utf-8"))
            else:
                ln, p = self.read_u16len(d, p)
                strings.append(d[p : p + ln * 2].decode("utf-16-le"))
        self.strings = strings
        self.body_off = self.pool_off + size

    @staticmethod
    def read_u16len(d, p):
        ln = struct.unpack_from("<H", d, p)[0]
        return ln, p + 2

    @staticmethod
    def read_u8len(d, p):
        # 兼容 0x80 高位的长格式
        b = d[p]
        if b & 0x80:
            ln = ((b & 0x7F) << 8) | d[p + 1]
            return ln, p + 2
        return b, p + 1

    def idx(self, s):
        if s in self.strings:
            return self.strings.index(s)
        self.strings.append(s)
        return len(self.strings) - 1

    def build_pool(self):
        body = bytearray()
        offsets = []
        for s in self.strings:
            offsets.append(len(body))
            if self.utf8:
                b = s.encode("utf-8")
                body.append(len(b))  # 简化：不做长格式
                body += b
                body.append(0)
            else:
                body += struct.pack("<H", len(s))
                body += s.encode("utf-16-le")
                body += b"\x00\x00"
        while len(body) % 4:
            body.append(0)
        count = len(self.strings)
        header = bytearray()
        header += struct.pack("<HHI", POOL_TYPE, 28, 28 + 4 * count + len(body))
        header += struct.pack("<IIIII", count, 0, self.pool_flags, 28 + 4 * count, 0)
        header += struct.pack("<%dI" % count, *offsets)
        return bytes(header) + bytes(body)

    def build_start(self, name_idx, attrs, line=1, ns_idx=0xFFFFFFFF):
        out = bytearray()
        attr_data = bytearray()
        for ns, aname, raw, dtype, data in attrs:
            attr_data += struct.pack("<III", ns, aname, raw)
            attr_data += struct.pack("<HBBI", 8, 0, dtype, data)
        size = 16 + 20 + len(attr_data)
        out += struct.pack("<HHI", START_EL, 16, size)
        out += struct.pack("<II", line, 0xFFFFFFFF)
        out += struct.pack("<II", ns_idx, name_idx)
        out += struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0)
        out += attr_data
        return bytes(out)

    def build_end(self, name_idx, line=1):
        return struct.pack("<HHIIIII", END_EL, 16, 24, line, 0xFFFFFFFF, 0xFFFFFFFF, name_idx)

    def to_bytes(self):
        pool = self.build_pool()
        total = 8 + len(pool) + len(self.body)
        return struct.pack("<HHI", XML_TYPE, 8, total) + pool + self.body
