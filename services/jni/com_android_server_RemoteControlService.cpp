/*
 * Copyright (C) 2011-2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* This file exposes the SurfaceFlinger's ScreenshotClient API to
 * Java.
 *
 * The alternative would be to call the binder interfaces directly,
 * which is more complex and more prone to failure if things change.
 *
 * Calling the Binder interfaces from Java isn't possible because the
 * IMemory interface used by the screenshot API is exposed to native
 * code only. */

#define LOG_TAG "RemoteControlService"

#include "jni.h"

#include "android_runtime/AndroidRuntime.h"
#include <binder/IMemory.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

using namespace android;

namespace android
{

static struct {
    jclass clazz;

    jfieldID nativeID;
} gRemoteControlClientClassInfo;

static struct {
    jclass clazz;

    jfieldID mAddress;
} gMemoryFileClassInfo;

static int registerScreenshotClient(JNIEnv *env, jobject self, jint pixfmt)
{
    /* First see if we can use the ScreenshotClient mechanism */
    ScreenshotClient *client = new ScreenshotClient();    
    int rv = client ? client->update() : -1;
    if(rv == 0) {

        /* Success! */

        LOGI("Screen access method: SurfaceFlinger");
        env->SetIntField(self, gRemoteControlClientClassInfo.nativeID, (jint) client);
        return 0;
    }

    LOGE("SurfaceFlinger: failed with code %d (%s)", rv, strerror(-rv));

    delete client;

    return -1;
}

static ScreenshotClient *getClient(JNIEnv *env, jobject self)
{
    return (ScreenshotClient *)env->GetIntField(self, gRemoteControlClientClassInfo.nativeID);
}

static void unregisterScreenshotClient(JNIEnv *env, jobject self)
{
    ScreenshotClient *client = getClient(env, self);
    if(client) {
        delete client;
    }
    env->SetIntField(self, gRemoteControlClientClassInfo.nativeID, 0);
}

static int grabScreen(JNIEnv *env, jobject self, jobject sharedBuffer, jboolean incremental, jint requestedW, jint requestedH)
{
    ScreenshotClient *client = getClient(env, self);
    int rv = -1;

    if(client) {
         if (requestedW == 0 || requestedH == 0)
            rv = client->update();
        else
            rv = client->update(requestedW, requestedH);

        if(rv == 0) {
            void *buffer = (void *)env->GetIntField(sharedBuffer, gMemoryFileClassInfo.mAddress);
            memcpy(buffer, client->getPixels(), client->getSize());
        }
    }
    return rv;
}

static int getBufferSize(JNIEnv *env, jobject self)
{
    ScreenshotClient *client = getClient(env, self);
    if(client)
        return client->getSize();
    else
        return 0;
}

static void fillInFrameBufferMetrics(JNIEnv *env, jobject self, jobject di)
{
    /*
      Need to set:
        di.frameBufferWidth;
    di.frameBufferHeight;
    di.frameBufferFormat;
    di.frameBufferStride;
    di.frameBufferSize;
    */
    int fieldId;
    ScreenshotClient *client = getClient(env, self);
    jclass cls_DeviceInfo = env->FindClass(
    "android/os/RemoteControl$DeviceInfo");

    if (!cls_DeviceInfo || !client)
      return;
    
    jfieldID frameBufferWidthFid = env->GetFieldID(cls_DeviceInfo,
            "frameBufferWidth", "I");
    jfieldID frameBufferHeightFid = env->GetFieldID(cls_DeviceInfo,
            "frameBufferHeight", "I");
    jfieldID frameBufferFormatFid = env->GetFieldID(cls_DeviceInfo,
            "frameBufferFormat", "I");
    jfieldID frameBufferStrideFid = env->GetFieldID(cls_DeviceInfo,
            "frameBufferStride", "I");
    jfieldID frameBufferSizeFid = env->GetFieldID(cls_DeviceInfo,
            "frameBufferSize", "I");
    
    if (!frameBufferWidthFid || !frameBufferHeightFid || !frameBufferFormatFid ||
    !frameBufferStrideFid || !frameBufferSizeFid)
      return;
    
    env->SetIntField(di, frameBufferWidthFid, client->getWidth());
    env->SetIntField(di, frameBufferHeightFid, client->getHeight());
    env->SetIntField(di, frameBufferFormatFid, client->getFormat());
    env->SetIntField(di, frameBufferStrideFid, client->getStride());
    env->SetIntField(di, frameBufferSizeFid, client->getSize());
}

static JNINativeMethod method_table[] = {
    { "nRegisterScreenshotClient", "(I)I", (void*)registerScreenshotClient },
    { "nUnregisterScreenshotClient", "()V", (void*)unregisterScreenshotClient },
    { "nGrabScreen", "(Landroid/os/MemoryFile;ZII)I", (void*)grabScreen },
    { "nGetBufferSize", "()I", (void*)getBufferSize },
    { "nFillInFrameBufferMetrics", "(Landroid/os/RemoteControl$DeviceInfo;)V", (void*)fillInFrameBufferMetrics },
};

jint register_android_server_RemoteControlService(JNIEnv *env)
{
    // RemoteControlClient

    jclass clazz = env->FindClass("com/android/server/RemoteControlService$RemoteControlClient");
    LOG_FATAL_IF(!clazz, "Unable to find RemoteControlClient class");
    if (!clazz) {
      return -1;
    }

    int res = env->RegisterNatives(clazz,
                                   method_table, 
                                   sizeof(method_table) / sizeof(method_table[0]));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    gRemoteControlClientClassInfo.clazz = (jclass)env->NewGlobalRef(clazz);

    gRemoteControlClientClassInfo.nativeID = env->GetFieldID(gRemoteControlClientClassInfo.clazz,
                                                             "nativeID", "I");
    LOG_FATAL_IF(!gRemoteControlClientClassInfo.nativeID, "Unable to find nativeID field");

    // MemoryFile

    clazz = env->FindClass("android/os/MemoryFile");
    LOG_FATAL_IF(!clazz, "Unable to find MemoryFile class");
    if (!clazz) {
      return -1;
    }

    gMemoryFileClassInfo.clazz = (jclass)env->NewGlobalRef(clazz);

    gMemoryFileClassInfo.mAddress = env->GetFieldID(gMemoryFileClassInfo.clazz,
                                                    "mAddress", "I");
    LOG_FATAL_IF(!gMemoryFileClassInfo.mAddress, "Unable to find mAddress field");
    if (!gMemoryFileClassInfo.mAddress) {
      return -1;
    }

    return JNI_VERSION_1_4;
}

};
