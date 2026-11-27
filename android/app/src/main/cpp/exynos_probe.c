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
