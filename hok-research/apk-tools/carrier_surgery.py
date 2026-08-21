"""
ELF surgery: transform paradise.ko into a minimal tss_poc_carrier.ko that
loads on OPlus ColorOS 6.1.75 device, by:

1. Patching init_module @ .init.text+0x4 (file off 0xaa00) to MOV W0,#0; RET
   - This makes init_module return success without calling cfi_bypass etc.
2. Patching cleanup_module @ .exit.text+0x4 (file off 0xaaac) to MOV W0,#0; RET
3. Replacing .modinfo content with:
   - vermagic = device's exact vermagic
   - name    = tss_poc
4. Resizing .modinfo section (delta = new_size - old_size = +25 bytes typically),
   shifting all following sections' file offsets, updating section header table
   and ELF e_shoff.
5. .gnu.linkonce.this_module's struct module "paradise" name string ALSO needs
   updating to "tss_poc" (same length or shorter, no surgery).

Output: D:\ctf\_root\tss_poc_carrier.ko ready to push + insmod.
"""
import struct, sys

IN  = r'D:\ctf\_root\paradise_6.1.ko'
OUT = r'D:\ctf\_root\tss_poc_carrier.ko'

DEV_VERMAGIC = "6.1.75-android14-11-o-g9c1e58b386f5 SMP preempt mod_unload modversions aarch64"
NEW_MODNAME  = "tss_poc"   # max 8 chars to fit "paradise" slot in struct module

# ARM64 BTI c (Branch Target Identifier 'call' landing pad) + MOV W0,#0 + RET
# OPlus 6.1.75 enables BTI on kernel .text; the first instruction of any
# function reached via BLR must be a BTI. We preserve the original BTI and
# only replace the prolog (STP/SUB/etc) with MOV+RET to make the function
# return success immediately.
PATCH_CODE = bytes([
    0x3f, 0x23, 0x03, 0xd5,   # BTI c    (HINT #0x21)
    0x00, 0x00, 0x80, 0x52,   # MOV W0, #0
    0xc0, 0x03, 0x5f, 0xd6,   # RET
])

def patch_modinfo(data: bytearray, modinfo_off: int, modinfo_old_size: int):
    """Build new .modinfo content. Returns (new_data: bytes, delta: int)."""
    old = data[modinfo_off:modinfo_off + modinfo_old_size]
    fields = [f for f in old.split(b'\x00') if f]
    print(f'  .modinfo old fields ({len(fields)}):')
    new_fields = []
    for f in fields:
        s = f.decode('latin1', errors='replace')
        if s.startswith('vermagic='):
            new = 'vermagic=' + DEV_VERMAGIC
            print(f'    {s!r}  ->  {new!r}')
            new_fields.append(new)
        elif s.startswith('name='):
            new = 'name=' + NEW_MODNAME
            print(f'    {s!r}  ->  {new!r}')
            new_fields.append(new)
        elif s.startswith('import_ns='):
            print(f'    {s!r}  -> DROPPED (avoid namespace check panic)')
            # skip - don't append
        else:
            print(f'    {s!r}  (kept)')
            new_fields.append(s)
    new_content = b''.join(f.encode('latin1') + b'\x00' for f in new_fields)
    delta = len(new_content) - modinfo_old_size
    return bytes(new_content), delta


def main():
    with open(IN, 'rb') as f:
        data = bytearray(f.read())
    print(f'paradise.ko size: {len(data)}')

    # 1. Parse ELF header
    if data[:4] != b'\x7fELF':
        sys.exit('not ELF')
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3c)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3a)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3e)[0]
    print(f'e_shoff={e_shoff:#x} e_shnum={e_shnum} e_shentsize={e_shentsize}')

    # 2. Section header table parsing
    # section header layout (Elf64_Shdr): name(4) type(4) flags(8) addr(8) offset(8)
    #                                     size(8) link(4) info(4) addralign(8) entsize(8)
    SHDR_SIZE = e_shentsize  # 64
    def shdr_field(idx, name):
        off = e_shoff + idx * SHDR_SIZE
        layout = {
            'name': (0, '<I'), 'type': (4, '<I'), 'flags': (8, '<Q'),
            'addr': (0x10, '<Q'), 'offset': (0x18, '<Q'), 'size': (0x20, '<Q'),
            'link': (0x28, '<I'), 'info': (0x2c, '<I'),
            'addralign': (0x30, '<Q'), 'entsize': (0x38, '<Q')
        }
        sub, fmt = layout[name]
        return struct.unpack_from(fmt, data, off + sub)[0]
    def set_shdr_field(idx, name, value):
        off = e_shoff + idx * SHDR_SIZE
        layout = {
            'name': (0, '<I'), 'type': (4, '<I'), 'flags': (8, '<Q'),
            'addr': (0x10, '<Q'), 'offset': (0x18, '<Q'), 'size': (0x20, '<Q'),
            'link': (0x28, '<I'), 'info': (0x2c, '<I'),
            'addralign': (0x30, '<Q'), 'entsize': (0x38, '<Q')
        }
        sub, fmt = layout[name]
        struct.pack_into(fmt, data, off + sub, value)

    # Get section names from .shstrtab
    shstrtab_off = shdr_field(e_shstrndx, 'offset')
    shstrtab_size = shdr_field(e_shstrndx, 'size')
    shstrtab = bytes(data[shstrtab_off:shstrtab_off + shstrtab_size])
    def sec_name(idx):
        n = shdr_field(idx, 'name')
        end = shstrtab.index(b'\x00', n)
        return shstrtab[n:end].decode('latin1', errors='replace')

    # Find sections we care about
    sec_idx = {}
    for i in range(e_shnum):
        sec_idx[sec_name(i)] = i

    print('\nKey section locations:')
    for k in ['.init.text', '.exit.text', '.modinfo', '.gnu.linkonce.this_module']:
        i = sec_idx[k]
        print(f'  [{i}] {k}  off={shdr_field(i,"offset"):#x}  size={shdr_field(i,"size"):#x}')

    # 3. PATCH 1: init_module @ .init.text + 4
    init_text_off = shdr_field(sec_idx['.init.text'], 'offset')
    init_module_off = init_text_off + 4
    print(f'\nPatching init_module @ file offset {init_module_off:#x}')
    print(f'  before: {bytes(data[init_module_off:init_module_off+len(PATCH_CODE)]).hex()}')
    data[init_module_off:init_module_off+len(PATCH_CODE)] = PATCH_CODE
    print(f'  after:  {bytes(data[init_module_off:init_module_off+len(PATCH_CODE)]).hex()}')

    # 4. PATCH 2: cleanup_module @ .exit.text + 4
    exit_text_off = shdr_field(sec_idx['.exit.text'], 'offset')
    cleanup_off = exit_text_off + 4
    print(f'\nPatching cleanup_module @ file offset {cleanup_off:#x}')
    print(f'  before: {bytes(data[cleanup_off:cleanup_off+len(PATCH_CODE)]).hex()}')
    data[cleanup_off:cleanup_off+len(PATCH_CODE)] = PATCH_CODE
    print(f'  after:  {bytes(data[cleanup_off:cleanup_off+len(PATCH_CODE)]).hex()}')

    # 5. PATCH 3: struct module name "paradise" -> "tss_poc"
    tm_off = shdr_field(sec_idx['.gnu.linkonce.this_module'], 'offset')
    name_off_in_tm = bytes(data[tm_off:tm_off+0x100]).find(b'paradise')
    if name_off_in_tm < 0:
        sys.exit('paradise name not found in this_module section')
    abs_name_off = tm_off + name_off_in_tm
    print(f'\nPatching struct module name @ file offset {abs_name_off:#x}')
    # Original "paradise\0" is 8+1=9 bytes. We zero 9 bytes then write "tss_poc\0"
    for k in range(16): data[abs_name_off + k] = 0
    name_bytes = NEW_MODNAME.encode('latin1') + b'\x00'
    data[abs_name_off:abs_name_off+len(name_bytes)] = name_bytes
    print(f'  ctx: {bytes(data[abs_name_off-2:abs_name_off+16]).hex()}')

    # 6. PATCH 4: .modinfo content (vermagic + name) with section resize
    modinfo_idx = sec_idx['.modinfo']
    modinfo_off = shdr_field(modinfo_idx, 'offset')
    modinfo_size = shdr_field(modinfo_idx, 'size')
    new_modinfo, delta = patch_modinfo(data, modinfo_off, modinfo_size)
    print(f'\n.modinfo resize: old={modinfo_size} new={len(new_modinfo)} delta={delta:+d}')

    # We must shift everything after .modinfo by `delta` bytes (could be negative)
    after_off = modinfo_off + modinfo_size
    # Slice out the byte regions
    head = bytes(data[:modinfo_off])
    tail = bytes(data[after_off:])
    new_data = bytearray(head + new_modinfo + tail)
    print(f'  new file size: {len(new_data)}  (orig: {len(data)}  delta: {len(new_data)-len(data):+d})')

    # 7. Fix up section header offsets for sections with offset >= after_off
    # Sections are in new_data, but section header table was at old e_shoff
    # which is at the very end. Section headers themselves shift by delta too.
    # First parse all old headers from original data (they're shifted in new_data too)
    new_e_shoff = e_shoff + delta if e_shoff >= after_off else e_shoff
    print(f'  ELF e_shoff: {e_shoff:#x} -> {new_e_shoff:#x}')
    struct.pack_into('<Q', new_data, 0x28, new_e_shoff)

    # Iterate section headers in new_data at new_e_shoff
    for i in range(e_shnum):
        nh_off = new_e_shoff + i * SHDR_SIZE
        s_off = struct.unpack_from('<Q', new_data, nh_off + 0x18)[0]
        # If this is .modinfo itself, update its size
        if i == modinfo_idx:
            struct.pack_into('<Q', new_data, nh_off + 0x20, len(new_modinfo))
            continue
        # If section starts after .modinfo end (in old space), shift its offset
        if s_off >= after_off:
            new_off = s_off + delta
            struct.pack_into('<Q', new_data, nh_off + 0x18, new_off)

    # Write
    with open(OUT, 'wb') as f:
        f.write(new_data)
    print(f'\nWrote {OUT}  ({len(new_data)} bytes)')

    # Verify by re-reading
    print('\n=== Verifying output ===')
    from elftools.elf.elffile import ELFFile
    with open(OUT, 'rb') as f:
        elf = ELFFile(f)
        mi = elf.get_section_by_name('.modinfo')
        print(f'.modinfo size: {mi["sh_size"]}  off: {mi["sh_offset"]:#x}')
        for field in mi.data().split(b'\x00'):
            if field:
                try: print(f'  {field.decode()!r}')
                except: pass

main()
