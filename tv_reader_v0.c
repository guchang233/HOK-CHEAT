/* TrueVision Reader v0.1
 * 自动找 sgame PID + 解析 maps + set sgame_tgid + dump 10 actor 位置
 * 通过 KPatch-Next supercall(45) + KPM ctl0 跨进程读 sgame 内存
 *
 * 编译: zig cc -target aarch64-linux-musl -O2 -static -s reader.c -o tv_reader
 * 部署: adb push tv_reader /data/local/tmp/ && chmod 755 /data/local/tmp/tv_reader
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/syscall.h>
#include <stdint.h>
#include <time.h>
#include <errno.h>

/* KPatch-Next supercall ABI */
#define __NR_supercall 45
#define SUPERCALL_KERNELPATCH_VER 0x1008
#define SUPERCALL_KPM_CONTROL 0x1022

#define KPM_NAME "sgame-fakemem-v17"
#define SGAME_PKG "com.tencent.tmgp.sgame"
#define LIBGAMECORE_NAME "libGameCore.so"

/* sgame v11.3.1.1 layout (from baba RE) */
#define BSS_OFFSET          0x4E9A000     /* libGameCore.so .bss section vaddr offset */
#define OFF_IS_IN_BATTLE    0x2844        /* bss + 0x2844 = is_in_battle flag */
#define OFF_ACTOR_ANCHOR    0x17BB58      /* bss + 0x17BB58 = actor list anchor */
#define ANCHOR_LIST_OFF     0x238         /* anchor + 0x238 = actor list base */
#define ACTOR_STRIDE        0x18          /* 24 bytes per entry */
#define ACTOR_TYPE_OFF      0x50          /* actor + 0x50 = type (uint32) */
#define ACTOR_POS_PTR_OFF   0x188         /* chain A: actor + 0x188 = pos_anchor */
#define POS_OFFSET          0xA8          /* chain A: pos_anchor + 0xA8 = ? (DYNAMIC) */
#define ACTOR_POS_B_PTR_OFF 0x250         /* chain B (hero): actor + 0x250 deref */
#define POS_B_X_OFF         0x60          /* chain B: +0x60 = x int32 */
#define POS_B_Z_OFF         0x68          /* chain B: +0x68 = z int32 */
#define ACTOR_COUNT         10            /* 5v5 hardcoded */

/* ARM64 PAC top byte ignore */
#define UNTAG(p) ((p) & 0xFFFFFFFFFFFFULL)

static uint32_t kp_version = 0;

static long ver_and_cmd(long cmd) {
    return ((long)kp_version << 32) | (0x2026L << 16) | (cmd & 0xFFFF);
}

static int kp_init(void) {
    /* Bootstrap: try the bare cmd first to get version */
    long ver = syscall(__NR_supercall, NULL, SUPERCALL_KERNELPATCH_VER);
    if (ver < 0) {
        fprintf(stderr, "[!] supercall(45) failed: %s\n", strerror(errno));
        return -1;
    }
    if (ver < 0xa05) {
        /* Old API style, just use raw cmd */
        kp_version = 0;
    } else {
        kp_version = (uint32_t)ver;
    }
    /* Re-query with version for sanity */
    long v2 = syscall(__NR_supercall, NULL, ver_and_cmd(SUPERCALL_KERNELPATCH_VER));
    if (v2 < 0) {
        fprintf(stderr, "[!] versioned supercall failed: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static int kpm_ctl(const char *args, char *out, int outlen) {
    long ret = syscall(__NR_supercall, NULL,
                       ver_and_cmd(SUPERCALL_KPM_CONTROL),
                       KPM_NAME, args, out, (long)outlen);
    return (int)ret;
}

/* Parse "R:<n> <hex>\n" or "E:<msg>\n" */
static int parse_r_response(const char *resp, uint8_t *buf, int max_len) {
    if (!resp) return -1;
    if (resp[0] == 'E') return -1;  /* error */
    if (resp[0] != 'R' || resp[1] != ':') return -1;
    const char *p = resp + 2;
    int n = 0;
    while (*p >= '0' && *p <= '9') { n = n * 10 + (*p++ - '0'); }
    if (*p == ' ') p++;
    if (n > max_len) n = max_len;
    for (int i = 0; i < n; i++) {
        char a = p[i*2], b = p[i*2+1];
        if (!a || !b) return i;
        uint8_t hi = (a >= 'a' ? (a - 'a' + 10) : (a - '0'));
        uint8_t lo = (b >= 'a' ? (b - 'a' + 10) : (b - '0'));
        buf[i] = (hi << 4) | lo;
    }
    return n;
}

static int kpm_read_mem(int pid, uint64_t addr, void *out, int len) {
    char cmd[64], resp[8192];
    snprintf(cmd, sizeof(cmd), "r %d %lx %x", pid, (unsigned long)addr, len);
    memset(resp, 0, sizeof(resp));
    if (kpm_ctl(cmd, resp, sizeof(resp)) < 0) return -1;
    return parse_r_response(resp, (uint8_t *)out, len);
}

static int find_sgame_pid(void) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char buf[256] = {0};
        ssize_t r = read(fd, buf, sizeof(buf)-1);
        close(fd);
        if (r > 0 && strcmp(buf, SGAME_PKG) == 0) {
            found = atoi(e->d_name);
            break;
        }
    }
    closedir(d);
    return found;
}

static uint64_t find_libgamecore_base(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[1024];
    uint64_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        /* First r--p segment of libGameCore.so = library base */
        if (strstr(line, LIBGAMECORE_NAME) && strstr(line, "r--p")) {
            unsigned long start = 0;
            if (sscanf(line, "%lx-", &start) == 1) {
                base = (uint64_t)start;
                break;
            }
        }
    }
    fclose(f);
    return base;
}

static int is_pid_alive(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    return access(path, F_OK) == 0;
}

int main(int argc, char **argv) {
    setbuf(stdout, NULL);  /* unbuffered, real-time print */

    int interval_ms = 500;  /* 2 FPS default */
    if (argc > 1) interval_ms = atoi(argv[1]);
    if (interval_ms <= 0) interval_ms = 500;

    printf("=== TrueVision Reader v0.1 ===\n");
    printf("Interval: %d ms\n", interval_ms);

    if (kp_init() < 0) {
        fprintf(stderr, "KPatch-Next init failed. Need KPatch-Next + sgame-fakemem-v17.kpm loaded.\n");
        return 1;
    }
    printf("[init] KPatch-Next ver=0x%x\n", kp_version);

    /* Verify KPM loaded by sending status query */
    char resp[1024] = {0};
    int rc = kpm_ctl("s", resp, sizeof(resp));
    if (rc < 0) {
        fprintf(stderr, "[!] KPM %s not loaded or ctl0 failed (rc=%d)\n", KPM_NAME, rc);
        return 2;
    }
    printf("[init] KPM status: %s", resp);  /* already ends with \n */

    while (1) {
        int pid = find_sgame_pid();
        if (pid <= 0) {
            printf("[wait] sgame not running, retry in 5s\n");
            sleep(5);
            continue;
        }

        uint64_t libgc = find_libgamecore_base(pid);
        if (libgc == 0) {
            printf("[wait] libGameCore.so not mapped yet (pid=%d), retry in 2s\n", pid);
            sleep(2);
            continue;
        }

        uint64_t bss = libgc + BSS_OFFSET;
        printf("\n[+] Found sgame pid=%d libGameCore=0x%lx bss=0x%lx\n",
               pid, (unsigned long)libgc, (unsigned long)bss);

        /* Set sgame_tgid in KPM (so fake maps + filter applies) */
        char tgid_cmd[32];
        snprintf(tgid_cmd, sizeof(tgid_cmd), "p %d", pid);
        memset(resp, 0, sizeof(resp));
        kpm_ctl(tgid_cmd, resp, sizeof(resp));
        printf("[+] KPM tgid set: %s", resp);

        /* Game loop - read until sgame dies or restart */
        while (is_pid_alive(pid)) {
            uint8_t in_battle[4];
            int rb = kpm_read_mem(pid, bss + OFF_IS_IN_BATTLE, in_battle, 4);
            if (rb < 4) {
                printf("[!] read is_in_battle failed (rc=%d)\n", rb);
                usleep(interval_ms * 1000);
                continue;
            }
            if (in_battle[0] != 1) {
                printf("[idle] in_battle=%d  pid=%d\n", in_battle[0], pid);
                sleep(2);
                continue;
            }

            uint64_t anchor = 0;
            if (kpm_read_mem(pid, bss + OFF_ACTOR_ANCHOR, &anchor, 8) < 8) {
                printf("[!] read actor_anchor failed\n");
                usleep(interval_ms * 1000);
                continue;
            }
            anchor = UNTAG(anchor);
            if (anchor == 0) { usleep(interval_ms * 1000); continue; }

            uint64_t list_base = 0;
            if (kpm_read_mem(pid, anchor + ANCHOR_LIST_OFF, &list_base, 8) < 8) continue;
            list_base = UNTAG(list_base);
            if (list_base == 0) { usleep(interval_ms * 1000); continue; }

            /* Read 10 entries in one shot */
            uint8_t entries[ACTOR_COUNT * ACTOR_STRIDE];
            if (kpm_read_mem(pid, list_base, entries, sizeof(entries)) < (int)sizeof(entries)) continue;

            printf("\n--- Frame [pid=%d anchor=0x%lx list=0x%lx] ---\n",
                   pid, (unsigned long)anchor, (unsigned long)list_base);

            for (int i = 0; i < ACTOR_COUNT; i++) {
                uint64_t actor;
                memcpy(&actor, entries + i * ACTOR_STRIDE, 8);
                actor = UNTAG(actor);
                if (actor == 0) {
                    printf("  [%d] NULL\n", i);
                    continue;
                }

                uint32_t type = 0;
                kpm_read_mem(pid, actor + ACTOR_TYPE_OFF, &type, 4);

                uint64_t pos_anchor = 0;
                if (kpm_read_mem(pid, actor + ACTOR_POS_PTR_OFF, &pos_anchor, 8) < 8 ||
                    UNTAG(pos_anchor) == 0) {
                    printf("  [%d] actor=0x%lx type=%-4u pos_anchor=NULL\n",
                           i, (unsigned long)actor, type);
                    continue;
                }
                pos_anchor = UNTAG(pos_anchor);

                int32_t pos[4] = {0};
                if (kpm_read_mem(pid, pos_anchor + POS_OFFSET, pos, 16) < 16) {
                    printf("  [%d] actor=0x%lx type=%-4u pos=READ_FAIL\n",
                           i, (unsigned long)actor, type);
                    continue;
                }

                printf("  [%d] actor=0x%lx type=%-4u pos=(%+7.2f, %+7.2f) m  raw=(%d, %d)\n",
                       i, (unsigned long)actor, type,
                       pos[0] / 100.0, pos[2] / 100.0,
                       pos[0], pos[2]);
            }

            usleep(interval_ms * 1000);
        }

        printf("[!] sgame pid=%d died, will re-discover...\n", pid);
        sleep(2);
    }
    return 0;
}
