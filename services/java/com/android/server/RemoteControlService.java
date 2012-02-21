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

package com.android.server;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.Semaphore;

import android.app.Service;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.IDevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import android.os.IRemoteControl;
import android.os.IRemoteControlClient;
import android.os.RemoteControl;


/* Server-side implementation of the remote control service.
 *
 * This code runs in the system server rather than the client process,
 * so it is responsible for enforcing all security restrictions. */

public class RemoteControlService extends IRemoteControl.Stub implements IBinder.DeathRecipient
{
    private static final String TAG = "RemoteControlService";

    private Context mContext;
    private Display mDisplay;
    private IWindowManager mWindowManager;

    /**
     * Big global lock for everything relating to the remote control service.
     * Absolutely everything should be locked on this - see MOB-5499 for justification.
     * This does mean that some method calls might take a long time waiting for others
     * to complete, but we've decided that the frequency of multiple clients trying to
     * use RemoteControlService is so slim that a big global lock is acceptable.
     */
    private static Object mCondVar = new Object();

    public void RemoteControlService(Context context) {
		mContext = context;
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = wm.getDefaultDisplay();
        mWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
    }

    /* Class representing a client connected to the remote control
     * service. */
    private class RemoteControlClient {
        private native int nRegisterScreenshotClient(int pixfmt);
        private native void nUnregisterScreenshotClient();
        private native int nGrabScreen(MemoryFile buffer, boolean incremental, int requestedW, int requestedH);
        private native int nGetBufferSize();
        private native void nFillInFrameBufferMetrics(RemoteControl.DeviceInfo di);

        private int nativeID = 0;
        private volatile boolean mHasFrameBuffer = false;

        private int mGrabPixelFormat;

        private MemoryFile mSharedBuffer;

        private int mCurrentRotation;

        private IRemoteControlClient mListener;

        // The scaled size (if any) that will be requested from SurfaceFlinger.
        private int mScaledW;
        private int mScaledH;

        private RemoteControlClient(IRemoteControlClient listener) {
            mListener = listener;
            mGrabPixelFormat = PixelFormat.RGBA_8888;
        }

        /* Binder API */

        RemoteControl.DeviceInfo getDeviceInfo() {
            RemoteControl.DeviceInfo di = new RemoteControl.DeviceInfo();

            di.fbPixelFormat = mGrabPixelFormat;
            di.displayOrientation = mDisplay.getRotation();

            /* From Ice Cream Sandwich onwards Display.getWidth() no longer returns the full
             * width of the display as it doesn't take into account the soft-keys. So we need to
             * use a different API that is not present in earlier Android versions to get it. */
            if((di.displayOrientation & 1) == 0) {
                try {
                    di.fbWidth = (Integer)Display.class.getMethod("getRawWidth").invoke(mDisplay);
                    di.fbHeight = (Integer)Display.class.getMethod("getRawHeight").invoke(mDisplay);
                } catch (Throwable t) {
                    di.fbWidth = mDisplay.getWidth();
                    di.fbHeight = mDisplay.getHeight();
                }
            } else {
                try {
                    di.fbHeight = (Integer)Display.class.getMethod("getRawWidth").invoke(mDisplay);
                    di.fbWidth = (Integer)Display.class.getMethod("getRawHeight").invoke(mDisplay);
                } catch (Throwable t) {
                    di.fbHeight = mDisplay.getWidth();
                    di.fbWidth = mDisplay.getHeight();
                }
            }

            /* Fill in unknown as default values as the
               native method might fail */
            di.frameBufferWidth = -1;
            di.frameBufferHeight = -1;
            di.frameBufferFormat = android.graphics.PixelFormat.UNKNOWN;
            di.frameBufferStride = -1;
            di.frameBufferSize = -1;

            nFillInFrameBufferMetrics(di);

            return di;
        }

        MemoryFile getFrameBuffer(int pixfmt) throws IOException {

            if(mHasFrameBuffer || (pixfmt != mGrabPixelFormat))
                return null;

            mCurrentRotation = mDisplay.getRotation();
            mHasFrameBuffer = true;

            if(nRegisterScreenshotClient(pixfmt) < 0)
                return null;

            int size = nGetBufferSize();
            mSharedBuffer = new MemoryFile("SharedFrameBuffer", size);
            return mSharedBuffer;
        }

        void releaseFrameBuffer() {

            if(mHasFrameBuffer) {
                mHasFrameBuffer = false;

                try {
                    nUnregisterScreenshotClient();

                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                }
            }
        }

        int grabScreen(boolean incremental) {

            boolean loop = false;
            int rv = -1;

            if(incremental)
                return RemoteControl.RC_INCREMENTAL_UPDATES_UNAVAILABLE;

            if(!mHasFrameBuffer)
                return RemoteControl.RC_DISCONNECTED;

            // Poll for screen orientation changes. We could use
            // call IWindowManager.watchRotation() instead.

            int rotation = mDisplay.getRotation();
            if(rotation != mCurrentRotation) {
                mCurrentRotation = rotation;
                try {
                    if(mListener != null)
                        mListener.deviceInfoChanged();
                } catch(RemoteException e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception in grabScreen");
                    e.printStackTrace();
                }
                return 0;
            } else {
                rv = nGrabScreen(mSharedBuffer, incremental, mScaledW, mScaledH);
            }

            return rv;
        }

        void injectKeyEvent(KeyEvent event) {
            try {
                mWindowManager.injectKeyEvent(event, false);
            } catch(RemoteException e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception in injectKeyEvent");
                e.printStackTrace();
            }
        }

        void injectMotionEvent(MotionEvent event) {
            try {
                mWindowManager.injectPointerEvent(event, false);
            } catch(RemoteException e) {
                Log.println(Log.ERROR, TAG, "RemoteControl: exception in injectMotionEvent");
                e.printStackTrace();
            }
        }

        void release() {
            if(mHasFrameBuffer)
                releaseFrameBuffer();
            mSharedBuffer = null;
            mListener = null;
        }

        Bundle customClientRequest(String extensionType, Bundle payload) {
            if (extensionType.equals("com.realvnc.serversidescaling")) {
                // Ignore version for now since we only support v1.
                mScaledW = payload.getInt("width");
                mScaledH = payload.getInt("height");
                int rv = grabScreen(false);
                try {
                    if(mListener != null)
                        /* Get the client to call getDeviceInfo(), which
                         * will now contain details of the scaled screen. */
                        mListener.deviceInfoChanged();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to inform client that screen parameters have changed after scaling request");
                }
                Bundle response = new Bundle();
                response.putInt("err", rv);
                return response;
            } else
                return null;
        }
    }

    /* Map of all registered clients.
     *
     * A client is only added to the map if it is authorised to use
     * the remote control service. */
    private HashMap<IBinder, RemoteControlClient> mClients = new HashMap<IBinder, RemoteControlClient>();

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

    private void cleanupClient(IBinder clientId) {
        clientId.unlinkToDeath(this, 0);
        RemoteControlClient client = mClients.get(clientId);
        if(client != null)
            client.release();
        mClients.remove(clientId);
    }

    public void binderDied() {
        /* Unfortunately the framework doesn't tell us _which_ binder
         * died. So we have to check all of them. */

        synchronized (RemoteControlService.mCondVar) {
            for(IBinder clientId : mClients.keySet()) {
                if(!clientId.isBinderAlive()) {
                    Log.println(Log.INFO, TAG, "binderDied: nuking " + clientId);
                    cleanupClient(clientId);
                    /* Need to return immediately as mClients will have been modified */
                    return;
                }
            }
        }
    }

    /* Binder API */
    public int registerRemoteController(IRemoteControlClient obj) throws SecurityException, RemoteException {
        synchronized (RemoteControlService.mCondVar) {
            IBinder clientId = obj.asBinder();

            /* Perform security checks here and refuse to register the
             * client if it's not permitted to use the service */

            IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
            /* Will throw SecurityException if remote control not permitted */
            dpm.ensureCallerHasPolicy(DeviceAdminInfo.USES_POLICY_REMOTE_CONTROL);

            /* The client is authorised - add it to the registered clients
             * map */

            if(mClients.containsKey(clientId)) {
                Log.println(Log.ERROR, TAG, "Already registered: " + clientId);
                throw new IllegalStateException("Already registered: " + clientId);
            } else {
                RemoteControlClient client = new RemoteControlClient(obj);

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
    }

    public void unregisterRemoteController(IRemoteControlClient obj) {
        synchronized (RemoteControlService.mCondVar) {
            IBinder clientId = obj.asBinder();
            RemoteControlClient client = checkClient(obj);
            client.release();
            cleanupClient(clientId);
        }
    }

    public RemoteControl.DeviceInfo getDeviceInfo(IRemoteControlClient obj) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            return client.getDeviceInfo();
        }
    }

    public void releaseFrameBuffer(IRemoteControlClient obj) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            client.releaseFrameBuffer();
        }
    }

    public int grabScreen(IRemoteControlClient obj, boolean incremental) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            return client.grabScreen(incremental);
        }
    }

    public void injectKeyEvent(IRemoteControlClient obj, KeyEvent event) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            client.injectKeyEvent(event);
        }
    }

    public void injectMotionEvent(IRemoteControlClient obj, MotionEvent event) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            client.injectMotionEvent(event);
        }
    }

    /**
     * @deprecated This is no longer used. See comments in {@link RemoteControl}.
     */
    public boolean verifyPermissions() {
        return true;
    }

    public Bundle customRequest(String extensionType, Bundle payload) {
        /* This version of the Remote Control Service does not support any
         * extensions at the present time.
         */
        return null;
    }

    public Bundle customClientRequest(IRemoteControlClient obj, String extensionType, Bundle payload) {
        synchronized (RemoteControlService.mCondVar) {
            RemoteControlClient client = checkClient(obj);
            return client.customClientRequest(extensionType, payload);
        }
    }

    /* Custom Binder transaction implementation for getFrameBuffer(),
     * because AIDL doesn't do file descriptor passing. */

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code)
        {
        case RemoteControl.TRANSACTION_getFrameBuffer: {
            synchronized (RemoteControlService.mCondVar) {
                data.enforceInterface("android.os.IRemoteControl");
                IBinder b = data.readStrongBinder();
                int pixfmt = data.readInt();
                IRemoteControlClient obj = IRemoteControlClient.Stub.asInterface(b);
                RemoteControlClient client = checkClient(obj);

                try {
                    MemoryFile mf = client.getFrameBuffer(pixfmt);
                    if(mf != null) {
                        reply.writeInt(0);
                        reply.writeFileDescriptor(mf.getFileDescriptor());
                        reply.writeInt(mf.length());
                        reply.writeNoException();
                    } else {
                        reply.writeInt(-1);
                    }
                } catch(Exception e) {
                    Log.println(Log.ERROR, TAG, "RemoteControl: exception");
                    e.printStackTrace();
                    Log.println(Log.ERROR, TAG, "RemoteControl: writing exception to reply");
                    reply.writeException(e);
                }
                return true;
            }
        }
        }
        return super.onTransact(code, data, reply, flags);
    }
}
