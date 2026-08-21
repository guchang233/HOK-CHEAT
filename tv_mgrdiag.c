/* tv_mgrdiag — 路2 全局位置管理器(0x50157C8)实证诊断 vs DD数组 vs +0x268雾链
 *
 * workflow w8j6o9k3s 判决: 路2 纸面 NO-GO(~0.30), 三个反汇编阻断:
 *   #1 取键(posStruct+0xc)要走 +0x268 雾链   #2 查不到返回哨兵   #3 recordObj+0x228 是陈旧缓存,
 *      生产者经 +0x268 读 → 深雾下 +0x228 陈旧/垃圾, 不旁路雾
 *   且"34 BYPASS 实测"被坐实是 DD 数组(gc_bss+0x1C4980)而非本管理器(gc_bss+0x17B7C8)的功劳.
 * 静态已到极限(producer 类零入边), 只能上设备实测. 本工具就是那个实测.
 *
 * 三问(一局答完):
 *   ① 管理器: 深雾敌人(268被清)的 recordObj+0x228/+0x90 是否随移动"变化"? 变=路2活, 冻结=路2死.
 *   ② DD: 深雾敌人在 DD 里还在不在? 验证 DD 只覆盖宽限期 vs 覆盖深雾.
 *   ③ dump 整张管理器表(table 头 + 全 entry + recordObj 字段) 供离线找真 sim 权威存储.
 *
 * 方法(纯读, 不改, bounded, 每跳 is_benign):
 *   敌人"可见"帧用 chain268 终点 posStruct+0xc 学到 objID→key 映射;
 *   敌人进深雾后按已学 key 继续在管理器查 → 看 +0x228/+0x90 是否还动.
 *
 * 链(workflow 解码, 全静态地址 gc_bss-relative):
 *   manager = *( *(gc_bss+0x50157C8) + 0x70 )
 *   table   = *(manager+0x10);  gen 守卫 *(u32)(table+0xc)==*(u32)(manager+0x18)
 *   entries = table+0x10 起, stride 0x20: +0=u32 key, +8=recordObj, +0x10=u32 gen
 *   record gen 守卫 *(u32)(rec+0xc)==entry+0x10
 *   rec+0x1f9==0(simple) → VInt3 int32 @ +0x228(x)/+0x22c(z)/+0x230(y), /1000 米
 *   rec+0x90 = A4 称"live VInt3 管理器副本"(与 +0x228 并读, 谁动看谁)
 *   哨兵: SA=*(gc_bss+0x4d68480);  SB=*(*(gc_bss+0x4c52048))  — rec 命中哨兵=无此实体记录
 *
 * 安全: 全定向读 + 每跳 is_benign(private 非设备) + bounded(<=256 entry) + fsync 每行存盘.
 * 编译: zig cc -target aarch64-linux-musl -O2 -static -s tv_mgrdiag.c -o tv_mgrdiag
 * 用法: /data/local/tmp/tv_mgrdiag [帧数=40]   (建议 30-60, 让敌人有进/出雾过程)
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

#define __NR_supercall 45
#define SUPERCALL_KERNELPATCH_VER 0x1008
#define SUPERCALL_KPM_CONTROL 0x1022
static const char *KPM_CANDIDATES[]={"sgame-fakemem-v20","sgame-fakemem-v19","sgame-fakemem-v18","sgame-fakemem-v17",NULL};
static const char *KPM_NAME="sgame-fakemem-v20";
#define SGAME_PKG "com.tencent.tmgp.sgame"
#define LIBGC "libGameCore.so"
#define UNTAG(p) ((p)&0xFFFFFFFFFFFFULL)

/* actor list */
#define OFF_ANCHOR 0x17BB58
#define OFF_INBATTLE 0x2844
#define LIST_OFF 0x238
#define STRIDE 0x18
#define COUNT 10
#define OBJID_OFF 0x34
/* DD 数组 */
#define DD_BEGIN 0x1C4980
#define DD_CNTF  0x1C499C
#define DD_STR   0x34
#define DD_MAXENT 160
/* 路2 管理器 */
#define MGR_SINGLETON 0x50157C8
#define MGR_FIELD 0x70
#define MGR_MAP   0x10
#define MGR_GEN   0x18
#define TBL_GEN   0xc
#define TBL_BEGIN 0x10
#define TBL_END   0x18
#define ENT_STR   0x20
#define ENT_KEY   0x0
#define ENT_REC   0x8
#define ENT_GEN   0x10
#define REC_GEN   0xc
#define REC_POS90 0x90
#define REC_KEYMIR 0x9c
#define REC_KEY   0x1f8
#define REC_FLAG  0x1f9
#define REC_POS228 0x228
#define SENT_A    0x4d68480
#define SENT_B_PP 0x4c52048
#define MGR_MAXENT 256

static uint32_t kp_version=0;
static long vc(long c){ return ((long)kp_version<<32)|(0x2026L<<16)|(c&0xFFFF); }
static int kp_init(void){ long v=syscall(__NR_supercall,NULL,SUPERCALL_KERNELPATCH_VER); if(v<0)return -1;
    kp_version=(v<0xa05)?0:(uint32_t)v; return syscall(__NR_supercall,NULL,vc(SUPERCALL_KERNELPATCH_VER))<0?-1:0; }
static int kpm_ctl(const char*a,char*o,int n){ return (int)syscall(__NR_supercall,NULL,vc(SUPERCALL_KPM_CONTROL),KPM_NAME,a,o,(long)n); }
static int parse_r(const char*r,uint8_t*b,int max){ if(!r||r[0]=='E'||r[0]!='R'||r[1]!=':')return -1;
    const char*p=r+2;int n=0;while(*p>='0'&&*p<='9')n=n*10+(*p++ -'0');if(*p==' ')p++;if(n>max)n=max;
    for(int i=0;i<n;i++){char x=p[i*2],y=p[i*2+1];if(!x||!y)return i;uint8_t hi=(x>='a'?x-'a'+10:x-'0'),lo=(y>='a'?y-'a'+10:y-'0');b[i]=(hi<<4)|lo;}return n; }
static int rd_mem(int pid,uint64_t a,void*o,int len){ char c[64],r[8192];snprintf(c,sizeof(c),"r %d %lx %x",pid,(unsigned long)a,len);
    memset(r,0,sizeof(r));if(kpm_ctl(c,r,sizeof(r))<0)return -1;return parse_r(r,(uint8_t*)o,len); }

/* benign allow-list (private 非设备) */
#define MAXR 8192
static uint64_t bl[MAXR],bh[MAXR]; static int nb=0;
static void sortb(void){for(int i=1;i<nb;i++){uint64_t a=bl[i],b=bh[i];int j=i-1;while(j>=0&&bl[j]>a){bl[j+1]=bl[j];bh[j+1]=bh[j];j--;}bl[j+1]=a;bh[j+1]=b;}}
static int is_benign(uint64_t a){if(a<0x10000)return 0;int l=0,r=nb-1;while(l<=r){int m=(l+r)/2;if(a<bl[m])r=m-1;else if(a>=bh[m])l=m+1;else return 1;}return 0;}
static int is_benign_range(uint64_t a,uint64_t len){return is_benign(a)&&is_benign(a+len-1);}
static int dev(const char*l){return strstr(l,"/dev/")||strstr(l,"kgsl")||strstr(l,"mali")||strstr(l,"ion")||strstr(l,"dmabuf")||strstr(l,"memfd")||strstr(l,"/gpu")||strstr(l,"render")||strstr(l,"/dri/")||strstr(l,"adreno")||strstr(l,"powervr");}
static int find_pid(void){DIR*d=opendir("/proc");if(!d)return -1;struct dirent*e;int f=-1;
    while((e=readdir(d))){if(e->d_name[0]<'0'||e->d_name[0]>'9')continue;char p[64];snprintf(p,sizeof(p),"/proc/%s/cmdline",e->d_name);
    int fd=open(p,O_RDONLY);if(fd<0)continue;char b[256]={0};ssize_t r=read(fd,b,255);close(fd);if(r>0&&strcmp(b,SGAME_PKG)==0){f=atoi(e->d_name);break;}}closedir(d);return f;}
static uint64_t gc_bss=0;
static void parse_maps(int pid){char p[64];snprintf(p,sizeof(p),"/proc/%d/maps",pid);FILE*f=fopen(p,"r");if(!f)return;char line[1024];int seen=0;
    while(fgets(line,sizeof(line),f)){uint64_t st,en;char pm[8]={0};if(sscanf(line,"%lx-%lx %4s",&st,&en,pm)!=3)continue;if(pm[0]!='r')continue;
        if(pm[3]=='p'&&!dev(line)&&nb<MAXR){bl[nb]=st;bh[nb]=en;nb++;}
        if(!seen){if(strstr(line,LIBGC))seen=1;}else if(!gc_bss&&strstr(line,"[anon:.bss]"))gc_bss=st;}
    fclose(f);sortb();}

/* gated reads */
static int rg(int pid,uint64_t a,void*o,int len){if(!is_benign_range(a,len))return -1;return rd_mem(pid,a,o,len)<len?-1:0;}
static int rd8(int pid,uint64_t a,uint64_t*o){uint64_t v=0;if(rg(pid,a,&v,8)<0)return -1;*o=UNTAG(v);return 0;}
static int rd4(int pid,uint64_t a,uint32_t*o){return rg(pid,a,o,4);}
static int rd1(int pid,uint64_t a,uint8_t*o){return rg(pid,a,o,1);}
static int follow(int pid,uint64_t p,uint64_t*o){uint64_t v;if(rd8(pid,p,&v)<0)return -1;if(!is_benign(v))return -1;*o=v;return 0;}

/* +0x268 fog 链 → 终点 posStruct(d) + (x,z) */
static int chain268(int pid,uint64_t actor,uint64_t*pos_struct,int32_t*x,int32_t*z){uint64_t a,b,c,d;
    if(follow(pid,actor+0x268,&a)<0)return -1;if(follow(pid,a+0x10,&b)<0)return -2;
    if(follow(pid,b,&c)<0)return -3;if(follow(pid,c+0x60,&d)<0)return -4;
    int32_t p[4]={0};if(rg(pid,d,p,16)<0)return -5;*x=p[0];*z=p[2];if(pos_struct)*pos_struct=d;return 0;}

static FILE*g_out=NULL;
#define LOGF(...) do{if(g_out){fprintf(g_out,__VA_ARGS__);fflush(g_out);fsync(fileno(g_out));}printf(__VA_ARGS__);}while(0)

/* objID→key 跨帧映射 + 管理器位置变化追踪 */
typedef struct{uint32_t objid;uint8_t key;int has_key;
    int32_t last90x,last90z,last228x,last228z;int has_last;
    int seen,fog_seen,fog_moved90,fog_moved228;}track_t;
static track_t trk[32];static int ntrk=0;
static track_t* get_trk(uint32_t objid){for(int i=0;i<ntrk;i++)if(trk[i].objid==objid)return &trk[i];
    if(ntrk<32){memset(&trk[ntrk],0,sizeof(track_t));trk[ntrk].objid=objid;return &trk[ntrk++];}return NULL;}

int main(int argc,char**argv){
    setbuf(stdout,NULL);
    g_out=fopen("/data/local/tmp/mgr.txt","w");
    int frames=(argc>1)?atoi(argv[1]):40;if(frames<1)frames=40;if(frames>200)frames=200;
    LOGF("=== tv_mgrdiag (路2 管理器 0x50157C8 vs DD vs 268) frames=%d ===\n",frames);
    if(kp_init()<0){LOGF("kp_init fail\n");return 1;}
    char resp[512]={0};int ok=0;
    for(int i=0;KPM_CANDIDATES[i];i++){KPM_NAME=KPM_CANDIDATES[i];memset(resp,0,sizeof(resp));if(kpm_ctl("s",resp,sizeof(resp))==0){ok=1;break;}}
    if(!ok){LOGF("no KPM\n");return 2;}
    int pid=find_pid();if(pid<=0){LOGF("sgame 未运行\n");return 3;}
    parse_maps(pid);
    LOGF("[init] pid=%d gc_bss=%lx benign=%d KPM=%s\n",pid,(unsigned long)gc_bss,nb,KPM_NAME);
    if(!gc_bss){LOGF("gc_bss 未找到\n");return 4;}

    /* 哨兵: SA=*(gc_bss+0x4d68480), SB=*(*(gc_bss+0x4c52048)) */
    uint64_t SA=0,SBp=0,SB=0;
    rd8(pid,gc_bss+SENT_A,&SA);
    if(rd8(pid,gc_bss+SENT_B_PP,&SBp)==0&&SBp)rd8(pid,SBp,&SB);
    LOGF("[sentinel] SA=%lx SB=%lx (rec 命中这俩=无此实体记录)\n",(unsigned long)SA,(unsigned long)SB);

    uint8_t *dd=malloc(DD_MAXENT*DD_STR);
    uint8_t *ents=malloc(MGR_MAXENT*ENT_STR);

    for(int fr=0;fr<frames;fr++){
        uint32_t inb=0;rd4(pid,gc_bss+OFF_INBATTLE,&inb);

        /* ---- 路2 管理器解析 ---- */
        uint64_t sg=0,manager=0,table=0,ebegin=0,eend=0; uint32_t mgen=0,tgen=0; int ecnt=0;
        int mgr_ok=0;
        if(follow(pid,gc_bss+MGR_SINGLETON,&sg)==0 && follow(pid,sg+MGR_FIELD,&manager)==0){
            if(follow(pid,manager+MGR_MAP,&table)==0){
                rd4(pid,manager+MGR_GEN,&mgen); rd4(pid,table+TBL_GEN,&tgen);
                follow(pid,table+TBL_BEGIN,&ebegin); rd8(pid,table+TBL_END,&eend);
                if(ebegin&&eend>ebegin){ ecnt=(int)((eend-ebegin)/ENT_STR); }
                if(ecnt<0)ecnt=0; if(ecnt>MGR_MAXENT)ecnt=MGR_MAXENT;
                /* 若 begin/end 推 count 不靠谱, 退化为扫满 MGR_MAXENT */
                if(ecnt==0&&ebegin)ecnt=MGR_MAXENT;
                if(ebegin&&ecnt>0){
                    int got=0;
                    for(int off=0;off<ecnt*ENT_STR;off+=512){int want=(ecnt*ENT_STR-off<512)?(ecnt*ENT_STR-off):512;
                        if(rg(pid,ebegin+off,ents+off,want)<0){got=-1;break;}}
                    if(got==0)mgr_ok=1; else ecnt=0;
                }
            }
        }
        LOGF("\n--- F%d in_battle=%u | MGR sg=%lx manager=%lx table=%lx gen(m=%u t=%u %s) entries=%lx cnt=%d %s ---\n",
            fr,inb,(unsigned long)sg,(unsigned long)manager,(unsigned long)table,mgen,tgen,
            mgen==tgen?"GEN-OK":"GEN-MISMATCH!",(unsigned long)ebegin,ecnt,mgr_ok?"":"(read fail)");

        /* 管理器全 entry dump (仅首帧详尽; 之后只摘要) */
        if(mgr_ok&&fr==0){
            uint64_t hdr[4]={0}; rg(pid,table,hdr,32);
            LOGF("   [table hdr] +0=%lx +8=%lx +0x10=%lx +0x18=%lx\n",(unsigned long)hdr[0],(unsigned long)hdr[1],(unsigned long)hdr[2],(unsigned long)hdr[3]);
            int shown=0;
            for(int i=0;i<ecnt&&shown<40;i++){uint8_t*e=ents+i*ENT_STR;
                uint32_t k=*(uint32_t*)(e+ENT_KEY); uint64_t rec=UNTAG(*(uint64_t*)(e+ENT_REC)); uint32_t eg=*(uint32_t*)(e+ENT_GEN);
                if(!rec||!is_benign(rec))continue;
                uint8_t flag=0xff,rkey=0xff; uint32_t rg2=0; int32_t p90[3]={0},p228[3]={0};
                rd1(pid,rec+REC_FLAG,&flag); rd1(pid,rec+REC_KEY,&rkey); rd4(pid,rec+REC_GEN,&rg2);
                rg(pid,rec+REC_POS90,p90,12); rg(pid,rec+REC_POS228,p228,12);
                const char*st=(rec==SA||rec==SB)?" SENTINEL":"";
                LOGF("   ENT[%d] key=%u rec=%lx eg=%u rg=%u%s flag=%u rkey=%u | +0x90=(%.1f,%.1f) +0x228=(%.1f,%.1f)\n",
                    i,k,(unsigned long)rec,eg,rg2,st,flag,rkey,p90[0]/1000.0f,p90[2]/1000.0f,p228[0]/1000.0f,p228[2]/1000.0f);
                shown++;
            }
        }

        /* ---- DD 数组 ---- */
        uint64_t db=0; uint32_t dcnt=0; int dn=0;
        if(follow(pid,gc_bss+DD_BEGIN,&db)==0){rd4(pid,gc_bss+DD_CNTF,&dcnt);dn=(int)dcnt;if(dn<0)dn=0;if(dn>DD_MAXENT)dn=DD_MAXENT;
            int got=0;for(int off=0;off<dn*DD_STR;off+=512){int want=(dn*DD_STR-off<512)?(dn*DD_STR-off):512;
                if(rg(pid,db+off,dd+off,want)<0){got=-1;break;}}if(got<0)dn=0;}

        /* ---- 10 actor: 三方对比 + 追踪 ---- */
        uint64_t anchor=0,list=0;
        if(follow(pid,gc_bss+OFF_ANCHOR,&anchor)<0||follow(pid,anchor+LIST_OFF,&list)<0){LOGF(" actor anchor fail\n");usleep(300000);continue;}
        uint8_t ent[COUNT*STRIDE]; if(rg(pid,list,ent,sizeof(ent))<0){LOGF(" list fail\n");usleep(300000);continue;}
        for(int i=0;i<COUNT;i++){
            uint64_t actor;memcpy(&actor,ent+i*STRIDE,8);actor=UNTAG(actor);
            if(!actor||!is_benign(actor))continue;
            uint32_t type=0,camp=0,objid=0;
            rd4(pid,actor+0x50,&type);rd4(pid,actor+0x5c,&camp);rd4(pid,actor+OBJID_OFF,&objid);
            if(!objid)continue;
            track_t*t=get_trk(objid); if(t)t->seen++;

            /* 268 链 + 学 key */
            uint64_t ps=0; int32_t x2=0,z2=0; int r268=chain268(pid,actor,&ps,&x2,&z2);
            if(r268==0&&ps){uint8_t k=0; if(rd1(pid,ps+0xc,&k)==0&&t){t->key=k;t->has_key=1;}}

            /* DD 匹配 */
            int ddi=-1; float ddx=0,ddz=0;
            for(int k=0;k<dn;k++){uint8_t*e=dd+k*DD_STR;if(*(uint32_t*)(e+0)==objid){ddi=k;ddx=*(float*)(e+0x10);ddz=*(float*)(e+0x18);break;}}

            /* 管理器: 按已学 key 在 entries 里找 */
            int mi=-1; int32_t m90[3]={0},m228[3]={0}; uint8_t mflag=0xff; uint32_t mgen2=0,meg=0; uint64_t mrec=0; int msent=0;
            if(mgr_ok&&t&&t->has_key){
                for(int k=0;k<ecnt;k++){uint8_t*e=ents+k*ENT_STR; if((*(uint32_t*)(e+ENT_KEY)&0xff)==t->key){mi=k;
                    mrec=UNTAG(*(uint64_t*)(e+ENT_REC)); meg=*(uint32_t*)(e+ENT_GEN); break;}}
                if(mi>=0&&mrec&&is_benign(mrec)){
                    if(mrec==SA||mrec==SB)msent=1;
                    rd1(pid,mrec+REC_FLAG,&mflag); rd4(pid,mrec+REC_GEN,&mgen2);
                    rg(pid,mrec+REC_POS90,m90,12); rg(pid,mrec+REC_POS228,m228,12);
                }
            }

            /* 追踪深雾移动: 268失败(=进雾) 且有 key 且 mgr 命中 → 看 +0x228/+0x90 是否变化 */
            const char*verdict="";
            if(t){
                int infog=(r268!=0);
                if(infog&&t->has_key&&mi>=0&&!msent){
                    t->fog_seen++;
                    if(t->has_last){
                        if(m90[0]!=t->last90x||m90[2]!=t->last90z)t->fog_moved90++;
                        if(m228[0]!=t->last228x||m228[2]!=t->last228z)t->fog_moved228++;
                    }
                }
                t->last90x=m90[0];t->last90z=m90[2];t->last228x=m228[0];t->last228z=m228[2];t->has_last=1;
                if(infog&&mi>=0&&(t->fog_moved228>0||t->fog_moved90>0))verdict="  ★MGR-FOG-MOVING!";
                else if(infog&&mi>=0)verdict="  (mgr命中但冻结?)";
            }

            LOGF(" [%d] c=%u T=%u oid=%u key=%s%u | 268:%s(%.1f,%.1f) | DD@%d=(%.1f,%.1f) | MGR@%d rec=%lx f=%u g(e=%u r=%u)%s 90=(%.1f,%.1f) 228=(%.1f,%.1f)%s\n",
                i,camp,type,objid, t&&t->has_key?"":"?", t?t->key:0,
                r268==0?"OK":"X",x2/1000.0f,z2/1000.0f,
                ddi,ddx,ddz,
                mi,(unsigned long)mrec,mflag,meg,mgen2,msent?" SENT":"",
                m90[0]/1000.0f,m90[2]/1000.0f,m228[0]/1000.0f,m228[2]/1000.0f,verdict);
        }
        usleep(300000);
    }

    /* ---- 汇总判读 ---- */
    LOGF("\n=== 汇总 (路2 判读) ===\n");
    for(int i=0;i<ntrk;i++){track_t*t=&trk[i];
        LOGF(" oid=%u key=%s%u seen=%d 深雾帧=%d 深雾中:+0x90动=%d +0x228动=%d  => %s\n",
            t->objid,t->has_key?"":"?",t->key,t->seen,t->fog_seen,t->fog_moved90,t->fog_moved228,
            (t->fog_seen>=3 && (t->fog_moved90>0||t->fog_moved228>0))?"★路2活: 深雾仍随移动变化":
            (t->fog_seen>=3?"路2死: 深雾中管理器冻结(陈旧缓存, 符合判决#3)":"深雾样本不足"));
    }
    LOGF("\n[判读指南]\n"
         " ① 任一敌人 ★MGR-FOG-MOVING / '深雾仍随移动变化' → 路2 意外可行, 重开评估.\n"
         " ② 全部 '深雾中管理器冻结' → 坐实判决: +0x228/+0x90 是陈旧缓存, 路2 死, 转路1 HW BP.\n"
         " ③ DD@-1(深雾敌人不在DD) → 坐实 DD 只覆盖宽限期不覆盖深雾(解释'雾中不显示').\n"
         "    DD@>=0 且 (x,z) 随移动变 → DD 仍覆盖该敌人(还在宽限期内).\n"
         " ④ MGR rec=SENTINEL/SENT → 该敌人管理器无注册记录(判决#2).\n");
    free(dd);free(ents);
    if(g_out)fclose(g_out);
    return 0;
}
