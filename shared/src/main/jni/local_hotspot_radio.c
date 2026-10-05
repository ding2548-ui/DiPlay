// Read only the caller-selected AP interface. No shell, netlink, privilege elevation or setters.
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/wireless.h>

JNIEXPORT jintArray JNICALL Java_com_shilapi_xcertplay_network_LegacyHotspotNative_query(JNIEnv *env, jobject self, jstring interface_name) {
    (void) self;
    jint values[3] = { EINVAL, 0, 0 };
    if (interface_name == NULL) goto result;
    const char *name = (*env)->GetStringUTFChars(env, interface_name, NULL);
    if (name == NULL) return NULL;
    const size_t length = strlen(name);
    if (length > 0 && length < IFNAMSIZ) {
        int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) values[0] = errno;
        else {
            struct iwreq request;
            memset(&request, 0, sizeof(request));
            memcpy(request.ifr_name, name, length);
            if (ioctl(fd, SIOCGIWFREQ, &request) == 0) {
                values[0] = 0;
                values[1] = request.u.freq.m;
                values[2] = request.u.freq.e;
            } else values[0] = errno;
            close(fd);
        }
    }
    (*env)->ReleaseStringUTFChars(env, interface_name, name);
result:;
    jintArray result_array = (*env)->NewIntArray(env, 3);
    if (result_array != NULL) (*env)->SetIntArrayRegion(env, result_array, 0, 3, values);
    return result_array;
}
