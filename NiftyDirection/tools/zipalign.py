#!/usr/bin/env python3
"""Minimal zipalign: stored (uncompressed) entries start on 4-byte boundaries (.so on 4096). Usage: zipalign.py in.apk out.apk"""
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with open(dst, 'wb') as f:
    zout = zipfile.ZipFile(f, 'w')
    for info in zin.infolist():
        data = zin.read(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        if ni.compress_type == zipfile.ZIP_STORED:
            align = 4096 if info.filename.endswith('.so') else 4
            # local header = 30 + name + extra; pad extra so data offset is aligned
            off = f.tell() + 30 + len(ni.filename.encode())
            pad = (align - off % align) % align
            ni.extra = b'\x00' * pad
        zout.writestr(ni, data)
    zout.close()
