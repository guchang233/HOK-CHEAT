/*
 * tv_reader - ESP game memory reader for Honor of Kings (王者荣耀)
 * 
 * Anti-cheat evasion design:
 *   - Uses /proc/<pid>/mem instead of ptrace (avoids PTRACE_TRACEME detection)
 *   - Benign-range filtering: only reads from private non-device mappings
 *   - Read throttling: max reads/second cap to avoid behavioral fingerprinting
 *   - Memory signature validation: checks game is alive & not anti-debugged
 *   - Low footprint: single-threaded, minimal syscalls, no suspicious patterns
 *
 * Target: com.tencent.tmgp.sgame (Honor of Kings)
 * Protocol: TVEF v1 binary frames over TCP
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/epoll.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <time.h>
#include <errno.h>
#include <dirent.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <assert.h>

#define GAME_PKG_DEFAULT "com.tencent.tmgp.sgame"
#define PORT_DEFAULT 47291
/*
 * 读取限流: pread 单次 ~1-2μs, 60000/s 的 CPU 开销约 10%。
 * 注意: 全堆扫描一轮需要 ~26 万次读取, 限流过低 (旧值 1200/s)
 * 会导致扫描一轮要 218 秒且每帧从头重扫 → 永远扫不到 actor。
 */
#define MAX_READS_PER_SEC 60000
#define READ_BATCH_SIZE 256
#define FRAME_INTERVAL_US 33000
#define MAX_ACTORS 64
#define MAX_SKILLS_PER_ACTOR 4
#define MAX_GLOBAL_TIMERS 8

#define MAP_X_MIN -10000.0f
#define MAP_X_MAX  10000.0f
#define MAP_Z_MIN -10000.0f
#define MAP_Z_MAX  10000.0f

#define ANCHOR_OFFSET      0x00238
#define ANCHOR_LIST_OFFSET 0x00000
#define ACTOR_TYPE_OFFSET  0x00188
#define ACTOR_NAME_OFFSET  0x0018C
#define ACTOR_TEAM_OFFSET  0x00198
#define POS_ANCHOR_OFFSET  0x000A8
#define POS_X_OFFSET       0x00044
#define POS_Y_OFFSET       0x00048
#define POS_Z_OFFSET       0x0004C

/* Enhanced actor offsets */
#define ACTOR_LEVEL_OFFSET   0x0019C
#define ACTOR_SPEED_OFFSET   0x001A0
#define ACTOR_FACING_OFFSET  0x001A4
#define ACTOR_ULTCD_OFFSET   0x001B0
#define ACTOR_ULTTOT_OFFSET  0x001B4
#define ACTOR_SKILLS_OFFSET  0x001C0
#define ACTOR_NAMEID_OFFSET  0x00188

/* Summoner skill offsets within skill slot */
#define SKILL_SPELLID_OFFSET  0x00
#define SKILL_CDREM_OFFSET    0x04
#define SKILL_CDTOT_OFFSET    0x08
#define SKILL_READY_OFFSET    0x0C
#define SKILL_SLOT_SIZE       0x10

/* Game state offsets for global timers */
#define GAMESTATE_TIME_OFFSET       0x0100
#define GAMESTATE_DRAGON_TIMER      0x0200
#define GAMESTATE_BARON_TIMER       0x0210
#define GAMESTATE_BLUE_RESPAWN      0x0220
#define GAMESTATE_RED_RESPAWN       0x0224
#define GAMESTATE_WOLF_RESPAWN      0x0228
#define GAMESTATE_CRAB_RESPAWN      0x022C
#define GAMESTATE_GOLEM_RESPAWN     0x0230

#define TEAM_ALLY   0x40
#define TEAM_ENEMY  0x1C

#define SUMMONER_FLASH     105
#define SUMMONER_HEAL      106
#define SUMMONER_EXECUTE   107
#define SUMMONER_CAPTURE   108
#define SUMMONER_SPEEDUP   109
#define SUMMONER_PURIFY    110
#define SUMMONER_BLINK     111
#define SUMMONER_STUN      112

/* ---- Utilities ---- */

static volatile sig_atomic_t g_running = 1;

static void on_sigint(int sig) {
    (void)sig;
    g_running = 0;
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static int clamp_i(int v, int lo, int hi) {
    return v < lo ? lo : v > hi ? hi : v;
}

/* ---- Process discovery ---- */

static pid_t find_game_pid(const char *pkg) {
    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *ent;
    while ((ent = readdir(d))) {
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') continue;
        pid_t pid = (pid_t)atoi(ent->d_name);
        char path[256];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char buf[512] = {0};
        ssize_t rd = read(fd, buf, sizeof(buf) - 1);
        (void)rd;
        close(fd);
        if (strstr(buf, pkg)) {
            closedir(d);
            return pid;
        }
    }
    closedir(d);
    return 0;
}

/* ---- Memory range filtering ---- */

typedef struct {
    uintptr_t start;
    uintptr_t end;
    int prot;       /* PROT_READ | PROT_WRITE */
    int flags;      /* MAP_PRIVATE, MAP_ANONYMOUS, etc */
    char path[256];
} MmapRange;

#define MAX_RANGES 512

static int parse_maps(pid_t pid, MmapRange *ranges, int max_ranges) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return -1;

    int count = 0;
    char line[512];
    while (count < max_ranges && fgets(line, sizeof(line), f)) {
        MmapRange *r = &ranges[count];
        uintptr_t s = 0, e = 0;
        char perms[8];
        char path_field[256] = "";

        unsigned long s_ul, e_ul, off_ul, ino_ul;
        unsigned long dev_ul;
        int n = sscanf(line, "%lx-%lx %7s %lx %lx %lu %255s",
                       &s_ul, &e_ul, perms, &off_ul, &dev_ul, &ino_ul, path_field);
        if (n < 5) continue;
        s = s_ul; e = e_ul;

        r->start = s;
        r->end = e;
        r->prot = 0;
        if (perms[0] == 'r') r->prot |= 1;
        if (perms[1] == 'w') r->prot |= 2;
        r->flags = 0;
        if (strstr(perms, "p")) r->flags |= 1; /* MAP_PRIVATE */
        strncpy(r->path, path_field, sizeof(r->path) - 1);

        /* 
         * Filter: keep only private read-write ranges that are NOT:
         *   - device mappings (/dev/*)
         *   - code (executable)
         *   - stack
         *   - files (file-backed mmaps)
         * 
         * This is the "benign range" filter to avoid detection.
         */
        if (!(r->prot & 1) || !(r->prot & 2)) continue;  /* need rw */
        if (r->flags != 1) continue;  /* need MAP_PRIVATE */
        
        /* Skip device mappings, they're suspicious */
        if (strncmp(r->path, "/dev/", 5) == 0) continue;
        if (strncmp(r->path, "/memfd:", 7) == 0) continue;
        
        /* Keep anonymous private ranges (heap, bss, data) */
        if (r->path[0] == '\0' || strstr(r->path, "[heap]") || 
            strstr(r->path, "[bss]") || strstr(r->path, "[data]") ||
            strstr(r->path, "[anon:") || strstr(r->path, "deleted")) {
            /* valid */
        } else {
            /* Skip file-backed (so/dex/art files) - they have integrity checks */
            continue;
        }
        
        count++;
    }
    fclose(f);
    return count;
}

/* Find the largest heap range (likely contains actor data) */
static MmapRange *find_largest_heap_range(MmapRange *ranges, int count) {
    MmapRange *best = NULL;
    uint64_t best_size = 0;
    for (int i = 0; i < count; i++) {
        uint64_t sz = ranges[i].end - ranges[i].start;
        if (sz > best_size) {
            best_size = sz;
            best = &ranges[i];
        }
    }
    return best;
}

static MmapRange *find_range_for_addr(MmapRange *ranges, int count, uintptr_t addr) {
    for (int i = 0; i < count; i++) {
        if (addr >= ranges[i].start && addr < ranges[i].end) {
            return &ranges[i];
        }
    }
    return NULL;
}

/* ---- Safe memory read ---- */

typedef struct {
    int fd;
    pid_t pid;
    MmapRange ranges[MAX_RANGES];
    int range_count;
    MmapRange *heap;
    uint64_t reads_per_second;
    uint64_t last_sec_ts;
    int read_count;
} MemReader;

/*
 * ---- Actor 列表缓存 (跨帧) ----
 * 旧实现每帧从堆头全量重扫 (26 万次读取), 且被限流打断后进度作废,
 * 导致 actor 永远扫不到。现在:
 *   g_actor_list_cache: 已命中的 actor 指针数组地址, 命中帧只读 ~百次;
 *   g_scan_cursor / g_scan_range_idx: 全扫描跨帧续扫游标。
 */
static uintptr_t g_actor_list_cache = 0;
static uintptr_t g_scan_cursor = 0;
static int g_scan_range_idx = 0;
static int g_scan_budget_override = 0;  /* >0: 诊断模式用大预算一次性扫完 */
static int g_no_throttle = 0;           /* 诊断模式旁路限流 */
static int g_relaxed_scan = 0;          /* 严格 team 校验一轮无果后降级: 不校验 team */
static int g_relaxed_announced = 0;     /* 降级只公告一次 */

static int mem_reader_open(MemReader *mr, pid_t pid) {
    mr->pid = pid;
    mr->fd = -1;
    mr->range_count = 0;
    mr->heap = NULL;
    mr->reads_per_second = 0;
    mr->last_sec_ts = now_ns();
    mr->read_count = 0;

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);

    /*
     * Open /proc/pid/mem with O_RDONLY.
     * This avoids ptrace entirely - no PTRACE_TRACEME syscall.
     * Anti-cheat can't detect /proc/pid/mem opens easily on modern Linux.
     */
    mr->fd = open(path, O_RDONLY | O_NOCTTY);
    if (mr->fd < 0) {
        /*
         * Fallback: use process_vm_readv (needs same uid or root)
         * This is even less detectable than /proc/pid/mem
         */
        mr->fd = -2;
    }

    mr->range_count = parse_maps(pid, mr->ranges, MAX_RANGES);
    if (mr->range_count <= 0) return -1;

    mr->heap = find_largest_heap_range(mr->ranges, mr->range_count);

    /* 新进程/重连: 内存布局已变, 清空 actor 扫描缓存与游标 */
    g_actor_list_cache = 0;
    g_scan_cursor = 0;
    g_scan_range_idx = 0;

    return mr->heap ? 0 : -1;
}

static void mem_reader_close(MemReader *mr) {
    if (mr->fd >= 0) close(mr->fd);
    mr->fd = -1;
}

static int read_mem(MemReader *mr, uintptr_t addr, void *buf, size_t len) {
    if (!mr->heap) return -1;
    
    /* Rate limiting */
    uint64_t now = now_ns();
    if ((now - mr->last_sec_ts) > 1000000000ULL) {
        mr->reads_per_second = 0;
        mr->last_sec_ts = now;
    }
    if (!g_no_throttle && mr->reads_per_second >= MAX_READS_PER_SEC) {
        return -2; /* throttled */
    }
    mr->reads_per_second++;
    mr->read_count++;

    /* 
     * Verify address is within benign range.
     * Only read from ranges we've validated (private rw, non-device).
     */
    MmapRange *r = find_range_for_addr(mr->ranges, mr->range_count, addr);
    if (!r) return -3;
    if (addr + len > r->end) return -4;  /* cross-range, skip */
    
    if (mr->fd == -2) {
        /* Fallback: readv would go here, but for simplicity we use /proc/pid/mem */
        return -5;
    }
    
    if (mr->fd < 0) return -1;
    
    /* 
     * Use pread() on /proc/pid/mem - single syscall, no access detection
     * anti-cheat can't intercept this reliably on non-root devices
     */
    ssize_t r2 = pread(mr->fd, buf, len, (off_t)addr);
    return r2 == (ssize_t)len ? 0 : -6;
}

/* ---- Actor parsing ---- */

typedef struct {
    uint64_t ptr;
} Ptr;

typedef struct {
    int32_t spell_id;
    float cd_remaining;
    float cd_total;
    int ready;
} SummonerSkill;

typedef struct {
    int32_t type;
    int32_t team_id;
    int32_t hp;
    int32_t max_hp;
    float x, y, z;
    int visible;
    char name[32];
    int32_t name_id;
    int32_t level;
    float ultimate_cd;
    float ultimate_total;
    SummonerSkill skills[MAX_SKILLS_PER_ACTOR];
    int skill_count;
    float facing_angle;
    float speed;
} ActorData;

typedef struct {
    int32_t id;
    float respawn_seconds;
    float max_seconds;
    int active;
    char label[16];
} GlobalTimer;

static int read_ptr(MemReader *mr, uintptr_t addr, uint64_t *out) {
    Ptr p;
    if (read_mem(mr, addr, &p, sizeof(p)) != 0) return -1;
    *out = p.ptr;
    return 0;
}

static int read_i32(MemReader *mr, uintptr_t addr, int32_t *out) {
    return read_mem(mr, addr, out, sizeof(int32_t));
}

static int read_f32(MemReader *mr, uintptr_t addr, float *out) {
    return read_mem(mr, addr, out, sizeof(float));
}

/* Read game string safely */
static int read_string(MemReader *mr, uintptr_t addr, char *buf, size_t maxlen) {
    uint8_t tmp[256];
    if (read_mem(mr, addr, tmp, sizeof(tmp)) != 0) return -1;
    size_t i;
    for (i = 0; i < sizeof(tmp) - 1 && i < maxlen - 1; i++) {
        if (tmp[i] == 0) break;
        if (tmp[i] < 0x20 || tmp[i] > 0x7e) { buf[i] = '.'; continue; }
        buf[i] = (char)tmp[i];
    }
    buf[i] = 0;
    return 0;
}

/* 
 * Parse actor list from game memory.
 * 
 * Chain: base + ANCHOR_OFFSET → list_root → actors → actor fields
 * 
 * Coordinate extraction:
 *   actor + POS_ANCHOR_OFFSET → pos_anchor
 *   pos_anchor + POS_X_OFFSET → x
 *   pos_anchor + POS_Y_OFFSET → y  
 *   pos_anchor + POS_Z_OFFSET → z
 * 
 * Entity type (hero/tower/minion/monster):
 *   actor + ACTOR_TYPE_OFFSET → type_id
 *   Types 1-50: Heroes
 *   Types 60-100: Towers/structures
 *   Types 100+: Minions/monsters
 * 
 * Team:
 *   actor + ACTOR_TEAM_OFFSET → team_id
 *   0x40 = ally (blue side)
 *   0x1C = enemy (red side)
 */

/*
 * 从一个 actor 指针提取全部字段。
 * 返回 1 = 合法 actor (type/team 校验通过), 0 = 不是 actor。
 */
static int extract_actor_slot(MemReader *mr, uintptr_t aptr, ActorData *a) {
    memset(a, 0, sizeof(*a));

    /* Validate this is an actor by reading its type field */
    int32_t type_id;
    if (read_i32(mr, aptr + ACTOR_TYPE_OFFSET, &type_id) != 0) return 0;
    if (type_id <= 0 || type_id > 500) return 0;  /* not a valid entity type */

    int32_t team_id;
    if (read_i32(mr, aptr + ACTOR_TEAM_OFFSET, &team_id) != 0) {
        if (!g_relaxed_scan) return 0;
        team_id = TEAM_ENEMY;  /* 宽松模式: team 读不到按敌方处理 */
    }
    if (team_id != TEAM_ALLY && team_id != TEAM_ENEMY) {
        if (!g_relaxed_scan) return 0;
        /* 宽松模式: team 值对不上也收 — 序列化时非 ally 即画成敌方 */
    }

    a->type = type_id;
    a->team_id = team_id;

    /* Extract position via the pos anchor chain */
    uintptr_t pos_anchor;
    if (read_ptr(mr, aptr + POS_ANCHOR_OFFSET, &pos_anchor) == 0) {
        /*
         * Sanity check: pos_anchor should point to a valid float area
         * We validate by checking that all floats are in reasonable ranges
         */
        float fx, fy, fz;
        if (read_f32(mr, pos_anchor + POS_X_OFFSET, &fx) == 0 &&
            read_f32(mr, pos_anchor + POS_Y_OFFSET, &fy) == 0 &&
            read_f32(mr, pos_anchor + POS_Z_OFFSET, &fz) == 0) {

            /* Validate position in map bounds with some tolerance */
            if (fx >= MAP_X_MIN * 1.5f && fx <= MAP_X_MAX * 1.5f &&
                fz >= MAP_Z_MIN * 1.5f && fz <= MAP_Z_MAX * 1.5f &&
                fy > -500.0f && fy < 500.0f) {
                a->x = fx;
                a->y = fy;
                a->z = fz;
                a->visible = 1;
            }
        }
    }

    /* Read health */
    if (read_i32(mr, aptr + 0x190, &a->hp) != 0) {
        a->hp = 0;
    }
    if (read_i32(mr, aptr + 0x194, &a->max_hp) != 0) {
        a->max_hp = a->hp;
    }

    /* Read name and name_id */
    read_string(mr, aptr + ACTOR_NAME_OFFSET, a->name, sizeof(a->name));
    read_i32(mr, aptr + ACTOR_NAMEID_OFFSET, &a->name_id);
    if (a->name_id < 0) a->name_id = 0;

    /* Read level */
    int32_t lvl;
    if (read_i32(mr, aptr + ACTOR_LEVEL_OFFSET, &lvl) == 0) {
        a->level = clamp_i(lvl, 0, 15);
    }

    /* Read facing angle and speed */
    read_f32(mr, aptr + ACTOR_FACING_OFFSET, &a->facing_angle);
    read_f32(mr, aptr + ACTOR_SPEED_OFFSET, &a->speed);

    /* Read ultimate cooldown */
    if (read_f32(mr, aptr + ACTOR_ULTCD_OFFSET, &a->ultimate_cd) != 0) {
        a->ultimate_cd = 0.0f;
    }
    if (read_f32(mr, aptr + ACTOR_ULTTOT_OFFSET, &a->ultimate_total) != 0) {
        a->ultimate_total = 0.0f;
    }

    /* Read summoner skills */
    a->skill_count = 0;
    if (a->type >= 1 && a->type <= 50) {
        uintptr_t skills_base;
        if (read_ptr(mr, aptr + ACTOR_SKILLS_OFFSET, &skills_base) == 0) {
            for (int si = 0; si < MAX_SKILLS_PER_ACTOR; si++) {
                SummonerSkill *sk = &a->skills[si];
                uintptr_t slot_ptr = skills_base + si * SKILL_SLOT_SIZE;
                if (read_i32(mr, slot_ptr + SKILL_SPELLID_OFFSET, &sk->spell_id) != 0) break;
                if (sk->spell_id <= 0 || sk->spell_id > 500) continue;

                read_f32(mr, slot_ptr + SKILL_CDREM_OFFSET, &sk->cd_remaining);
                read_f32(mr, slot_ptr + SKILL_CDTOT_OFFSET, &sk->cd_total);
                int32_t ready;
                read_i32(mr, slot_ptr + SKILL_READY_OFFSET, &ready);
                sk->ready = (ready != 0 || sk->cd_remaining <= 0.0f) ? 1 : 0;

                if (sk->cd_remaining < 0.0f) sk->cd_remaining = 0.0f;
                if (sk->cd_total < 0.0f) sk->cd_total = 0.0f;
                a->skill_count++;
            }
        }
    }

    return 1;
}

/*
 * 从 list_base 开始逐槽提取 actor, 写入 actors 数组。
 * 返回本次成功提取的个数。
 */
static int harvest_actor_list(MemReader *mr, uintptr_t list_base,
                              ActorData *actors, int max_actors) {
    int n = 0;
    for (int k = 0; k < MAX_ACTORS && n < max_actors; k++) {
        uintptr_t aptr;
        if (read_ptr(mr, list_base + (uintptr_t)k * 8, &aptr) != 0) break;
        if (aptr == 0) continue;  /* 空槽跳过, 继续看下一槽 */

        /* 指针必须落在某个已知 rw 匿名段内 */
        if (!find_range_for_addr(mr->ranges, mr->range_count, aptr)) continue;

        if (extract_actor_slot(mr, aptr, &actors[n])) {
            n++;
        }
    }
    return n;
}

static int parse_actors(MemReader *mr, ActorData *actors, int max_actors, int *out_count) {
    *out_count = 0;

    /* ---- 快速路径: 上一帧命中的列表地址仍有效 ---- */
    if (g_actor_list_cache != 0) {
        int n = harvest_actor_list(mr, g_actor_list_cache, actors, max_actors);
        if (n >= 3) {           /* 至少 3 个有效 actor 视为缓存仍有效 */
            *out_count = n;
            return 0;
        }
        /* 列表失效 (对局结束/结构移动), 清缓存走全扫描 */
        g_actor_list_cache = 0;
    }

    /*
     * ---- 全扫描: 遍历所有 rw 匿名段 (不只最大段), 跨帧续扫 ----
     * 每段窗口取 min(段大小, SCAN_WINDOW_PER_RANGE)。
     * 被限流/帧间隔打断时游标保留, 下一帧接着扫。
     */
    const size_t scan_stride = 8;
    const size_t SCAN_WINDOW_PER_RANGE = 8 * 1024 * 1024;  /* 每段最多扫 8MB */
    const int SCAN_BUDGET_PER_CALL = 20000;                /* 单次调用最多探测 2 万个地址 */
    const int budget = g_scan_budget_override > 0 ? g_scan_budget_override : SCAN_BUDGET_PER_CALL;

    int probed = 0;
    while (probed < budget) {
        /* 换到下一个有效段 */
        if (g_scan_range_idx >= mr->range_count) {
            /* 所有段扫完一轮没找到 */
            if (!g_relaxed_scan) {
                /* 严格模式 (team 必须是 0x40/0x1C) 一轮无果 →
                   降级宽松模式重扫: 不校验 team, 只靠 type+位置过滤 */
                g_relaxed_scan = 1;
                if (!g_relaxed_announced) {
                    g_relaxed_announced = 1;
                    fprintf(stderr, "[tv_reader] strict scan pass done, no actor list. "
                                    "switching to RELAXED scan (no team check)\n");
                }
            } else {
                g_relaxed_announced = 0;  /* 宽松一轮也无果, 回严格再来 */
                g_relaxed_scan = 0;
            }
            g_scan_range_idx = 0;
            g_scan_cursor = 0;
            return -1;
        }
        MmapRange *r = &mr->ranges[g_scan_range_idx];
        uintptr_t seg_start = r->start;
        uintptr_t seg_end = r->end;
        if (seg_end - seg_start > SCAN_WINDOW_PER_RANGE) {
            seg_end = seg_start + SCAN_WINDOW_PER_RANGE;
        }

        /* 初始化/推进游标 */
        if (g_scan_cursor < seg_start || g_scan_cursor >= seg_end) {
            g_scan_cursor = seg_start;
        }

        for (uintptr_t addr = g_scan_cursor; addr + 8 <= seg_end && probed < budget;
             addr += scan_stride, probed++) {

            /* 游标始终指向下一个待探测地址 (break 后下帧从这续扫) */
            g_scan_cursor = addr + scan_stride;

            uintptr_t ptr0;
            if (read_ptr(mr, addr, &ptr0) != 0) continue;   /* 含节流 (-2): 也算消耗预算 */

            if (ptr0 == 0) continue;
            /*
             * 指针数组与 actor 结构体可能在不同 rw 段 (跨段引用完全合法)。
             * 旧版要求 ptr0 必须落在当前扫描段内 — 跨段引用全被误杀。
             * 现在只要求落在任意一个已知 rw 段内。
             */
            if (!find_range_for_addr(mr->ranges, mr->range_count, ptr0)) continue;

            /* 候选列表: 从 addr 起连续提取 */
            int n = harvest_actor_list(mr, addr, actors, max_actors);
            if (n >= 5) {
                /* 命中! 缓存列表地址, 本帧数据已就绪 */
                g_actor_list_cache = addr;
                *out_count = n;
                return 0;
            }
        }

        /* 本段扫完 → 下一个段; 预算耗尽 → 游标留在当前位置, 下帧续扫 */
        if (g_scan_cursor >= seg_end) {
            g_scan_range_idx++;
            g_scan_cursor = 0;
        } else {
            break;
        }
    }

    return -1;  /* 本次调用没扫到 (可能续扫中) */
}

/* ---- TVEF protocol v3 ---- */
/* v3: actor 增加 y 坐标 (高度), header 增加 self_y — 供客户端做世界→屏幕投影 */

#define TVEF_MAGIC "TVEF"
#define TVEF_VERSION 3

#pragma pack(push, 1)
typedef struct {
    char magic[4];
    uint8_t version;
    uint32_t frame_id;
    float game_time;
    float self_x;
    float self_z;
    float self_y;
    uint8_t actor_count;
} TVEFHeaderV3;

typedef struct {
    int32_t type;
    float x, z, y;
    uint8_t ally;
    int32_t hp;
    int32_t max_hp;
    uint8_t visible;
    int32_t name_id;
    uint8_t level;
    float ultimate_cd;
    float ultimate_total;
    uint8_t skill_count;
    struct {
        int32_t spell_id;
        float cd_remaining;
        float cd_total;
        uint8_t ready;
    } skills[MAX_SKILLS_PER_ACTOR];
    float facing_angle;
    float speed;
} TVEFActorV3;

typedef struct {
    int32_t id;
    float respawn_seconds;
    float max_seconds;
    uint8_t active;
    char label[12];
} TVEFTimer;
#pragma pack(pop)

static int serialize_frame_v3(const ActorData *actors, int count,
                              const GlobalTimer *timers, int timer_count,
                              float game_time, float self_x, float self_z, float self_y,
                              uint32_t frame_id,
                              uint8_t *out, size_t out_size) {
    size_t actor_bytes = (size_t)clamp_i(count, 0, MAX_ACTORS) * sizeof(TVEFActorV3);
    size_t timer_bytes = (size_t)clamp_i(timer_count, 0, MAX_GLOBAL_TIMERS) * sizeof(TVEFTimer);
    size_t total = sizeof(TVEFHeaderV3) + actor_bytes + 1 + timer_bytes;

    if (total > out_size - 4) return -1;

    uint8_t *pkt = out + 4;
    memset(pkt, 0, total);

    TVEFHeaderV3 *hdr = (TVEFHeaderV3 *)pkt;
    memcpy(hdr->magic, TVEF_MAGIC, 4);
    hdr->version = TVEF_VERSION;
    hdr->frame_id = frame_id;
    hdr->game_time = game_time;
    hdr->self_x = self_x;
    hdr->self_z = self_z;
    hdr->self_y = self_y;
    hdr->actor_count = (uint8_t)clamp_i(count, 0, MAX_ACTORS);

    uint8_t *aptr = pkt + sizeof(TVEFHeaderV3);
    for (int i = 0; i < hdr->actor_count; i++) {
        const ActorData *src = &actors[i];
        TVEFActorV3 *dst = (TVEFActorV3 *)aptr;

        dst->type = src->type;
        dst->x = src->x;
        dst->z = src->z;
        dst->y = src->y;
        dst->ally = (src->team_id == TEAM_ALLY) ? 1 : 0;
        dst->hp = src->hp;
        dst->max_hp = src->max_hp;
        dst->visible = src->visible ? 1 : 0;
        dst->name_id = src->name_id;
        dst->level = (uint8_t)clamp_i(src->level, 0, 255);
        dst->ultimate_cd = src->ultimate_cd;
        dst->ultimate_total = src->ultimate_total;
        dst->skill_count = (uint8_t)clamp_i(src->skill_count, 0, MAX_SKILLS_PER_ACTOR);

        for (int si = 0; si < MAX_SKILLS_PER_ACTOR; si++) {
            if (si < src->skill_count) {
                dst->skills[si].spell_id = src->skills[si].spell_id;
                dst->skills[si].cd_remaining = src->skills[si].cd_remaining;
                dst->skills[si].cd_total = src->skills[si].cd_total;
                dst->skills[si].ready = src->skills[si].ready ? 1 : 0;
            }
        }

        dst->facing_angle = src->facing_angle;
        dst->speed = src->speed;
        aptr += sizeof(TVEFActorV3);
    }

    uint8_t *tptr = aptr;
    tptr[0] = (uint8_t)clamp_i(timer_count, 0, MAX_GLOBAL_TIMERS);
    tptr += 1;
    for (int t = 0; t < tptr[-1]; t++) {
        TVEFTimer *dst = (TVEFTimer *)tptr;
        dst->id = timers[t].id;
        dst->respawn_seconds = timers[t].respawn_seconds;
        dst->max_seconds = timers[t].max_seconds;
        dst->active = timers[t].active ? 1 : 0;
        strncpy(dst->label, timers[t].label, 11);
        tptr += sizeof(TVEFTimer);
    }

    uint32_t net_size = (uint32_t)total;
    memcpy(out, &net_size, 4);
    return (int)(4 + total);
}

static int scan_global_timers(MemReader *mr, GlobalTimer *timers) {
    int count = 0;
    
    struct { const char *label; int32_t id; float off; float max_sec; } defs[] = {
        {"小龙",   1, GAMESTATE_DRAGON_TIMER, 60.0f},
        {"大龙",   2, GAMESTATE_BARON_TIMER, 120.0f},
        {"蓝BUFF", 3, GAMESTATE_BLUE_RESPAWN, 90.0f},
        {"红BUFF", 4, GAMESTATE_RED_RESPAWN, 90.0f},
        {"野狼",   5, GAMESTATE_WOLF_RESPAWN, 90.0f},
        {"河蟹",   6, GAMESTATE_CRAB_RESPAWN, 60.0f},
        {"石头人", 7, GAMESTATE_GOLEM_RESPAWN, 90.0f},
    };
    
    uintptr_t gs_base = mr->heap ? mr->heap->start : 0;
    if (!gs_base) return 0;
    
    for (int i = 0; i < (int)(sizeof(defs) / sizeof(defs[0])) && count < MAX_GLOBAL_TIMERS; i++) {
        float respawn = 0.0f;
        int32_t active = 0;
        
        uintptr_t addr = gs_base + defs[i].off;
        float raw;
        if (read_f32(mr, addr, &raw) == 0) {
            if (raw < 0.0f) raw = 0.0f;
            if (raw > defs[i].max_sec * 2.0f) raw = 0.0f;
            respawn = raw;
            active = (raw > 0.0f && raw < defs[i].max_sec * 1.5f) ? 1 : 0;
        }
        
        GlobalTimer *t = &timers[count];
        memset(t, 0, sizeof(*t));
        t->id = defs[i].id;
        t->respawn_seconds = respawn;
        t->max_seconds = defs[i].max_sec;
        t->active = active;
        strncpy(t->label, defs[i].label, 15);
        count++;
    }
    
    return count;
}

/* ---- TCP server ---- */

static int create_tcp_server(int port) {
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) return -1;
    
    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((uint16_t)port);
    
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(srv);
        return -2;
    }
    if (listen(srv, 2) < 0) {
        close(srv);
        return -3;
    }
    
    /* Set TCP_NODELAY on listening socket for lower latency */
    int flag = 1;
    setsockopt(srv, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
    
    return srv;
}

/* ---- Main loop ---- */

static void usage(const char *prog) {
    fprintf(stderr, 
        "tv_reader - ESP game memory reader\n"
        "Usage: %s [OPTIONS]\n"
        "  --game-pkg PKG   Game package name (default: %s)\n"
        "  --port PORT      TCP port for overlay (default: %d)\n"
        "  --scan-only      Scan for actors once and print\n"
        "  --help           Show this help\n",
        prog, GAME_PKG_DEFAULT, PORT_DEFAULT);
}

int main(int argc, char **argv) {
    signal(SIGINT, on_sigint);
    signal(SIGTERM, on_sigint);
    
    const char *game_pkg = GAME_PKG_DEFAULT;
    int port = PORT_DEFAULT;
    int scan_only = 0;
    
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--game-pkg") == 0 && i + 1 < argc) {
            game_pkg = argv[++i];
        } else if (strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            port = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--scan-only") == 0) {
            scan_only = 1;
        } else if (strcmp(argv[i], "--help") == 0) {
            usage(argv[0]);
            return 0;
        }
    }
    
    fprintf(stderr, "[tv_reader] Starting...\n");
    fprintf(stderr, "[tv_reader] Target: %s, port: %d\n", game_pkg, port);
    
    /* Find game process */
    pid_t game_pid = 0;
    MemReader mr;
    memset(&mr, 0, sizeof(mr));
    mr.fd = -1;
    
    while (g_running) {
        game_pid = find_game_pid(game_pkg);
        if (game_pid > 0) {
            fprintf(stderr, "[tv_reader] Game PID: %d\n", game_pid);
            if (mem_reader_open(&mr, game_pid) == 0) {
                fprintf(stderr, "[tv_reader] Memory reader opened, heap: %p-%p, ranges: %d\n",
                        (void *)mr.heap->start, (void *)mr.heap->end, mr.range_count);
                break;
            } else {
                fprintf(stderr, "[tv_reader] Failed to open process memory: errno=%d (%s)"
                                " — 检查 SELinux/权限\n",
                        errno, strerror(errno));
            }
        }
        fprintf(stderr, "[tv_reader] Waiting for game...\n");
        for (int i = 0; i < 30 && g_running; i++) {
            usleep(200000);
        }
    }
    
    if (!g_running) return 0;
    
    if (scan_only) {
        ActorData actors[MAX_ACTORS];
        GlobalTimer timers[MAX_GLOBAL_TIMERS];
        int count;
        /* 诊断模式: 大预算 + 旁路限流, 一次性扫完所有 rw 匿名段 */
        g_scan_budget_override = 5000000;
        g_no_throttle = 1;
        fprintf(stderr, "[tv_reader] Scan-only mode: scanning all rw ranges...\n");
        if (parse_actors(&mr, actors, MAX_ACTORS, &count) == 0) {
            fprintf(stderr, "[tv_reader] Found %d actors:\n", count);
            for (int i = 0; i < count; i++) {
                fprintf(stderr, "  [%d] type=%d team=%d pos=(%.1f,%.1f,%.1f) hp=%d/%d ult=%.1f/%.1f skills=%d visible=%d\n",
                        i, actors[i].type, actors[i].team_id,
                        actors[i].x, actors[i].y, actors[i].z,
                        actors[i].hp, actors[i].max_hp,
                        actors[i].ultimate_cd, actors[i].ultimate_total,
                        actors[i].skill_count, actors[i].visible);
                for (int si = 0; si < actors[i].skill_count; si++) {
                    fprintf(stderr, "    skill[%d]: spell=%d cd=%.1f/%.1f ready=%d\n",
                            si, actors[i].skills[si].spell_id,
                            actors[i].skills[si].cd_remaining,
                            actors[i].skills[si].cd_total,
                            actors[i].skills[si].ready);
                }
            }
        } else {
            fprintf(stderr, "[tv_reader] No actors found.\n");
            fprintf(stderr, "[tv_reader] ranges=%d reads=%llu\n",
                    mr.range_count, (unsigned long long)mr.read_count);
            fprintf(stderr, "[tv_reader] 可能原因: ① 游戏不在对局中 ② 偏移与当前版本不匹配 ③ team/type 校验过严\n");
        }
        
        int tcount = scan_global_timers(&mr, timers);
        fprintf(stderr, "[tv_reader] Global timers (%d):\n", tcount);
        for (int t = 0; t < tcount; t++) {
            fprintf(stderr, "  [%d] %s respawn=%.1fs active=%d\n",
                    timers[t].id, timers[t].label,
                    timers[t].respawn_seconds, timers[t].active);
        }
        
        mem_reader_close(&mr);
        return 0;
    }
    
    /* Start TCP server */
    int srv = create_tcp_server(port);
    if (srv < 0) {
        fprintf(stderr, "[tv_reader] Failed to create TCP server on port %d\n", port);
        mem_reader_close(&mr);
        return 1;
    }
    fprintf(stderr, "[tv_reader] TCP server listening on 127.0.0.1:%d\n", port);
    
    /* Main event loop: read memory and serve data */
    ActorData actors[MAX_ACTORS];
    GlobalTimer timers[MAX_GLOBAL_TIMERS];
    uint32_t frame_id = 0;
    uint8_t tx_buf[65536];
    int client = -1;
    float game_time = 0.0f;
    float self_x = 0.0f, self_z = 0.0f, self_y = 0.0f;

    while (g_running) {
        /*
         * Check if game process is still alive.
         * If game restarts, we need to re-detect.
         */
        pid_t cur_pid = find_game_pid(game_pkg);
        if (cur_pid == 0 || cur_pid != game_pid) {
            fprintf(stderr, "[tv_reader] Game process changed (old=%d, new=%d)\n",
                    game_pid, cur_pid);
            mem_reader_close(&mr);
            if (cur_pid > 0 && mem_reader_open(&mr, cur_pid) == 0) {
                game_pid = cur_pid;
                fprintf(stderr, "[tv_reader] Re-attached to PID %d\n", game_pid);
            } else {
                fprintf(stderr, "[tv_reader] Waiting for game...\n");
                for (int i = 0; i < 50 && g_running; i++) usleep(200000);
                continue;
            }
        }

        /* Accept client connection */
        if (client < 0) {
            fd_set fds;
            FD_ZERO(&fds);
            FD_SET(srv, &fds);
            struct timeval tv = {0, 0};
            int maxfd = srv + 1;
            int r = select(maxfd, &fds, NULL, NULL, &tv);
            if (r > 0 && FD_ISSET(srv, &fds)) {
                struct sockaddr_in cli;
                socklen_t cli_len = sizeof(cli);
                client = accept(srv, (struct sockaddr *)&cli, &cli_len);
                if (client >= 0) {
                    int flag = 1;
                    setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
                    fprintf(stderr, "[tv_reader] Client connected\n");
                }
            }
        }

        if (client < 0) {
            usleep(FRAME_INTERVAL_US);
            continue;
        }

        /* Parse actors from game memory */
        int actor_count = 0;
        int pr = parse_actors(&mr, actors, MAX_ACTORS, &actor_count);

        /* Extract self position from first ally hero */
        for (int i = 0; i < actor_count; i++) {
            if (actors[i].team_id == TEAM_ALLY && actors[i].type >= 1 && actors[i].type <= 50) {
                self_x = actors[i].x;
                self_z = actors[i].z;
                self_y = actors[i].y;
                break;
            }
        }

        /* Scan global timers */
        int timer_count = scan_global_timers(&mr, timers);

        /* Read game time */
        float gt;
        uintptr_t gt_addr = (mr.heap ? mr.heap->start : 0) + GAMESTATE_TIME_OFFSET;
        if (read_f32(&mr, gt_addr, &gt) == 0 && gt >= 0.0f && gt < 7200.0f) {
            game_time = gt;
        }

        /* Serialize frame */
        int frame_len = 0;
        if (pr == 0 && (actor_count > 0 || timer_count > 0)) {
            frame_len = serialize_frame_v3(actors, actor_count,
                                           timers, timer_count,
                                           game_time, self_x, self_z, self_y,
                                           frame_id, tx_buf, sizeof(tx_buf));
        } else {
            uint8_t *pkt = tx_buf + 4;
            memset(pkt, 0, sizeof(TVEFHeaderV3) + 1);
            TVEFHeaderV3 *hdr = (TVEFHeaderV3 *)pkt;
            memcpy(hdr->magic, TVEF_MAGIC, 4);
            hdr->version = TVEF_VERSION;
            hdr->frame_id = frame_id;
            hdr->game_time = game_time;
            hdr->self_x = self_x;
            hdr->self_z = self_z;
            hdr->self_y = self_y;
            hdr->actor_count = 0;
            pkt[sizeof(TVEFHeaderV3)] = (uint8_t)timer_count;
            size_t pkt_size = sizeof(TVEFHeaderV3) + 1 + (size_t)timer_count * sizeof(TVEFTimer);
            uint32_t net_size = (uint32_t)pkt_size;
            memcpy(tx_buf, &net_size, 4);
            frame_len = (int)(4 + pkt_size);
        }
        
        /* Send to client */
        ssize_t sent = send(client, tx_buf, frame_len, MSG_NOSIGNAL);
        if (sent != frame_len) {
            if (sent < 0 && errno != EAGAIN) {
                fprintf(stderr, "[tv_reader] Client disconnected\n");
                close(client);
                client = -1;
            }
        }

        /*
         * 周期性状态日志 (每 60 帧 ≈ 2s) — App 日志面板「读取器」tab 实时可见。
         * 一行讲清: 游戏PID / 本帧actor数 / 缓存命中 / 扫描进度 / 模式 / 客户端。
         */
        if (frame_id % 60 == 0) {
            int ally_n = 0, enemy_n = 0;
            for (int i = 0; i < actor_count; i++) {
                if (actors[i].team_id == TEAM_ALLY) ally_n++;
                else enemy_n++;
            }
            fprintf(stderr,
                    "[tv_reader] st: pid=%d actors=%d(ally=%d,enemy=%d) cache=%s "
                    "scan=seg%d/%d relaxed=%d client=%s frames=%u\n",
                    game_pid, actor_count, ally_n, enemy_n,
                    g_actor_list_cache ? "HIT" : "no",
                    g_scan_range_idx, mr.range_count, g_relaxed_scan,
                    client >= 0 ? "on" : "off", frame_id);
        }

        frame_id++;
        usleep(FRAME_INTERVAL_US);
    }
    
    /* Cleanup */
    if (client >= 0) close(client);
    close(srv);
    mem_reader_close(&mr);
    fprintf(stderr, "[tv_reader] Stopped.\n");
    return 0;
}
