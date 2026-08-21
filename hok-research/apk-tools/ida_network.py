"""Search production for network/packet routing."""
import idaapi, idautils, ida_funcs, ida_auto, idc, ida_bytes, ida_segment
ida_auto.auto_wait()

out = open(r"D:\ctf\_root\network_strings.txt", "w", encoding="utf-8")
def w(s): out.write(str(s)+"\n"); out.flush()

PATTERNS = [
    b"OnPacket",
    b"OnReceive",
    b"HandlePacket",
    b"NetMgr",
    b"NetworkManager",
    b"tcp_send",
    b"tcp_recv",
    b"tcp::",
    b"NetClient",
    b"SocketSend",
    b"SocketRecv",
    b"socket_send",
    b"socket_recv",
    b"connect_server",
    b"ConnectServer",
    b"tcp_session",
    b"frame_sync",
    b"FrameSync",
    b"sync_packet",
    b"sgame_packet",
    b"WireFormat",
    b"DecodePacket",
    b"EncodePacket",
]
import ida_segment
found = set()
for pat in PATTERNS:
    for seg in idautils.Segments():
        s = ida_segment.getseg(seg)
        if not s: continue
        try: data = ida_bytes.get_bytes(s.start_ea, s.end_ea - s.start_ea)
        except: continue
        if not data: continue
        i = 0
        while True:
            i = data.find(pat, i)
            if i < 0: break
            end = data.find(b"\x00", i)
            if end < 0: break
            chunk = data[i:end]
            if 5 <= len(chunk) <= 100:
                found.add((s.start_ea + i, chunk.decode(errors="replace")))
            i = end + 1
            if len(found) > 60: break

w(f"# {len(found)} network-related strings")
for addr, name in sorted(found)[:40]:
    w(f"  0x{addr:x}: {name}")

out.close()
print("DONE")
idc.qexit(0)
