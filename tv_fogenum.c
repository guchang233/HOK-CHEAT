/* tv_fogenum.c — PURE-READ global enumeration probe (NO HW-BP, NO arming, NO VMA scan).
 *
 * Tests the capture-free deep-fog read path discovered by static RE:
 *   actor (baba anchor gc_bss+0x17BB58) -> objID=actor+0x34
 *   container = *(actor+0xe8)                       (LocomotionContainer, per-actor)
 *   cnt = *(container+0x248)   small-map discriminator
 *     cnt==0 -> INLINE : c = *(container+0x250)
 *     cnt!=0 -> HEAP   : m=*(container+0x268); et=*(m+0x10);
 *                        idx=( *(u32)(m+0x50)==0 ) ? 0 : u16(*(m+0x30)+2);
 *                        c = *(et + idx*0x18)
 *   node = *(c+0x60); pos = {int32 x@+0, y@+4, z@+8} / 1000 m   (authoritative, setter-written)
 *   render node = *(c+0xc0)  (integrator; may freeze for non-ticking static fog enemy)
 *   ownerObjID = *( *(c+0x10) + 0x100 )  (cross-check)
 *
 * Cross-checked against: DisplayInfoData (truth for visible) + existing fog-view chain
 *   (actor+0x268->+0x10->+0->+0x60) which is the KNOWN fog-gated path (dead for never-seen).
 *
 * DECISIVE TEST: for a never-revealed static deep-fog enemy, does SEL pos exist, equal true pos,
 * and TRACK movement (use watch mode)?  -> if yes, capture-free full-map vision, no HW-BP.
 *
 * Usage:  tv_fogenum [pid] [loops] [interval_ms]
 *   pid omitted/0  -> autodetect sgame.   loops default 1.   interval_ms default 500.
 *   Run with: su -c '/data/local/tmp/tv_fogenum 0 40 500'   (40 samples @ 0.5s = 20s watch)
 *
 * SAFETY: every read is a single targeted KPM 'r' of a specific address (pointer-follow only).
 * No proc-mem mass scan, no VMA walk. Bad pointers -> kpm_read returns short -> skipped.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/syscall.h>

#define __NR_supercall 45
#define SC_VER 0x1008
#define SC_CTL 0x1022
#define SGAME_PKG "com.tencent.tmgp.sgame"
#define LIBGC "libGameCore.so"

#define UNTAG(p) ((uint64_t)(p) & 0xFFFFFFFFFFFFULL)

/* ---- gc_bss offsets (verified) ---- */
#define OFF_IS_IN_BATTLE 0x2844
#define OFF_ANCHOR1      0x28A8
#define OFF_ACTOR_ANCHOR 0x17BB58
#define ACTOR_LIST_OFF   0x238
#define ACTOR_STRIDE     0x18
#define N_ACTOR          10
#define OFF_DD_BEGIN     0x1C4980
#define OFF_DD_COUNT     0x1C499C
#define DD_STRIDE        0x34
#define DD_MAX_ENT       160

/* ---- KPM (KPatch-Next) cross-process read channel ---- */
static uint32_t kp_version = 0;
static const char *KPM_NAME = NULL;
static const char *KPM_CANDIDATES[] = {
    "sgame-truevision-v23", "sgame-fakemem-v20", "sgame-fakemem-v19",
    "sgame-fakemem-v18", "sgame-fakemem-v17", NULL };

static long kp_vc(long cmd) { return ((long)kp_version << 32) | (0x2026L << 16) | (cmd & 0xFFFF); }
static int kp_init(void) {
    long v = syscall(__NR_supercall, NULL, SC_VER);
    if (v < 0) return -1;
    kp_version = (v < 0xa05) ? 0 : (uint32_t)v;
    if (syscall(__NR_supercall, NULL, kp_vc(SC_VER)) < 0) return -1;
    return 0;
}
static int kpm_ctl(const char *args, char *out, int outlen) {
    return (int)syscall(__NR_supercall, NULL, kp_vc(SC_CTL), KPM_NAME, args, out, (long)outlen);
}
static int kpm_detect(void) {
    char resp[512];
    for (int i = 0; KPM_CANDIDATES[i]; i++) {
        KPM_NAME = KPM_CANDIDATES[i];
        memset(resp, 0, sizeof(resp));
        if (kpm_ctl("s", resp, sizeof(resp)) == 0) return 0;
    }
    KPM_NAME = NULL; return -1;
}
static int parse_r(const char *resp, uint8_t *buf, int max) {
    if (!resp || resp[0] != 'R' || resp[1] != ':') return -1;
    const char *p = resp + 2; int n = 0;
    while (*p >= '0' && *p <= '9') n = n * 10 + (*p++ - '0');
    if (*p == ' ') p++;
    if (n > max) n = max;
    for (int i = 0; i < n; i++) {
        char a = p[i*2], b = p[i*2+1]; if (!a || !b) return i;
        uint8_t hi = (a >= 'a') ? (a - 'a' + 10) : (a - '0');
        uint8_t lo = (b >= 'a') ? (b - 'a' + 10) : (b - '0');
        buf[i] = (hi << 4) | lo;
    }
    return n;
}
static int kpm_read(int pid, uint64_t addr, void *out, int len) {
    char cmd[80], resp[8192];
    snprintf(cmd, sizeof(cmd), "r %d %lx %x", pid, (unsigned long)addr, len);
    memset(resp, 0, sizeof(resp));
    if (kpm_ctl(cmd, resp, sizeof(resp)) < 0) return -1;
    return parse_r(resp, (uint8_t *)out, len);
}
static int find_sgame(void) {
    DIR *d = opendir("/proc"); if (!d) return -1;
    struct dirent *e; int found = -1;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char path[64]; snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY); if (fd < 0) continue;
        char buf[256] = {0}; ssize_t r = read(fd, buf, sizeof(buf) - 1); close(fd);
        if (r > 0 && strcmp(buf, SGAME_PKG) == 0) { found = atoi(e->d_name); break; }
    }
    closedir(d); return found;
}
static uint64_t resolve_anon_bss(int pid, const char *mod) {
    char path[64]; snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r"); if (!f) return 0;
    char line[1024]; int found = 0; uint64_t bss = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!found) { if (strstr(line, mod)) found = 1; continue; }
        if (strstr(line, "[anon:.bss]")) { unsigned long s = 0; if (sscanf(line, "%lx-", &s) == 1) bss = s; break; }
    }
    fclose(f); return bss;
}

/* ---- small read helpers ---- */
static inline int ptr_ok(uint64_t p) { return p >= 0x10000ULL && p < 0x800000000000ULL; }
/* read 8B pointer, untag; returns 1 if a plausible userspace pointer, 0 otherwise */
static int rd64(int pid, uint64_t a, uint64_t *o) {
    uint64_t v = 0; if (kpm_read(pid, a, &v, 8) < 8) return 0; *o = UNTAG(v); return ptr_ok(*o);
}
static int rd32(int pid, uint64_t a, uint32_t *o) {
    uint32_t v = 0; if (kpm_read(pid, a, &v, 4) < 4) return 0; *o = v; return 1;
}
/* read a position node ptr -> 3x int32 packed /1000.  node = *(c+0x60) (or +0xc0).  returns 1 on ok */
static int read_pos(int pid, uint64_t c, uint64_t node_off, float *x, float *y, float *z, uint64_t *node_out) {
    uint64_t node = 0;
    if (!rd64(pid, c + node_off, &node)) return 0;
    if (node_out) *node_out = node;
    int32_t p[3] = {0,0,0};
    if (kpm_read(pid, node, p, 12) < 12) return 0;
    *x = p[0] / 1000.0f; *y = p[1] / 1000.0f; *z = p[2] / 1000.0f;
    return 1;
}
/* container -> proxy c via the CORRECTED small-map branch.
 * mode 0 = follow discriminator (engine's choice); 1 = force inline; 2 = force heap.
 * returns c (or 0). */
static uint64_t resolve_c(int pid, uint64_t container, int mode, uint32_t *cnt_out) {
    uint32_t cnt = 0; rd32(pid, container + 0x248, &cnt);
    if (cnt_out) *cnt_out = cnt;
    int use_heap = (mode == 2) ? 1 : (mode == 1) ? 0 : (cnt != 0);
    if (!use_heap) {                       /* INLINE: c = *(container+0x250) */
        uint64_t c = 0; if (rd64(pid, container + 0x250, &c)) return c; return 0;
    }
    /* HEAP: m=*(container+0x268); et=*(m+0x10); idx; c=*(et+idx*0x18) */
    uint64_t m = 0; if (!rd64(pid, container + 0x268, &m)) return 0;
    uint64_t et = 0; if (!rd64(pid, m + 0x10, &et)) return 0;
    uint32_t mc = 0; rd32(pid, m + 0x50, &mc);
    uint32_t idx = 0;
    if (mc != 0) {
        uint64_t k = 0; if (rd64(pid, m + 0x30, &k)) { uint16_t u = 0; if (kpm_read(pid, k + 2, &u, 2) == 2) idx = u; }
    }
    if (idx > 4096) idx = 0;               /* sanity clamp */
    uint64_t c = 0; if (rd64(pid, et + (uint64_t)idx * 0x18, &c)) return c; return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    int pid = (argc > 1) ? atoi(argv[1]) : 0;
    int loops = (argc > 2) ? atoi(argv[2]) : 1;
    int interval_ms = (argc > 3) ? atoi(argv[3]) : 500;
    if (loops < 1) loops = 1;

    if (kp_init() < 0) { printf("[fog] supercall(45) failed: %s — root/KPatch not ready\n", strerror(errno)); return 2; }
    if (kpm_detect() < 0) { printf("[fog] NO sgame KPM loaded (tried v23..v17). Load KPM first.\n"); return 2; }
    if (pid <= 0) pid = find_sgame();
    if (pid <= 0) { printf("[fog] sgame not running\n"); return 2; }
    uint64_t gc = resolve_anon_bss(pid, LIBGC);
    if (!gc) { printf("[fog] gc_bss not found for pid=%d (is libGameCore mapped?)\n", pid); return 3; }
    printf("[fog] KPM=%s pid=%d gc_bss=0x%llx  loops=%d interval=%dms\n",
           KPM_NAME, pid, (unsigned long long)gc, loops, interval_ms);
    printf("[fog] read path = actor->*(+0xe8)=container; cnt=*(+0x248); inline *(+0x250) / heap *(+0x268)->; *(c+0x60)=pos/1000\n");

    for (int it = 0; it < loops; it++) {
        uint32_t in_battle = 0; rd32(pid, gc + OFF_IS_IN_BATTLE, &in_battle);
        /* our camp */
        uint32_t our_camp = 0; uint64_t a1 = 0, p1 = 0, p2 = 0;
        if (rd64(pid, gc + OFF_ANCHOR1, &a1) && rd64(pid, a1 + 0xA8, &p1) && rd64(pid, p1 + 0xC8, &p2))
            rd32(pid, p2 + 0x5C, &our_camp);
        /* actor list */
        uint64_t anchor = 0, lb = 0;
        rd64(pid, gc + OFF_ACTOR_ANCHOR, &anchor);
        if (anchor) rd64(pid, anchor + ACTOR_LIST_OFF, &lb);
        uint8_t ents[N_ACTOR * ACTOR_STRIDE];
        int have = (lb && kpm_read(pid, lb, ents, sizeof(ents)) == (int)sizeof(ents));
        /* DisplayInfoData (truth for visible) */
        static uint8_t dd[DD_MAX_ENT * DD_STRIDE]; int ddn = 0; uint64_t ddb = 0;
        if (rd64(pid, gc + OFF_DD_BEGIN, &ddb)) {
            uint32_t dc = 0; rd32(pid, gc + OFF_DD_COUNT, &dc);
            int n = (int)dc; if (n < 0) n = 0; if (n > DD_MAX_ENT) n = DD_MAX_ENT;
            int ok = 1;
            for (int o = 0; o < n * DD_STRIDE; o += 1024) {
                int w = (n*DD_STRIDE - o < 1024) ? (n*DD_STRIDE - o) : 1024;
                if (kpm_read(pid, ddb + o, dd + o, w) < w) { ok = 0; break; }
            }
            if (ok) ddn = n;
        }

        printf("\n===== t=%d  in_battle=%u our_camp=%u dd_count=%d %s =====\n",
               it, in_battle, our_camp, ddn, have ? "" : "(ACTOR LIST UNAVAILABLE — not in battle?)");
        printf("  # oid  camp side    typ inDD   DD(x,z)      chain268(x,z)  cnt  SEL c+0x60(x,z)  ownObj  render c+0xc0  [in:(x,z) hp:(x,z)]\n");

        for (int i = 0; i < N_ACTOR && have; i++) {
            uint64_t act; memcpy(&act, ents + i * ACTOR_STRIDE, 8); act = UNTAG(act);
            if (!ptr_ok(act)) { printf("  %d  --- NULL ---\n", i); continue; }
            uint32_t oid = 0, camp = 0, type = 0;
            rd32(pid, act + 0x34, &oid); rd32(pid, act + 0x5C, &camp); rd32(pid, act + 0x50, &type);
            const char *side = (our_camp && camp == our_camp) ? "FRIEND" : ((camp >= 1 && camp <= 2) ? "ENEMY " : "?     ");

            /* DD truth */
            int inDD = 0; float dx = 0, dz = 0;
            for (int k = 0; k < ddn; k++) { uint8_t *ee = dd + k * DD_STRIDE; if (*(uint32_t *)(ee + 0) == oid) { dx = *(float *)(ee + 0x10); dz = *(float *)(ee + 0x18); inDD = 1; break; } }

            /* existing fog-view chain (actor+0x268->+0x10->+0->+0x60) — KNOWN fog-gated */
            float cx = 0, cz = 0; int havec = 0;
            uint64_t e1 = 0, e2 = 0, e3 = 0, e4 = 0;
            if (rd64(pid, act + 0x268, &e1) && rd64(pid, e1 + 0x10, &e2) &&
                rd64(pid, e2 + 0, &e3) && rd64(pid, e3 + 0x60, &e4)) {
                int32_t p[3]; if (kpm_read(pid, e4, p, 12) == 12) { cx = p[0]/1000.0f; cz = p[2]/1000.0f; havec = 1; }
            }

            /* container = act (confirmed). Dump heap-path internals + alt position nodes. */
            uint32_t cnt = 0; rd32(pid, act + 0x248, &cnt);
            uint64_t m = 0, et = 0, c = 0; uint32_t mc = 0; uint32_t idx = 0;
            rd64(pid, act + 0x268, &m);
            if (ptr_ok(m)) { rd64(pid, m + 0x10, &et); rd32(pid, m + 0x50, &mc);
                if (mc != 0) { uint64_t k=0; if (rd64(pid,m+0x30,&k)){ uint16_t u=0; if(kpm_read(pid,k+2,&u,2)==2) idx=u; } }
                if (idx > 4096) idx = 0;
                if (ptr_ok(et)) rd64(pid, et + (uint64_t)idx*0x18, &c);
            }
            /* alt position nodes on the heap proxy c */
            float p60x=0,p60z=0,pc0x=0,pc0z=0,p0x=0,p0z=0,dy; int h60=0,hc0=0,h0=0;
            uint32_t hown=0; int hhown=0;
            if (ptr_ok(c)) {
                h60 = read_pos(pid, c, 0x60, &p60x, &dy, &p60z, NULL);
                hc0 = read_pos(pid, c, 0xc0, &pc0x, &dy, &pc0z, NULL);
                int32_t e0[3]; if (kpm_read(pid,c,e0,12)==12){ p0x=e0[0]/1000.0f; p0z=e0[2]/1000.0f; h0=1; }
                uint64_t own=0; if (rd64(pid,c+0x10,&own)) hhown=rd32(pid,own+0x100,&hown);
            }
            /* SyncSource handle act+0x2e8 + inline act+0x250 */
            uint64_t ss=0, inl=0; rd64(pid, act+0x2e8, &ss); rd64(pid, act+0x250, &inl);
            uint32_t sscnt=0; uint64_t ssa1=0, ssa2=0;
            if (ptr_ok(ss)) { rd32(pid, ss+0x28, &sscnt); rd64(pid, ss+0x30, &ssa1); rd64(pid, ss+0x48, &ssa2); }

            printf("  %d oid=%-3u %-6s typ=%-3u inDD=%d ", i, oid, side, type, inDD);
            if (inDD) printf("DD=(%7.2f,%7.2f)", dx, dz); else printf("DD=( fog )       ");
            if (havec) printf(" ch268=(%7.2f,%7.2f)\n", cx, cz); else printf(" ch268=( fog/X )\n");
            printf("        cnt=%u m=%llx et=%llx idx=%u c=%llx own=%u%s\n",
                   cnt, (unsigned long long)(m&0xffffff), (unsigned long long)(et&0xffffff), idx,
                   (unsigned long long)(c&0xffffff), hown, hhown?"":"(no-own)");
            printf("        c+60=%s%.2f,%.2f  c+c0=%s%.2f,%.2f  c+0=%s%.2f,%.2f\n",
                   h60?"":"x", p60x,p60z, hc0?"":"x", pc0x,pc0z, h0?"":"x", p0x,p0z);
            printf("        ss(2e8)=%llx sscnt=%u a1=%llx a2=%llx inl(250)=%llx\n",
                   (unsigned long long)(ss&0xffffff), sscnt, (unsigned long long)(ssa1&0xffffff),
                   (unsigned long long)(ssa2&0xffffff), (unsigned long long)(inl&0xffffff));
            /* For ENEMIES (fog-nulled c+0x60): dump c_view node-pointer region + try alt node offsets.
             * Find any pointer in c that reaches a live position node (non-fog). */
            if (ptr_ok(c) && side[0]=='E') {
                uint8_t cb[0x100]; int n = kpm_read(pid, c, cb, sizeof(cb));
                printf("        c[..]:");
                for (int o = 0x58; o + 8 <= n && o <= 0xd0; o += 8) {
                    uint64_t v; memcpy(&v, cb+o, 8); uint64_t u = UNTAG(v);
                    if (ptr_ok(u)) {
                        int32_t np[3]={0,0,0}; int ok = (kpm_read(pid,u,np,12)==12);
                        float nx=np[0]/1000.0f, nz=np[2]/1000.0f;
                        int plausible = ok && nx>-100 && nx<100 && nz>-100 && nz<100 && (np[0]||np[2]);
                        printf(" +%02x=%llx%s", o, (unsigned long long)(u&0xffffff),
                               plausible ? "*POS*" : "");
                        if (plausible) printf("(%.1f,%.1f)", nx, nz);
                    }
                }
                printf("\n");
            }
        }
        if (it + 1 < loops) usleep(interval_ms * 1000);
    }
    printf("\n[fog] done. KEY: for a never-revealed deep-fog ENEMY: DD=fog & chain268=fog/X (expected dead),\n");
    printf("[fog]   but if SEL c+0x60 shows a real position that TRACKS movement across t=ticks => capture-free vision WORKS.\n");
    printf("[fog]   compare inline(in:) vs heap(hp:) — whichever holds the live coord is the populated backend.\n");
    return 0;
}
