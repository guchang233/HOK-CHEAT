/* tv_mapread v2 — CRoomSystem.m_mapType 安全定向读 (P1)
 *
 * ★ 安全模型 (吸取 mass-scan 崩机教训 + 实测 maps 验证, 见 memory sgame-kpm-massscan-reboot):
 *   - 实测: 设备 buffer (kgsl-3d0 / dmabuf:qcom) 全是 rw-s (MAP_SHARED); CPU RAM (堆/栈/.data/bss)
 *     全是 rw-p (private). → private vs shared 是最强判据: 读 private 永不 fault, shared 才碰设备.
 *   - benign(可跟随读) = 私有映射('p') 且 非设备关键词. 允许无标签 private anon (il2cpp GC 堆在此),
 *     挡掉所有 shared + /dev/kgsl/mali/ion/dmabuf/...
 *   - 扫描区 = libil2cpp 路径匹配的 rw 段 (真实 maps 边界, 无 RVA 算术 —— libil2cpp 非连续映射,
 *     il_base+RVA 会错算到 224MB 完整性 blob 里).
 *   - is_benign_range(): offset 末端也校验 (防堆边缘溢出到相邻区).
 *   - 读上限 MAX_READS + 节流. 纯只读.
 *
 * 签名链 (workflow w4xwo1odg, cleartext disasm 确认):
 *   V(CRoomSystem*): rd=*(V+0x18); mapType=*(u8)(rd+0xc) 1..15; bInRoom=*(u8)(rd+0x14) 0/1;
 *   roomInfo=*(rd+0x30); roomAttrib=*(roomInfo+0x28)[指针]; bMapType=*(u8)(roomAttrib+0xa)==mapType(镜像).
 *
 * 编译: zig cc -target aarch64-linux-musl -O2 -static -s tv_mapread.c -o tv_mapread
 * 用法: /data/local/tmp/tv_mapread   (已匹配房间/对局中跑)
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
    "sgame-fakemem-v20","sgame-fakemem-v19","sgame-fakemem-v18","sgame-fakemem-v17",NULL };
static const char *KPM_NAME = "sgame-fakemem-v20";
#define SGAME_PKG "com.tencent.tmgp.sgame"
#define LIBIL2CPP "libil2cpp.so"
#define UNTAG(p) ((p) & 0xFFFFFFFFFFFFULL)

#define MAX_READS  300000     /* 全 benign 读, 安全; 堆预过滤+去重后实际远低于此 */
#define THROTTLE_EVERY 256
#define THROTTLE_US 300
#define DEDUP_BITS 21          /* 2^21=2M 槽 open-addressing 去重 (16MB) */
#define DEDUP_SIZE (1u<<DEDUP_BITS)
#define DEDUP_MASK (DEDUP_SIZE-1)

static uint32_t kp_version=0; static long g_reads=0;
static long ver_and_cmd(long c){ return ((long)kp_version<<32)|(0x2026L<<16)|(c&0xFFFF); }
static int kp_init(void){
    long ver=syscall(__NR_supercall,NULL,SUPERCALL_KERNELPATCH_VER);
    if(ver<0){ fprintf(stderr,"[!] supercall(45) failed: %s\n",strerror(errno)); return -1; }
    kp_version=(ver<0xa05)?0:(uint32_t)ver;
    if(syscall(__NR_supercall,NULL,ver_and_cmd(SUPERCALL_KERNELPATCH_VER))<0){
        fprintf(stderr,"[!] versioned supercall failed\n"); return -1; }
    return 0;
}
static int kpm_ctl(const char *a,char *o,int n){
    return (int)syscall(__NR_supercall,NULL,ver_and_cmd(SUPERCALL_KPM_CONTROL),KPM_NAME,a,o,(long)n); }
static int parse_r(const char *r,uint8_t *b,int max){
    if(!r||r[0]=='E'||r[0]!='R'||r[1]!=':') return -1;
    const char *p=r+2; int n=0; while(*p>='0'&&*p<='9') n=n*10+(*p++ - '0'); if(*p==' ')p++;
    if(n>max)n=max;
    for(int i=0;i<n;i++){ char x=p[i*2],y=p[i*2+1]; if(!x||!y)return i;
        uint8_t hi=(x>='a'?x-'a'+10:x-'0'),lo=(y>='a'?y-'a'+10:y-'0'); b[i]=(hi<<4)|lo; } return n;
}
static int kpm_read(int pid,uint64_t addr,void *out,int len){
    if(g_reads>=MAX_READS){ fprintf(stderr,"\n[!] 达到读上限 %d abort\n",MAX_READS); return -2; }
    g_reads++; if((g_reads%THROTTLE_EVERY)==0) usleep(THROTTLE_US);
    char cmd[64],resp[8192]; snprintf(cmd,sizeof(cmd),"r %d %lx %x",pid,(unsigned long)addr,len);
    memset(resp,0,sizeof(resp)); if(kpm_ctl(cmd,resp,sizeof(resp))<0) return -1;
    return parse_r(resp,(uint8_t*)out,len);
}
static int rd8(int pid,uint64_t a,uint64_t *o){ uint64_t v=0; if(kpm_read(pid,a,&v,8)<8) return -1; *o=UNTAG(v); return 0; }

static int find_sgame_pid(void){
    DIR *d=opendir("/proc"); if(!d) return -1; struct dirent *e; int found=-1;
    while((e=readdir(d))){ if(e->d_name[0]<'0'||e->d_name[0]>'9') continue;
        char p[64]; snprintf(p,sizeof(p),"/proc/%s/cmdline",e->d_name);
        int fd=open(p,O_RDONLY); if(fd<0)continue; char b[256]={0}; ssize_t r=read(fd,b,255); close(fd);
        if(r>0&&strcmp(b,SGAME_PKG)==0){ found=atoi(e->d_name); break; } }
    closedir(d); return found;
}

/* ---- maps ---- */
#define MAXR 8192
static uint64_t b_lo[MAXR],b_hi[MAXR]; static int nb=0;   /* benign: private 且 非设备 (跟随指针只能落这) */
static uint64_t h_lo[MAXR],h_hi[MAXR]; static int nh=0;   /* GC 堆候选: libc_malloc + 大块无标签 private anon */
#define MAXS 64
static uint64_t s_lo[MAXS],s_hi[MAXS]; static int ns=0;   /* 扫描区: libil2cpp 路径 rw 段 */

static void sort2(uint64_t *lo,uint64_t *hi,int n){
    for(int i=1;i<n;i++){ uint64_t a=lo[i],b=hi[i]; int j=i-1;
        while(j>=0&&lo[j]>a){ lo[j+1]=lo[j]; hi[j+1]=hi[j]; j--; } lo[j+1]=a; hi[j+1]=b; }
}
static int in_set(uint64_t *lo,uint64_t *hi,int n,uint64_t a){
    int l=0,r=n-1; while(l<=r){ int m=(l+r)/2;
        if(a<lo[m]) r=m-1; else if(a>=hi[m]) l=m+1; else return 1; } return 0;
}
/* ★ benign 门: 落在某个 private-非设备 区. 跟随指针读前必须过. */
static int is_benign(uint64_t a){ if(a<0x10000) return 0; return in_set(b_lo,b_hi,nb,a); }
static int is_benign_range(uint64_t a,uint64_t len){ return is_benign(a) && is_benign(a+len-1); }
/* ★ 堆指针预过滤: 候选 V 必须指向 GC 堆 (CRoomSystem 对象所在), 本地判断不读内存 → 砍候选 */
static int is_heap(uint64_t a){ if(a<0x10000) return 0; return in_set(h_lo,h_hi,nh,a); }

/* ---- 去重 (open-addressing, 0=空) ---- */
static uint64_t *g_seen=NULL;
static int seen_add(uint64_t v){            /* 新→1, 已见→0 */
    if(!v) return 0;
    uint64_t h=(v>>3)*0x9E3779B97F4A7C15ULL; uint32_t i=(uint32_t)(h>>(64-DEDUP_BITS));
    for(uint32_t n=0;n<DEDUP_SIZE;n++){ uint32_t k=(i+n)&DEDUP_MASK;
        if(g_seen[k]==0){ g_seen[k]=v; return 1; } if(g_seen[k]==v) return 0; }
    return 1;                                 /* 满了(不会), 当新处理 */
}

static int line_is_device(const char *line){
    return (strstr(line,"/dev/")||strstr(line,"kgsl")||strstr(line,"mali")||strstr(line,"ion")||
            strstr(line,"dmabuf")||strstr(line,"memfd")||strstr(line,"/gpu")||strstr(line,"faceless")||
            strstr(line,"render")||strstr(line,"/dri/")||strstr(line,"adreno")||strstr(line,"powervr"));
}
static void parse_maps(int pid){
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    FILE *f=fopen(path,"r"); if(!f) return; char line[1024];
    while(fgets(line,sizeof(line),f)){
        uint64_t st,en; char perms[8]={0};
        if(sscanf(line,"%lx-%lx %4s",&st,&en,perms)!=3) continue;
        if(perms[0]!='r') continue;
        uint64_t sz=en-st; int dev=line_is_device(line);
        /* benign = private('p') 且 非设备. 实测设备 buffer 全是 shared('s'); private 永不 fault. */
        if(perms[3]=='p' && !dev && nb<MAXR){ b_lo[nb]=st; b_hi[nb]=en; nb++; }
        /* 扫描区 = libil2cpp 路径 + 'w' (rw-p / rwxp) — 真实边界, 文件映射, 安全 */
        if(strstr(line,LIBIL2CPP) && perms[1]=='w' && !dev && ns<MAXS){ s_lo[ns]=st; s_hi[ns]=en; ns++; }
        /* GC 堆候选 = private rw, 非设备, ≥256KB, 且 (libc_malloc 或 裸anon无路径/无标签);
         * 排除 dalvik(ART Java 堆, 带标签) 和文件映射. CRoomSystem 对象落在这. */
        if(perms[0]=='r'&&perms[1]=='w'&&perms[3]=='p' && !dev && sz>=262144 && nh<MAXR){
            int bare=(!strchr(line,'/') && !strchr(line,'['));
            if(strstr(line,"libc_malloc")||bare){ h_lo[nh]=st; h_hi[nh]=en; nh++; }
        }
    }
    fclose(f);
    sort2(b_lo,b_hi,nb);
    sort2(h_lo,h_hi,nh);
}

static const char* mode_name(int mt){
    switch(mt){ case 1:return "VERSUS(娱乐PvP)"; case 2:return "COUNTERPART"; case 3:return "RANK(排位5v5)";
        case 4:return "ENTERTAINMENT(娱乐)"; case 5:return "REWARDMATCH"; case 6:return "GUILDMATCH";
        case 7:return "COMPETITION"; case 8:return "MASTER(巅峰)"; case 9:return "UGC"; case 10:return "ESPORTS";
        case 11:return "RANK10V10"; case 12:return "MASTER2V2"; case 14:return "PRETEAM"; default:return "?"; }
}

/* V 当 CRoomSystem* 验证 (镜像). 命中 1. 全程 is_benign_range 门控. */
static int check_inst(int pid,uint64_t V,uint64_t *o_rd,int *o_mt,uint32_t *o_mapid,int *o_binr){
    if(!is_benign_range(V,0x20)) return 0;
    uint64_t rd; if(rd8(pid,V+0x18,&rd)<0) return 0;
    if(!is_benign_range(rd,0x38)) return 0;
    uint8_t b16[16]; if(kpm_read(pid,rd+0x08,b16,16)<16) return 0;
    int mt=b16[0x0c-0x08], binr=b16[0x14-0x08];
    if(mt<1||mt>15) return 0; if(binr<0||binr>1) return 0;
    uint64_t roomInfo; if(rd8(pid,rd+0x30,&roomInfo)<0||!is_benign_range(roomInfo,0x30)) return 0;
    uint64_t roomAttrib; if(rd8(pid,roomInfo+0x28,&roomAttrib)<0||!is_benign_range(roomAttrib,0x0b)) return 0;
    uint8_t bmt=0xFF; if(kpm_read(pid,roomAttrib+0x0a,&bmt,1)<1) return 0;
    if(bmt!=mt) return 0;                          /* ★ 镜像必须一致 */
    *o_rd=rd; *o_mt=mt; *o_mapid=*(uint32_t*)(b16+0); *o_binr=binr; return 1;
}

int main(void){
    setbuf(stdout,NULL);
    printf("=== tv_mapread v3 (CRoomSystem.m_mapType 安全定向读 + 堆预过滤+去重) ===\n");
    if(kp_init()<0) return 1;
    printf("[init] KPatch ver=0x%x\n",kp_version);
    char resp[1024]={0}; int ok=0;
    for(int i=0;KPM_CANDIDATES[i];i++){ KPM_NAME=KPM_CANDIDATES[i]; memset(resp,0,sizeof(resp));
        if(kpm_ctl("s",resp,sizeof(resp))==0){ ok=1; printf("[init] KPM %s ok\n",KPM_NAME); break; } }
    if(!ok){ fprintf(stderr,"[!] no KPM\n"); return 2; }

    int pid=find_sgame_pid();
    if(pid<=0){ fprintf(stderr,"[!] sgame 未运行\n"); return 3; }
    parse_maps(pid);
    printf("[maps] pid=%d benign(private非设备)区=%d  GC堆候选区=%d  libil2cpp-rw 扫描区=%d\n",pid,nb,nh,ns);
    if(ns==0){ fprintf(stderr,"[!] libil2cpp rw 段未找到 (sgame 没进战斗?)\n"); return 4; }
    if(nh==0){ fprintf(stderr,"[!] GC 堆候选未找到\n"); return 4; }
    g_seen=calloc(DEDUP_SIZE,sizeof(uint64_t));
    if(!g_seen){ fprintf(stderr,"[!] 去重表分配失败\n"); return 4; }

    uint64_t total=0;
    for(int i=0;i<ns;i++){ total+=s_hi[i]-s_lo[i];
        printf("  scan[%d] %lx-%lx (%lu KB)\n",i,(unsigned long)s_lo[i],(unsigned long)s_hi[i],
               (unsigned long)((s_hi[i]-s_lo[i])/1024)); }
    printf("[safe] 扫描区仅 libil2cpp 路径 rw 段 (%lu KB); 跟随指针仅落 private 非设备区; 读上限 %d.\n",
           (unsigned long)(total/1024),MAX_READS);

    /* ★ 探测 KPM 'r' 单次最大读长 (reader 实测 ≤240 可行; v2 的 2048 全失败 = 超限).
     * 在最大 scan 区起址探测, 选能完整返回的最大尺寸. */
    int big=0; for(int i=1;i<ns;i++) if(s_hi[i]-s_lo[i] > s_hi[big]-s_lo[big]) big=i;
    int CHUNK=0; { int sz[]={1024,512,256,240,128,64,8}; uint8_t pb[1024];
        for(int i=0;i<7;i++){ int g=kpm_read(pid,s_lo[big],pb,sz[i]);
            printf("[probe] read %d @ %lx -> got %d\n",sz[i],(unsigned long)s_lo[big],g);
            if(g>=sz[i]){ CHUNK=sz[i]; break; } } }
    if(CHUNK==0){ fprintf(stderr,"[!] 连 8 字节都读不到 scan 区 (%lx), abort\n",(unsigned long)s_lo[big]); return 7; }
    printf("[probe] 选定 CHUNK=%d (预计 ~%lu chunk reads)\n",CHUNK,(unsigned long)(total/CHUNK));

    uint8_t *buf=malloc(CHUNK);
    int hits=0; uint64_t scanned=0,cand=0;
    for(int ri=0;ri<ns && g_reads<MAX_READS;ri++){
        for(uint64_t a=s_lo[ri]; a<s_hi[ri] && g_reads<MAX_READS; a+=CHUNK){
            int want=(int)((s_hi[ri]-a<CHUNK)?(s_hi[ri]-a):CHUNK);
            int got=kpm_read(pid,a,buf,want);
            if(got<8) continue;
            for(int off=0; off+8<=got; off+=8){
                scanned++;
                uint64_t V=UNTAG(*(uint64_t*)(buf+off));
                if(!is_heap(V)) continue;       /* ★ 堆预过滤: 只验指向 GC 堆的指针 (本地, 砍候选) */
                if(!seen_add(V)) continue;       /* ★ 去重: 同一 V 只验一次 */
                cand++;
                uint64_t rd; int mt,binr; uint32_t mapid;
                if(check_inst(pid,V,&rd,&mt,&mapid,&binr)){
                    hits++;
                    printf("\n[HIT#%d] slot@%lx  CRoomSystem*=%lx  RoomData*=%lx\n",
                           hits,(unsigned long)(a+off),(unsigned long)V,(unsigned long)rd);
                    printf("   ★ m_mapType=%d (%s)  mapId=%u  bInRoom=%d\n",mt,mode_name(mt),mapid,binr);
                }
            }
        }
    }
    free(buf); free(g_seen);
    printf("\n[done] 扫描 %lu slots, 堆指针候选(去重后) %lu, 命中 %d, 总读 %ld.\n",
           (unsigned long)scanned,(unsigned long)cand,hits,g_reads);
    if(hits==0) printf("[done] 0 命中. 若在对局中仍 0 → s_instance 不在 libil2cpp .data (在 GC 堆 static_fields), 暴力不可行 → 转新P1.\n");
    return 0;
}
