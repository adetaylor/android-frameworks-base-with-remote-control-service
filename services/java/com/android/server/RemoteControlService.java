/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.util.HashMap;

import android.app.Service;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.IDevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteControl;
import android.os.IRemoteControlClient;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.RemoteControl;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Display;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

/* Server-side implementation of the remote control service.
 *
 * This code runs in the system server rather than the client process,
 * so it is responsible for enforcing all security restrictions. */

public class RemoteControlService extends Service implements IBinder.DeathRecipient
{
    private static final String TAG = "RemoteControlService";

    private Display mDisplay;

    private IWindowManager mWindowManager;

    @Override
    public void onCreate() {
        mClients = new HashMap<IBinder, RemoteControlClient>();

        mDisplay = WindowManagerImpl.getDefault().getDefaultDisplay();

        IBinder wmbinder = ServiceManager.getService("window");
        mWindowManager = IWindowManager.Stub.asInterface(wmbinder);

        try {
            mWindowManager.watchRotation(mInterface);
        } catch(RemoteException e) {
            // Ignore this error because there's not much we can do
            // about it, and if the window manager isn't running then
            // we probably couldn't have got here in the first place.
        }
    }

    /* Class representing a client connected to the remote control
     * service. */
    private class RemoteControlClient
    {
        public MemoryFile mFrameBuffer;

        /* Functions implemented in native code in services/jni/ */
        private native void nRegisterScreenshotClient(int pixfmt);
        private native void nUnregisterScreenshotClient();
        private native void nGetBufferFd(FileDescriptor f);
        private native int nGetBufferLength();
        private native int nGrabScreen(boolean incremental);
        private native void nSignal();
        int nativeID = 0;
        volatile boolean closing = false;

        private RemoteControlClient() {
            mFrameBuffer = null;
        }

        private synchronized void unregister() {
            if(mFrameBuffer != null) {
                Log.println(Log.ERROR, TAG, "Client didn't release frame buffer");
                unregisterScreenshotClient();
            }
            mFrameBuffer = null;
        }

        private synchronized void registerScreenshotClient(int pixfmt) throws IOException {
            nRegisterScreenshotClient(pixfmt);

            FileDescriptor fd = new FileDescriptor();
            nGetBufferFd(fd);
            mFrameBuffer = new MemoryFile(fd, nGetBufferLength(), "r");
        }

        private synchronized void unregisterScreenshotClient() {
            nUnregisterScreenshotClient();
            mFrameBuffer.close();
            mFrameBuffer = null;
        }

        private synchronized int grabScreen(boolean incremental) {
            return nGrabScreen(incremental);
        }

        protected void finalize() throws Throwable {
            try {
                if(mFrameBuffer != null) {
                    Log.println(Log.ERROR, TAG, "RemoteControlClient: releasing frame buffer in finalize()");
                    mFrameBuffer.close();
                }
                mFrameBuffer = null;
            } finally {
                super.finalize();
            }
        }
    };

    /* Map of all registered clients.
     *
     * A client is only added to the map if it is authorised to use
     * the remote control service. */
    private HashMap<IBinder, RemoteControlClient> mClients;

    private RemoteControl.DeviceInfo buildDeviceInfo() {
        RemoteControl.DeviceInfo di = new RemoteControl.DeviceInfo();

        di.fbPixelFormat = mDisplay.getPixelFormat();
        di.displayOrientation = mDisplay.getOrientation();

        if((di.displayOrientation & 1) == 0) {
            di.fbWidth = mDisplay.getWidth();
            di.fbHeight = mDisplay.getHeight();
        } else {
            di.fbHeight = mDisplay.getWidth();
            di.fbWidth = mDisplay.getHeight();
        }

        return di;
    }

    /* Callback from the window manager service when the screen rotation changes */
    public void onRotationChanged(int rotation) {
        for(IBinder clientId : mClients.keySet()) {
            IRemoteControlClient obj;

            obj = IRemoteControlClient.Stub.asInterface(clientId);

            try {
                obj.deviceInfoChanged();
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception during callback");
                e.printStackTrace();
            }
        }
    }

    /* Check that we're dealing with a correctly registered client.
     *
     * This is important for security. Every public entry point should
     * call this function to ensure that the client passed the
     * security check in registerRemoteController(). */
    private RemoteControlClient checkClient(IRemoteControlClient obj) {
        IBinder clientId = obj.asBinder();
        RemoteControlClient client = mClients.get(clientId);

        if(client == null) {
            /* client is not registered */
            Log.println(Log.ERROR, TAG, "Client " + clientId + " is not registered");
            throw new IllegalStateException("Client " + clientId + " is not registered");
        }

        return client;
    }

    public void binderDied() {
        /* Unfortunately the framework doesn't tell us _which_ binder
         * died. So we have to check all of them. */

        for(IBinder clientId : mClients.keySet()) {
            if(!clientId.isBinderAlive()) {
                mClients.get(clientId).unregister();
                mClients.remove(clientId);
            }
        }
    }

    /* Binder API */

    private class BinderInterface extends IRemoteControl.Stub implements IRotationWatcher {

        public synchronized int registerRemoteController(IRemoteControlClient obj) throws SecurityException, RemoteException {
            IBinder clientId = obj.asBinder();

            /* Perform security checks here and refuse to register the
             * client if it's not permitted to use the service */

            IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));

            try {
                dpm.ensureCallerHasPolicy(DeviceAdminInfo.USES_POLICY_REMOTE_CONTROL);
            } catch(SecurityException e) {
                return RemoteControl.RC_DEVICE_ADMIN_NOT_ENABLED;
            }

            /* The client is authorised - add it to the registered clients
             * map */

            if(mClients.containsKey(clientId)) {
                Log.println(Log.ERROR, TAG, "Already registered: " + clientId);
                throw new IllegalStateException("Already registered: " + clientId);
            } else {
                RemoteControlClient client = new RemoteControlClient();

                mClients.put(clientId, client);

                try {
                    clientId.linkToDeath(RemoteControlService.this, 0);
                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                }
            }

            return 0;
        }

        public synchronized void unregisterRemoteController(IRemoteControlClient obj) {
            IBinder clientId = obj.asBinder();
            RemoteControlClient client = checkClient(obj);

            client.unregister();
            mClients.remove(clientId);
        }

        public RemoteControl.DeviceInfo getDeviceInfo(IRemoteControlClient obj) {
            RemoteControlClient client = checkClient(obj);
            return buildDeviceInfo();
        }

        public synchronized MemoryFile getFrameBuffer(IRemoteControlClient obj, int pixfmt) throws IOException {
            RemoteControlClient client = checkClient(obj);

            if(client.nativeID == 0) {
                client.registerScreenshotClient(pixfmt);
                return client.mFrameBuffer;
            } else return null;
        }

        public synchronized void releaseFrameBuffer(IRemoteControlClient obj) {
            RemoteControlClient client = checkClient(obj);

            client.closing = true;

            if(client.nativeID != 0) {
                try {
                    client.nSignal();
                    client.unregisterScreenshotClient();

                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                }
            }
        }

        public int grabScreen(IRemoteControlClient obj, boolean incremental) {
            RemoteControlClient client = checkClient(obj);

            if((client.nativeID == 0) || client.closing)
                return -1;

            int rv = client.grabScreen(incremental);

            if((client.nativeID == 0) || client.closing)
                return -1;

            return rv;
        }

        public void injectKeyEvent(IRemoteControlClient obj, KeyEvent event) {
            RemoteControlClient client = checkClient(obj);

            try {
                long previousIdentity = clearCallingIdentity();
                mWindowManager.injectKeyEvent(event, true);
                restoreCallingIdentity(previousIdentity);
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                e.printStackTrace();
            }
        }

        public void injectMotionEvent(IRemoteControlClient obj, MotionEvent event) {
            RemoteControlClient client = checkClient(obj);

            try {
                long previousIdentity = clearCallingIdentity();
                mWindowManager.injectPointerEvent(event, false);
                restoreCallingIdentity(previousIdentity);
            } catch(Exception e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                e.printStackTrace();
            }
        }

        /* Custom Binder transaction implementation for getFrameBuffer(),
         * because AIDL doesn't do file descriptor passing. */

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code)
            {
            case RemoteControl.TRANSACTION_getFrameBuffer: {
                data.enforceInterface("android.os.IRemoteControl");
                IBinder b = data.readStrongBinder();
                int pixfmt = data.readInt();
                IRemoteControlClient obj = IRemoteControlClient.Stub.asInterface(b);
                try {
                    MemoryFile fb = this.getFrameBuffer(obj, pixfmt);
                    if(fb != null) {
                        reply.writeInt(0);
                        reply.writeFileDescriptor(fb.getFileDescriptor());
                        reply.writeInt(fb.length());
                    } else {
                        reply.writeInt(-1);
                    }
                    reply.writeNoException();
                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                    reply.writeException(e);
                }
                return true;
            }
            }
            return super.onTransact(code, data, reply, flags);
        }

        /* Callback from the window manager service when the screen rotation changes */
        public void onRotationChanged(int rotation) {
            for(IBinder clientId : mClients.keySet()) {
                IRemoteControlClient obj;

                obj = IRemoteControlClient.Stub.asInterface(clientId);

                try {
                    obj.deviceInfoChanged();
                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                }
            }
        }


    }

    private BinderInterface mInterface = new BinderInterface();

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();

        if (action.equals(RemoteControl.BIND_SERVICE_INTENT)) {
            return mInterface;
        } else {
            Log.println(Log.ERROR, TAG, "onBind: Unknown intent: " + action);
            return null;
        }
    }
}
