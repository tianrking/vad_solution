#!/usr/bin/env python3
"""Verify every ELF PT_LOAD segment in an APK is compatible with 16 KiB pages."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile


PT_LOAD = 1
MIN_ALIGNMENT = 16 * 1024


def load_alignments(data: bytes) -> list[int]:
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = data[5]
    endian = "<" if byte_order == 1 else ">" if byte_order == 2 else None
    if endian is None:
        raise ValueError("unsupported ELF byte order")

    if elf_class == 1:
        ph_offset = struct.unpack_from(endian + "I", data, 28)[0]
        ph_entry_size = struct.unpack_from(endian + "H", data, 42)[0]
        ph_count = struct.unpack_from(endian + "H", data, 44)[0]
        type_offset, align_offset, align_format = 0, 28, "I"
    elif elf_class == 2:
        ph_offset = struct.unpack_from(endian + "Q", data, 32)[0]
        ph_entry_size = struct.unpack_from(endian + "H", data, 54)[0]
        ph_count = struct.unpack_from(endian + "H", data, 56)[0]
        type_offset, align_offset, align_format = 0, 48, "Q"
    else:
        raise ValueError("unsupported ELF class")

    result: list[int] = []
    for index in range(ph_count):
        entry = ph_offset + index * ph_entry_size
        segment_type = struct.unpack_from(endian + "I", data, entry + type_offset)[0]
        if segment_type == PT_LOAD:
            result.append(struct.unpack_from(endian + align_format, data, entry + align_offset)[0])
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()
    failed = False
    with zipfile.ZipFile(args.apk) as apk:
        names = sorted(name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so"))
        if not names:
            print("FAIL: APK contains no native libraries")
            return 1
        for name in names:
            alignments = load_alignments(apk.read(name))
            compatible = bool(alignments) and min(alignments) >= MIN_ALIGNMENT
            status = "PASS" if compatible else "FAIL"
            formatted = ", ".join(f"0x{value:x}" for value in alignments)
            print(f"{status} {name}: PT_LOAD p_align=[{formatted}]")
            failed |= not compatible
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
