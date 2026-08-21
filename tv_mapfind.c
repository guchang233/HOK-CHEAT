/* tv_mapfind — CRoomSystem.m_mapType 只读发现工具 (P1 实验)
 *
 * 目标: 在 libil2cpp 的 rw/bss 区用"强签名扫描"找到 CRoomSystem* (s_instance),
 *       从而读到 RoomData.m_mapType (排位=3 / 娱乐=4 / ...). 纯只读, 零写入, 零风险.
 *
 * 签名链 (来自 workflow w612jgz0x locate 结论 + get_MapType@0x8b2c068 实测):
 *   候选 slot 值 V 解释为 inst (CRoomSystem*):
 *     rd       = *(V + 0x18)            RoomData*           (get_MapType: ldr x8,[x0,#0x18])
 *     mapType  = *(u8)(rd + 0x0c)       1..15               (ldrb w0,[x8,#0xc])
 *     bInRoom  = *(u8)(rd + 0x14)       0/1
 *     roomInfo = *(u64)(rd + 0x30)
 *     bMapType = *(u8)(roomInfo + 0x32) == mapType  ← 镜像双字段必须一致 (roomAttrib@+0x28 +0xa)
 *   也尝试解释为 cls (Il2CppClass*): sf=*(V+0xb8); inst=*(sf+0); 再走上面的 inst 链.
 *
 * 镜像 + bInRoom + mapType 范围三重约束 → 几乎不可能误命中.
 *
 * 编译: zig cc -target aarch64-linux-musl -O2 -static -s tv_mapfind.c -o tv_mapfind
 * 用法: /data/local/tmp/tv_mapfind          (在一局对局/已匹配房间中跑, m_mapType 才有值)
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/syscall.h>
#include <stdint.h>
#include <errno.h>

#define __NR_supercall 45
#define SUPERCALL_KERNELPATCH_VER 0x1008
#define SUPERCALL_KPM_CONTROL 0x1022

static const char *KPM_CANDIDATES[] = {
    "sgame-fakemem-v20","sgame-fakemem-v19","sgame-fakemem-v18","sgame-fakemem-v17",NULL
};
static const char *KPM_NAME = "sgame-fakemem-v20";
#define SGAME_PKG "com.tencent.tmgp.sgame"
#define LIBIL2CPP "libil2cpp.so"
#define UNTAG(p) ((p) & 0xFFFFFFFFFFFFULL)

/* ---- supercall / KPM (抄 reader.c) ---- */
static uint32_t kp_version = 0;
static long ver_and_cmd(long cmd){ return ((long)kp_version<<32)|(0x2026L<<16)|(cmd&0xFFFF); }
static int kp_init(void){
    long ver = syscall(__NR_supercall, NULL, SUPERCALL_KERNELPATCH_VER);
    if (ver < 0){ fprintf(stderr,"[!] supercall(45) failed: %s\n", strerror(errno)); return -1; }
    kp_version = (ver < 0xa05) ? 0 : (uint32_t)ver;
    if (syscall(__NR_supercall, NULL, ver_and_cmd(SUPERCALL_KERNELPATCH_VER)) < 0){
        fprintf(stderr,"[!] versioned supercall failed: %s\n", strerror(errno)); return -1; }
    return 0;
}
static int kpm_ctl(const char *args, char *out, int outlen){
    return (int)syscall(__NR_supercall, NULL, ver_and_cmd(SUPERCALL_KPM_CONTROL),
                        KPM_NAME, args, out, (long)outlen);
}
static int parse_r(const char *resp, uint8_t *buf, int max_len){
    if (!resp || resp[0]=='E' || resp[0]!='R' || resp[1]!=':') return -1;
    const char *p = resp+2; int n=0;
    while (*p>='0'&&*p<='9') n=n*10+(*p++ - '0');
    if (*p==' ') p++;
    if (n>max_len) n=max_len;
    for (int i=0;i<n;i++){
        char a=p[i*2], b=p[i*2+1];
        if (!a||!b) return i;
        uint8_t hi=(a>='a'?a-'a'+10:a-'0'), lo=(b>='a'?b-'a'+10:b-'0');
        buf[i]=(hi<<4)|lo;
    }
    return n;
}
static int kpm_read(int pid, uint64_t addr, void *out, int len){
    char cmd[64], resp[8192];
    snprintf(cmd,sizeof(cmd),"r %d %lx %x", pid,(unsigned long)addr,len);
    memset(resp,0,sizeof(resp));
    if (kpm_ctl(cmd,resp,sizeof(resp))<0) return -1;
    return parse_r(resp,(uint8_t*)out,len);
}
static int rd8(int pid, uint64_t addr, uint64_t *out){
    uint64_t v=0; if (kpm_read(pid,addr,&v,8)<8) return -1; *out=UNTAG(v); return 0;
}

/* ---- pid ---- */
static int find_sgame_pid(void){
    DIR *d=opendir("/proc"); if(!d) return -1;
    struct dirent *e; int found=-1;
    while((e=readdir(d))){
        if (e->d_name[0]<'0'||e->d_name[0]>'9') continue;
        char path[64]; snprintf(path,sizeof(path),"/proc/%s/cmdline",e->d_name);
        int fd=open(path,O_RDONLY); if(fd<0) continue;
        char buf[256]={0}; ssize_t r=read(fd,buf,sizeof(buf)-1); close(fd);
        if (r>0 && strcmp(buf,SGAME_PKG)==0){ found=atoi(e->d_name); break; }
    }
    closedir(d); return found;
}

/* ---- maps ---- */
#define MAX_R 4096
static uint64_t r_lo[MAX_R], r_hi[MAX_R]; static int n_r=0;     /* 全部可读区 (排序) */
#define MAX_S 256
static uint64_t s_lo[MAX_S], s_hi[MAX_S]; static int n_s=0;     /* 扫描区 (il2cpp rw + bss) */

static void sort_ranges(void){
    for (int i=1;i<n_r;i++){ uint64_t a=r_lo[i],b=r_hi[i]; int j=i-1;
        while(j>=0 && r_lo[j]>a){ r_lo[j+1]=r_lo[j]; r_hi[j+1]=r_hi[j]; j--; }
        r_lo[j+1]=a; r_hi[j+1]=b; }
}
static int is_mapped(uint64_t a){
    if (a < 0x10000) return 0;
    int lo=0, hi=n_r-1;
    while (lo<=hi){ int m=(lo+hi)/2;
        if (a < r_lo[m]) hi=m-1;
        else if (a >= r_hi[m]) lo=m+1;
        else return 1; }
    return 0;
}
static void parse_maps(int pid){
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    FILE *f=fopen(path,"r"); if(!f) return;
    char line[1024]; int seen_il=0;
    while (fgets(line,sizeof(line),f)){
        uint64_t st,en; char perms[8]={0};
        if (sscanf(line,"%lx-%lx %4s",&st,&en,perms)!=3) continue;
        if (perms[0]=='r' && n_r<MAX_R){ r_lo[n_r]=st; r_hi[n_r]=en; n_r++; }
        int is_il = (strstr(line,LIBIL2CPP)!=NULL);
        if (is_il) seen_il=1;
        /* 扫描区: libil2cpp 'rw' 段 + libil2cpp 之后第一个 anon bss */
        if (n_s<MAX_S){
            if (is_il && perms[0]=='r' && perms[1]=='w'){ s_lo[n_s]=st; s_hi[n_s]=en; n_s++; }
            else if (seen_il && !is_il && strstr(line,"[anon:.bss]") && perms[0]=='r'){
                s_lo[n_s]=st; s_hi[n_s]=en; n_s++;
            }
        }
    }
    fclose(f);
    sort_ranges();
}

/* ---- 验证一个 inst 候选, 命中返回 1 并填出参 ---- */
static int check_inst(int pid, uint64_t inst, uint64_t *out_rd, int *out_mt,
                      uint32_t *out_mapid, int *out_roomtype, int *out_binroom){
    uint64_t rd; if (rd8(pid, inst+0x18, &rd)<0 || !is_mapped(rd)) return 0;
    uint8_t b16[16];
    if (kpm_read(pid, rd+0x08, b16, 16) < 16) return 0;   /* +8 mapId, +c mapType, +10 roomType, +14 bInRoom */
    int mt   = b16[0x0c-0x08];
    int binr = b16[0x14-0x08];
    if (mt<1 || mt>15) return 0;
    if (binr<0 || binr>1) return 0;
    uint64_t roomInfo; if (rd8(pid, rd+0x30, &roomInfo)<0 || !is_mapped(roomInfo)) return 0;
    uint8_t bmt=0xFF; if (kpm_read(pid, roomInfo+0x32, &bmt, 1)<1) return 0;
    if (bmt != mt) return 0;                               /* ★ 镜像必须一致 */
    *out_rd=rd; *out_mt=mt;
    *out_mapid = *(uint32_t*)(b16+0);                       /* +8 mapId */
    *out_roomtype = *(int32_t*)(b16+0x10-0x08);
    *out_binroom = binr;
    return 1;
}

static const char* mode_name(int mt){
    switch(mt){
        case 1:return "VERSUS(娱乐对战PvP)"; case 2:return "COUNTERPART";
        case 3:return "RANK(排位5v5)"; case 4:return "ENTERTAINMENT(娱乐)";
        case 5:return "REWARDMATCH"; case 6:return "GUILDMATCH"; case 7:return "COMPETITION";
        case 8:return "MASTER(巅峰赛)"; case 9:return "UGC"; case 10:return "ESPORTS";
        case 11:return "RANK10V10"; case 12:return "MASTER2V2"; case 14:return "PRETEAM";
        default:return "?";
    }
}

int main(void){
    setbuf(stdout,NULL);
    printf("=== tv_mapfind (CRoomSystem.m_mapType 只读发现) ===\n");
    if (kp_init()<0) return 1;
    printf("[init] KPatch ver=0x%x\n", kp_version);
    char resp[1024]={0}; int ok=0;
    for (int i=0;KPM_CANDIDATES[i];i++){ KPM_NAME=KPM_CANDIDATES[i]; memset(resp,0,sizeof(resp));
        if (kpm_ctl("s",resp,sizeof(resp))==0){ ok=1; printf("[init] KPM %s: %s",KPM_NAME,resp); break; } }
    if (!ok){ fprintf(stderr,"[!] no KPM loaded\n"); return 2; }

    int pid=find_sgame_pid();
    if (pid<=0){ fprintf(stderr,"[!] sgame 未运行\n"); return 3; }
    parse_maps(pid);
    printf("[maps] pid=%d 可读区=%d 扫描区=%d\n", pid, n_r, n_s);
    if (n_s==0){ fprintf(stderr,"[!] 没找到 libil2cpp rw/bss 扫描区\n"); return 4; }

    uint64_t total=0; for (int i=0;i<n_s;i++){ total += s_hi[i]-s_lo[i];
        printf("  scan[%d] %lx-%lx (%lu KB)\n", i,(unsigned long)s_lo[i],(unsigned long)s_hi[i],
               (unsigned long)((s_hi[i]-s_lo[i])/1024)); }
    printf("[scan] 总 %lu KB, 强签名 (inst链 + cls链 + 镜像校验)...\n",(unsigned long)(total/1024));

    enum { CHUNK = 2048 };
    uint8_t *buf = malloc(CHUNK);
    int hits=0; uint64_t slots_scanned=0, ptr_slots=0;

    for (int ri=0; ri<n_s; ri++){
        for (uint64_t a=s_lo[ri]; a<s_hi[ri]; a+=CHUNK){
            int want = (int)((s_hi[ri]-a < CHUNK)? (s_hi[ri]-a) : CHUNK);
            int got = kpm_read(pid, a, buf, want);
            if (got < 8) continue;
            for (int off=0; off+8<=got; off+=8){
                slots_scanned++;
                uint64_t V = UNTAG(*(uint64_t*)(buf+off));
                if (!is_mapped(V)) continue;
                ptr_slots++;
                uint64_t slot_addr = a+off;
                uint64_t rd; int mt,rt,binr; uint32_t mapid;
                /* 解释 1: V = inst */
                if (check_inst(pid, V, &rd,&mt,&mapid,&rt,&binr)){
                    hits++;
                    printf("\n[HIT#%d inst] slot@%lx  CRoomSystem*=%lx  RoomData*=%lx\n",
                           hits,(unsigned long)slot_addr,(unsigned long)V,(unsigned long)rd);
                    printf("   m_mapType=%d (%s)  mapId=%u  roomType=%d  bInRoom=%d\n",
                           mt, mode_name(mt), mapid, rt, binr);
                    continue;
                }
                /* 解释 2: V = cls (Il2CppClass*) → static_fields@+0xb8 → s_instance@+0 */
                uint64_t sf, inst2;
                if (rd8(pid, V+0xb8, &sf)==0 && is_mapped(sf) &&
                    rd8(pid, sf+0x00, &inst2)==0 && is_mapped(inst2) &&
                    check_inst(pid, inst2, &rd,&mt,&mapid,&rt,&binr)){
                    hits++;
                    printf("\n[HIT#%d cls] slot@%lx  Il2CppClass*=%lx  s_instance=%lx  RoomData*=%lx\n",
                           hits,(unsigned long)slot_addr,(unsigned long)V,(unsigned long)inst2,(unsigned long)rd);
                    printf("   m_mapType=%d (%s)  mapId=%u  roomType=%d  bInRoom=%d\n",
                           mt, mode_name(mt), mapid, rt, binr);
                    printf("   ★ slot@%lx 是 class-ptr slot (libil2cpp_base+S), 适合做持久 KPM 链\n",
                           (unsigned long)slot_addr);
                }
            }
        }
    }
    free(buf);
    printf("\n[done] 扫描 %lu slots, 其中 %lu 个有效指针, 命中 %d 个.\n",
           (unsigned long)slots_scanned,(unsigned long)ptr_slots,hits);
    if (hits==0) printf("[done] 未命中 — 可能不在 rw/bss (需 metadata walk Route A), 或当前不在房间 (m_mapType 未设).\n");
    return 0;
}
