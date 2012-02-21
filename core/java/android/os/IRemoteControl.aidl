/*
 * Copyright (C) 2010-2012 The Android Open Source Project
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

package android.os;

import android.os.IRemoteControlClient;
import android.os.RemoteControl;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * {@hide}
 */
interface IRemoteControl
{
    int registerRemoteController(IRemoteControlClient obj);
    void unregisterRemoteController(IRemoteControlClient obj);

    RemoteControl.DeviceInfo getDeviceInfo(IRemoteControlClient client);

    void injectKeyEvent(IRemoteControlClient obj, in KeyEvent event);
    void injectMotionEvent(IRemoteControlClient obj, in MotionEvent event);

    /* Note that getFrameBuffer() is not defined here as
     * RemoteControl.java performs the related binder transaction
     * manually. If it was defined here it would look more or less
     * like this:
     *
     * import android.os.MemoryFile;
     *
     * MemoryFile getFrameBuffer(IRemoteControlClient obj, int pixfmt); */

    void releaseFrameBuffer(IRemoteControlClient obj);

    int grabScreen(IRemoteControlClient obj, boolean incremental);

    /* No longer used. Kept in here to maintain compatibility with older
     * versions of the RemoteControlService available in certain Android
     * OEM phones. */
    boolean verifyPermissions();

    Bundle customRequest(String extensionName, in Bundle payload);

    Bundle customClientRequest(IRemoteControlClient client, String extensionName, in Bundle payload);
}
