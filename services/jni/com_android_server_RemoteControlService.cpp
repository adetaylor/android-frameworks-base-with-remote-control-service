/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "RemoteControlService"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_util_Binder.h"

#include <binder/IMemory.h>
#include <surfaceflinger/SurfaceComposerClient.h>

namespace android
{

static struct {
    jclass clazz;

    jfieldID nativeID;
} gRemoteControlClientClassInfo;


static void registerScreenshotClient(JNIEnv *env, jobject self, jint pixfmt)
{
    ScreenshotClient *client = new ScreenshotClient(pixfmt);
    client->update();
    env->SetIntField(self, gRemoteControlClientClassInfo.nativeID, (jint) client);
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

static void getBufferFd(JNIEnv *env, jobject self, jobject fd_obj)
{
    ScreenshotClient *client = getClient(env, self);
    int fd = client->getHeap()->getHeapID();
    env->SetIntField(fd_obj,
                     env->GetFieldID(env->FindClass("java/io/FileDescriptor"),
                                     "descriptor", "I"),
                     fd);
}

static jint getBufferLength(JNIEnv *env, jobject self)
{
    ScreenshotClient *client = getClient(env, self);
    int length = client->getHeap()->getSize();
    return length;
}

static int grabScreen(JNIEnv *env, jobject self, jboolean incremental)
{
    ScreenshotClient *client = getClient(env, self);
    int rv;

    if(incremental)
        rv = client->wait_update();
    else
        rv = client->update();

    if(rv != 0) {
        LOGE("grabScreen: %d (%s)\n", rv, strerror(-rv));
    }

    return rv;
}

static void signal(JNIEnv *env, jobject self)
{
    ScreenshotClient *client = getClient(env, self);
    client->signal();
}

static JNINativeMethod method_table[] = {
    { "nRegisterScreenshotClient", "(I)V", (void*)registerScreenshotClient },
    { "nUnregisterScreenshotClient", "()V", (void*)unregisterScreenshotClient },
    { "nGetBufferFd", "(Ljava/io/FileDescriptor;)V", (void*)getBufferFd },
    { "nGetBufferLength", "()I", (void*)getBufferLength },
    { "nGrabScreen", "(Z)I", (void*)grabScreen },
    { "nSignal", "()V", (void*)signal },
};

int register_android_server_RemoteControlService(JNIEnv *env)
{
    int res = jniRegisterNativeMethods(env,
                                       "com/android/server/RemoteControlService$RemoteControlClient",
                                       method_table, NELEM(method_table));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // RemoteControlClient

    jobject obj = env->FindClass("com/android/server/RemoteControlService$RemoteControlClient");
    LOG_FATAL_IF(!obj, "Unable to find RemoteControlClient class");
    gRemoteControlClientClassInfo.clazz = (jclass)env->NewGlobalRef(obj);

    gRemoteControlClientClassInfo.nativeID = env->GetFieldID(gRemoteControlClientClassInfo.clazz,
                                                             "nativeID", "I");
    LOG_FATAL_IF(!gRemoteControlClientClassInfo.nativeID, "Unable to find nativeID field");

    return 0;
}

};
