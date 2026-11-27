/* Root-only Exynos capability probe. No mixer writes, PCM HW_PARAMS, START, or sample IO.
 * PCM_INFO is queried on the control device even for occupied modem streams. For idle AP/SCO
 * candidates only, try a nonblocking open and HW_REFINE, then immediately close.
 * These checks establish native access/capabilities, not voice routing or a working bridge. */
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <poll.h>
#include <time.h>
#include <sound/asound.h>

struct textbuf { char data[4096]; size_t len; };
static void append(struct textbuf *b, const char *fmt, ...) {
    if (b->len >= sizeof(b->data)-1) return;
    va_list a;
    va_start(a, fmt);
    int n = vsnprintf(b->data+b->len, sizeof(b->data)-b->len, fmt, a);
    va_end(a);
    if (n > 0) {
        size_t left = sizeof(b->data)-b->len-1;
        b->len += (size_t)n < left ? (size_t)n : left;
    }
}

static int is_closed(int device, int capture) {
    char path[128], line[64];
    snprintf(path,sizeof(path),"/proc/asound/card0/pcm%d%c/sub0/status",device,capture?'c':'p');
    FILE *f=fopen(path,"r");
    if (!f) return 0;
    int ok=fgets(line,sizeof(line),f)!=NULL &&
        (strcmp(line,"closed\n")==0 || strcmp(line,"closed")==0);
    fclose(f);
    return ok;
}

static void any_params(struct snd_pcm_hw_params *p) {
    memset(p,0,sizeof(*p));
    for (unsigned int i=0;i<sizeof(p->masks)/sizeof(p->masks[0]);i++)
        memset(&p->masks[i],0xff,sizeof(p->masks[i]));
    for (unsigned int i=0;i<sizeof(p->intervals)/sizeof(p->intervals[0]);i++)
        p->intervals[i].max=UINT32_MAX;
    p->rmask=UINT32_MAX;
}
static void mask_only(struct snd_mask *m,unsigned int bit) {
    memset(m,0,sizeof(*m));
    if (bit<sizeof(m->bits)*8) m->bits[bit/32]=1u<<(bit%32);
}
static void exact_interval(struct snd_pcm_hw_params *p,unsigned int param,unsigned int value) {
    struct snd_interval *i=&p->intervals[param-SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    i->min=i->max=value;
    i->integer=1;
}
static int refine(int fd,unsigned int rate,unsigned int channels) {
    struct snd_pcm_hw_params p;
    any_params(&p);
    mask_only(&p.masks[SNDRV_PCM_HW_PARAM_ACCESS-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    mask_only(&p.masks[SNDRV_PCM_HW_PARAM_FORMAT-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_FORMAT_S16_LE);
    mask_only(&p.masks[SNDRV_PCM_HW_PARAM_SUBFORMAT-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_SUBFORMAT_STD);
    exact_interval(&p,SNDRV_PCM_HW_PARAM_RATE,rate);
    exact_interval(&p,SNDRV_PCM_HW_PARAM_CHANNELS,channels);
    return ioctl(fd,SNDRV_PCM_IOCTL_HW_REFINE,&p)==0 ? 0 : errno;
}

static void pcm(struct textbuf *b,int ctl,int device,int capture) {
    struct snd_pcm_info info;
    memset(&info,0,sizeof(info));
    info.device=(unsigned int)device;
    info.stream=capture?SNDRV_PCM_STREAM_CAPTURE:SNDRV_PCM_STREAM_PLAYBACK;
    int rc=ctl<0 ? -1 : ioctl(ctl,SNDRV_CTL_IOCTL_PCM_INFO,&info);
    append(b,"pcm%d%c: ",device,capture?'c':'p');
    if(rc==0) append(b,"substreams %u/%u free; ",info.subdevices_avail,info.subdevices_count);
    else append(b,"control query unavailable; ");
    if(device==2) { append(b,"modem reserved, not opened\n"); return; }
    if(rc!=0 || !info.subdevices_avail || !is_closed(device,capture)) {
        append(b,"busy or unknown, not opened\n"); return;
    }
    char path[64];
    snprintf(path,sizeof(path),"/dev/snd/pcmC0D%d%c",device,capture?'c':'p');
    int fd=open(path,O_RDWR|O_NONBLOCK|O_CLOEXEC);
    if(fd<0) { append(b,"open errno=%d\n",errno); return; }
    int a=refine(fd,8000,1), c=refine(fd,8000,2);
    int d=refine(fd,16000,1), e=refine(fd,16000,2);
    close(fd);
    append(b,"open OK, S16_LE refine 8k mono=%d stereo=%d 16k mono=%d stereo=%d (0=accepted; no samples)\n",a,c,d,e);
}

static void mixer(struct textbuf *b,int ctl,const char *name) {
    if(ctl<0) return;
    struct snd_ctl_elem_info i;
    memset(&i,0,sizeof(i));
    i.id.iface=SNDRV_CTL_ELEM_IFACE_MIXER;
    snprintf((char*)i.id.name,sizeof(i.id.name),"%s",name);
    if(ioctl(ctl,SNDRV_CTL_IOCTL_ELEM_INFO,&i)<0) {
        append(b,"%s: info errno=%d\n",name,errno); return;
    }
    struct snd_ctl_elem_value v;
    memset(&v,0,sizeof(v));
    v.id=i.id;
    if(ioctl(ctl,SNDRV_CTL_IOCTL_ELEM_READ,&v)<0) {
        append(b,"%s: read errno=%d\n",name,errno); return;
    }
    if(i.type==SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
        i.value.enumerated.item=v.value.enumerated.item[0];
        if(ioctl(ctl,SNDRV_CTL_IOCTL_ELEM_INFO,&i)==0)
            append(b,"%s: %.63s\n",name,i.value.enumerated.name);
        else append(b,"%s: enum %u\n",name,v.value.enumerated.item[0]);
    } else if(i.type==SNDRV_CTL_ELEM_TYPE_BOOLEAN || i.type==SNDRV_CTL_ELEM_TYPE_INTEGER) {
        append(b,"%s: %ld\n",name,v.value.integer.value[0]);
    } else append(b,"%s: type=%d\n",name,i.type);
}

JNIEXPORT jstring JNICALL Java_app_tether_helper_ExynosNative_nativeProbe(JNIEnv *env,jobject self) {
    (void)self;
    struct textbuf b={{0},0};
    if(getuid()!=0 || geteuid()!=0) return (*env)->NewStringUTF(env,"Root required");
    /* Private system libraries may be hidden by linker namespaces; report, never force a namespace. */
    const char *libs[]={"/apex/com.android.vndk.v30/lib/libtinyalsa.so","/system/lib/libtinyalsa.so"};
    void *lib=NULL;
    for(unsigned int i=0;i<sizeof(libs)/sizeof(libs[0]) && !lib;i++) lib=dlopen(libs[i],RTLD_NOW|RTLD_LOCAL);
    append(&b,"TinyALSA dlopen=%s; PCM/mixer probes use Linux ALSA ioctls\n",lib?"ok":"unavailable");
    if(lib) {
        append(&b,"TinyALSA exports pcm_open=%s mixer_open=%s\n",dlsym(lib,"pcm_open")?"yes":"no",dlsym(lib,"mixer_open")?"yes":"no");
        dlclose(lib);
    }
    int ctl=open("/dev/snd/controlC0",O_RDONLY|O_NONBLOCK|O_CLOEXEC);
    if(ctl<0) append(&b,"controlC0 errno=%d\n",errno);
    pcm(&b,ctl,2,0); pcm(&b,ctl,2,1);
    pcm(&b,ctl,0,1); pcm(&b,ctl,7,0);
    pcm(&b,ctl,3,0); pcm(&b,ctl,3,1);
    pcm(&b,ctl,6,0); pcm(&b,ctl,6,1);
    const char *names[]={"AudioMixer Mixer En","AudioMixer SRC2 En","AudioMixer SRC3 En",
        "AudioMixer CH1 Mixer En","AudioMixer CH2 Mixer En","AudioMixer CH3 Mixer En",
        "AudioMixer RMIX1 En","AudioMixer RMIX2 En","AudioMixer CH3 Rec En",
        "AudioMixer CH1 DOUT Select","AudioMixer CH2 DOUT Select","AudioMixer CH3 DOUT Select"};
    for(unsigned int i=0;i<sizeof(names)/sizeof(names[0]);i++) mixer(&b,ctl,names[i]);
    if(ctl>=0) close(ctl);
    return (*env)->NewStringUTF(env,b.data);
}


/* Experimental application-processor path, using the device's call-forwarding mixer
 * profile as a hypothesis. Never open modem PCM2 or Bluetooth/AMP links. */
#define AP_RATE 48000u
#define AP_PERIOD 960u
struct change { const char *name; const char *want; const char *expect; struct snd_ctl_elem_value old, target; int changed; };
static struct change changes[] = {
    {.name="AudioMixer CH2 Mixer En",.want="Off",.expect="On"}, /* do not feed far-end audio back into CP */
    {.name="AudioMixer RMIX2 En",.want="Off",.expect="On"},
    {.name="AudioMixer CH2 DOUT Select",.want="DMIX_OUT",.expect="AIF4IN"}
};
static int bridge_ctl=-1, bridge_in=-1, bridge_out=-1, bridge_rate=0;
static char bridge_error[256];
static void err(const char *where,int code) {
    snprintf(bridge_error,sizeof(bridge_error),"%s (errno=%d)",where,code);
}
static int ctl_enum(int fd,const char *name,const char *want,const char *expect,
                    struct snd_ctl_elem_value *old,struct snd_ctl_elem_value *target) {
    struct snd_ctl_elem_info i;
    memset(&i,0,sizeof(i)); i.id.iface=SNDRV_CTL_ELEM_IFACE_MIXER;
    snprintf((char*)i.id.name,sizeof(i.id.name),"%s",name);
    if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_INFO,&i)<0 || i.type!=SNDRV_CTL_ELEM_TYPE_ENUMERATED || i.count!=1) return -1;
    memset(old,0,sizeof(*old)); old->id=i.id;
    if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_READ,old)<0) return -1;
    unsigned int count=i.value.enumerated.items;
    int wanted=-1, expected=-1;
    for(unsigned int k=0;k<count;k++) {
        i.value.enumerated.item=k;
        if(ioctl(fd,SNDRV_CTL_IOCTL_ELEM_INFO,&i)<0) return -1;
        if(strcmp(i.value.enumerated.name,want)==0) wanted=(int)k;
        if(strcmp(i.value.enumerated.name,expect)==0) expected=(int)k;
    }
    if(wanted<0 || expected<0 || old->value.enumerated.item[0]!=(unsigned int)expected) return -1;
    *target=*old; target->value.enumerated.item[0]=(unsigned int)wanted;
    return 0;
}
static int modem_reserved(int capture) {
    struct snd_pcm_info i;
    memset(&i,0,sizeof(i)); i.device=2;
    i.stream=capture?SNDRV_PCM_STREAM_CAPTURE:SNDRV_PCM_STREAM_PLAYBACK;
    return ioctl(bridge_ctl,SNDRV_CTL_IOCTL_PCM_INFO,&i)==0 && i.subdevices_count==1 && i.subdevices_avail==0;
}
static int check_enum(const char *name,const char *value) {
    struct snd_ctl_elem_value a,b;
    return ctl_enum(bridge_ctl,name,value,value,&a,&b);
}
static void restore_route(void) {
    if(bridge_ctl<0) return;
    for(int k=(int)(sizeof(changes)/sizeof(changes[0]))-1;k>=0;k--) {
        struct change *c=&changes[k];
        if(!c->changed) continue;
        struct snd_ctl_elem_value now=c->target;
        if(ioctl(bridge_ctl,SNDRV_CTL_IOCTL_ELEM_READ,&now)==0 &&
           now.value.enumerated.item[0]==c->target.value.enumerated.item[0]) {
            if(ioctl(bridge_ctl,SNDRV_CTL_IOCTL_ELEM_WRITE,&c->old)<0) err("mixer restore",errno);
        }
        c->changed=0;
    }
}
static void bridge_close(void) {
    restore_route();
    if(bridge_in>=0) { ioctl(bridge_in,SNDRV_PCM_IOCTL_DROP); close(bridge_in); bridge_in=-1; }
    if(bridge_out>=0) { ioctl(bridge_out,SNDRV_PCM_IOCTL_DROP); close(bridge_out); bridge_out=-1; }
    if(bridge_ctl>=0) { close(bridge_ctl); bridge_ctl=-1; }
    bridge_rate=0;
}
static int ap_open(int dev,int capture) {
    if(!is_closed(dev,capture)) { err("AP PCM busy/unknown",EBUSY); return -1; }
    char path[64];
    snprintf(path,sizeof(path),"/dev/snd/pcmC0D%d%c",dev,capture?'c':'p');
    int fd=open(path,O_RDWR|O_NONBLOCK|O_CLOEXEC);
    if(fd<0) { err("AP PCM open",errno); return -1; }
    unsigned int chosen=0, buf=0;
    const unsigned int periods[]={960,1024,480,512,1920,256};
    for(unsigned int k=0;k<sizeof(periods)/sizeof(periods[0]);k++) {
        struct snd_pcm_hw_params p;
        any_params(&p);
        mask_only(&p.masks[SNDRV_PCM_HW_PARAM_ACCESS-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_ACCESS_RW_INTERLEAVED);
        mask_only(&p.masks[SNDRV_PCM_HW_PARAM_FORMAT-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_FORMAT_S16_LE);
        mask_only(&p.masks[SNDRV_PCM_HW_PARAM_SUBFORMAT-SNDRV_PCM_HW_PARAM_FIRST_MASK],SNDRV_PCM_SUBFORMAT_STD);
        exact_interval(&p,SNDRV_PCM_HW_PARAM_RATE,AP_RATE);
        exact_interval(&p,SNDRV_PCM_HW_PARAM_CHANNELS,2);
        exact_interval(&p,SNDRV_PCM_HW_PARAM_PERIOD_SIZE,periods[k]);
        exact_interval(&p,SNDRV_PCM_HW_PARAM_BUFFER_SIZE,periods[k]*4);
        if(ioctl(fd,SNDRV_PCM_IOCTL_HW_REFINE,&p)==0 && ioctl(fd,SNDRV_PCM_IOCTL_HW_PARAMS,&p)==0) {
            chosen=periods[k]; buf=chosen*4; break;
        }
    }
    if(!chosen) { err("AP PCM 48k stereo HW_PARAMS",errno); close(fd); return -1; }
    struct snd_pcm_sw_params sw;
    memset(&sw,0,sizeof(sw));
    sw.period_step=1; sw.avail_min=chosen;
    sw.start_threshold=1;
    sw.stop_threshold=buf;
    sw.boundary=buf;
    while(sw.boundary <= (unsigned long)0x3fffffff/2) sw.boundary*=2;
    if(ioctl(fd,SNDRV_PCM_IOCTL_SW_PARAMS,&sw)<0 || ioctl(fd,SNDRV_PCM_IOCTL_PREPARE)<0) {
        err("AP PCM prepare",errno); close(fd); return -1;
    }
    return fd;
}
static int64_t mono_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC,&t);
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int transfer(int fd,int capture,int16_t *samples,unsigned int frames) {
    unsigned int done=0;
    int64_t deadline=mono_ms()+150;
    while(done<frames && mono_ms()<deadline) {
        struct snd_xferi x;
        memset(&x,0,sizeof(x)); x.buf=samples+done*2; x.frames=frames-done;
        int rc=ioctl(fd,capture?SNDRV_PCM_IOCTL_READI_FRAMES:SNDRV_PCM_IOCTL_WRITEI_FRAMES,&x);
        if(rc==0 && x.result>0 && (unsigned long)x.result<=x.frames) { done+=(unsigned int)x.result; continue; }
        int e=rc<0?errno:(x.result<0?(int)-x.result:EAGAIN);
        if(e==EPIPE) {
            if(ioctl(fd,SNDRV_PCM_IOCTL_PREPARE)<0 || (capture && ioctl(fd,SNDRV_PCM_IOCTL_START)<0)) return -EPIPE;
            if(done) return -EPIPE;
            continue;
        }
        if(e!=EAGAIN && e!=EINTR) return -e;
        struct pollfd f={.fd=fd,.events=capture?POLLIN:POLLOUT,.revents=0};
        poll(&f,1,10);
    }
    return done==frames?(int)done:-ETIMEDOUT;
}
JNIEXPORT jstring JNICALL Java_app_tether_helper_ExynosNative_nativeStart(JNIEnv *env,jobject self,jint rate) {
    (void)self;
    if(getuid()!=0 || geteuid()!=0 || (rate!=8000 && rate!=16000 && rate!=48000))
        return (*env)->NewStringUTF(env,"unsupported UID or codec rate");
    bridge_close(); bridge_error[0]=0;
    bridge_ctl=open("/dev/snd/controlC0",O_RDWR|O_NONBLOCK|O_CLOEXEC);
    if(bridge_ctl<0) { err("mixer open",errno); goto fail; }
    /* Fail closed on any mismatch with the observed j7velte call topology. */
    if(!modem_reserved(0) || !modem_reserved(1) ||
       check_enum("AudioMixer Mixer En","On") || check_enum("AudioMixer SRC2 En","On") ||
       check_enum("AudioMixer SRC3 En","Off") || check_enum("AudioMixer CH1 Mixer En","On") ||
       check_enum("AudioMixer CH3 Mixer En","Off") || check_enum("AudioMixer RMIX1 En","On") ||
       check_enum("AudioMixer CH1 DOUT Select","RMIX_OUT")) { err("unexpected in-call mixer topology",EINVAL); goto fail; }
    for(unsigned int k=0;k<sizeof(changes)/sizeof(changes[0]);k++) {
        struct change *c=&changes[k];
        if(ctl_enum(bridge_ctl,c->name,c->want,c->expect,&c->old,&c->target)<0) {
            err("unexpected mixer baseline",EINVAL); goto fail;
        }
    }
    bridge_in=ap_open(0,1); if(bridge_in<0) goto fail;
    bridge_out=ap_open(7,0); if(bridge_out<0) bridge_out=ap_open(0,0);
    if(bridge_out<0) goto fail;
    /* PCM startup may alter the mixer. Check baseline again before any write. */
    for(unsigned int k=0;k<sizeof(changes)/sizeof(changes[0]);k++) {
        struct change *c=&changes[k];
        if(ctl_enum(bridge_ctl,c->name,c->want,c->expect,&c->old,&c->target)<0) {
            err("mixer changed during AP setup",EINVAL); goto fail;
        }
        c->changed=1;
        if(ioctl(bridge_ctl,SNDRV_CTL_IOCTL_ELEM_WRITE,&c->target)<0 ||
           check_enum(c->name,c->want)<0) { err("mixer route rejected",EIO); goto fail; }
    }
    if(ioctl(bridge_in,SNDRV_PCM_IOCTL_START)<0) { err("AP capture start",errno); goto fail; }
    bridge_rate=rate;
    return NULL;
fail:
    bridge_close();
    return (*env)->NewStringUTF(env,bridge_error[0]?bridge_error:"native start failed");
}
JNIEXPORT jint JNICALL Java_app_tether_helper_ExynosNative_nativeRead(JNIEnv *env,jobject self,jshortArray out) {
    (void)self;
    if(bridge_in<0 || !bridge_rate) return -ENODEV;
    int n=(*env)->GetArrayLength(env,out), factor=(int)AP_RATE/bridge_rate;
    if(n<=0 || n*factor>(int)AP_PERIOD) return -EINVAL;
    int16_t pcm[AP_PERIOD*2], mono[AP_PERIOD];
    int rc=transfer(bridge_in,1,pcm,(unsigned int)(n*factor));
    if(rc<0) return rc;
    for(int i=0;i<n;i++) {
        int32_t sum=0;
        for(int j=0;j<factor;j++) {
            int k=(i*factor+j)*2; sum+=pcm[k]+pcm[k+1];
        }
        mono[i]=(int16_t)(sum/(2*factor));
    }
    (*env)->SetShortArrayRegion(env,out,0,n,mono);
    return n;
}
JNIEXPORT jint JNICALL Java_app_tether_helper_ExynosNative_nativeWrite(JNIEnv *env,jobject self,jshortArray in,jint n) {
    (void)self;
    if(bridge_out<0 || !bridge_rate) return -ENODEV;
    int factor=(int)AP_RATE/bridge_rate;
    if(n<=0 || n>(*env)->GetArrayLength(env,in) || n*factor>(int)AP_PERIOD) return -EINVAL;
    int16_t pcm[AP_PERIOD*2], mono[AP_PERIOD];
    (*env)->GetShortArrayRegion(env,in,0,n,mono);
    for(int i=0;i<n;i++) for(int j=0;j<factor;j++) {
        int next=i+1<n?mono[i+1]:mono[i];
        /* Cap experimental injection at half-scale, no abrupt gain increase. */
        int v=((int)mono[i]*(factor-j)+next*j)/(factor*2);
        int k=(i*factor+j)*2; pcm[k]=pcm[k+1]=(int16_t)v;
    }
    int rc=transfer(bridge_out,0,pcm,(unsigned int)(n*factor));
    return rc<0?rc:n;
}
JNIEXPORT jboolean JNICALL Java_app_tether_helper_ExynosNative_nativeRouteIntact(JNIEnv *env,jobject self) {
    (void)env; (void)self;
    if(bridge_ctl<0) return JNI_FALSE;
    for(unsigned int k=0;k<sizeof(changes)/sizeof(changes[0]);k++)
        if(check_enum(changes[k].name,changes[k].want)<0) return JNI_FALSE;
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_app_tether_helper_ExynosNative_nativeClose(JNIEnv *env,jobject self) {
    (void)env; (void)self; bridge_close();
}
