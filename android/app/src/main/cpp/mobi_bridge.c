#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libmobi/mobi.h"

/* Return reconstructed EPUB resources in memory; no book-controlled filesystem paths. */
JNIEXPORT jobjectArray JNICALL Java_cn_jiangcheng_lemon_reader_MobiImporter_unpack(JNIEnv *env, jobject self, jbyteArray input) {
    (void)self;
    const char *error = "MOBI 文件损坏或格式不受支持";
    MOBIData *m = NULL; MOBIRawml *raw = NULL; FILE *file = NULL; jbyte *bytes = NULL; jobjectArray result = NULL;
    jsize length = (*env)->GetArrayLength(env, input);
    if (length <= 0 || length > 80000000) goto cleanup;
    bytes = (*env)->GetByteArrayElements(env, input, NULL); if (!bytes) goto cleanup;
    file = fmemopen(bytes, (size_t)length, "rb"); if (!file) goto cleanup;
    m = mobi_init(); if (!m || mobi_load_file(m, file) != MOBI_SUCCESS) goto cleanup;
    if (mobi_is_encrypted(m)) { error = "此 MOBI 有 DRM 保护，请使用无 DRM 的小说文件"; goto cleanup; }
    if (mobi_get_text_maxsize(m) > 150000000) { error = "MOBI 解压正文超过 150 MB"; goto cleanup; }
    raw = mobi_init_rawml(m); if (!raw || mobi_parse_rawml(raw, m) != MOBI_SUCCESS) goto cleanup;
    MOBIPart *groups[] = {raw->markup, raw->flow ? raw->flow->next : NULL, raw->resources};
    size_t count = 0, total = 0;
    for (int g=0; g<3; g++) for (MOBIPart *p=groups[g]; p; p=p->next) { count++; total+=p->size; if (count>20000 || total>150000000) goto cleanup; }
    jclass arrayType = (*env)->FindClass(env,"[B");
    result = (*env)->NewObjectArray(env,(jsize)(count*2),arrayType,NULL); if (!result) goto cleanup;
    int index=0;
    for (int g=0;g<3;g++) for (MOBIPart *p=groups[g];p;p=p->next) {
        MOBIFileMeta meta=mobi_get_filemeta_by_type(p->type); char name[96];
        if (p->type==T_OPF) snprintf(name,sizeof(name),"content.opf");
        else snprintf(name,sizeof(name),"%s%05zu.%s",g==0?"part":g==1?"flow":"resource",p->uid,meta.extension);
        jbyteArray key=(*env)->NewByteArray(env,(jsize)strlen(name));
        jbyteArray value=(*env)->NewByteArray(env,(jsize)p->size);
        if (!key || !value) {result=NULL;goto cleanup;}
        (*env)->SetByteArrayRegion(env,key,0,(jsize)strlen(name),(const jbyte*)name);
        (*env)->SetByteArrayRegion(env,value,0,(jsize)p->size,(const jbyte*)p->data);
        (*env)->SetObjectArrayElement(env,result,index++,key);(*env)->SetObjectArrayElement(env,result,index++,value);
        (*env)->DeleteLocalRef(env,key);(*env)->DeleteLocalRef(env,value);
        if ((*env)->ExceptionCheck(env)) {result=NULL;goto cleanup;}
    }
cleanup:
    if (raw) mobi_free_rawml(raw);
    if (m) mobi_free(m);
    if (file) fclose(file);
    if (bytes) (*env)->ReleaseByteArrayElements(env,input,bytes,JNI_ABORT);
    if (!result && !(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),error);
    return result;
}
