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

public class RemoteControlService extends Service implements IBinder.DeathRecipient
{
    private static final String TAG = "RemoteControlService";

    private Display mDisplay;
    private IWindowManager mWindowManager;
    private boolean mNativeLibraryLoadFailed;

    /**
     * Big global lock for everything relating to the remote control service.
     * Absolutely everything should be locked on this - see MOB-5499 for justification.
     * This does mean that some method calls might take a long time waiting for others
     * to complete, but we've decided that the frequency of multiple clients trying to
     * use RemoteControlService is so slim that a big global lock is acceptable.
     */
    private static Object mCondVar = new Object();

    /**
     * Enumeration used by the {@link AuthoriseActivity} to tell us the results
     * of the authorisation prompt.
     */
    enum AuthorisationResult {
        UNKNOWN,
        REJECTED,
        APPROVED
    }

    /**
     * Enumeration representing the state as to whether we're displaying an authorisation
     * prompt right now.
     */
    private enum AuthorisationState {
        IDLE,
        ACTIVE
    }

    /**
     * Class to represent an authorisation box which we need to display to the user.
     * We only display one of these at a time, so we keep them in a queue.
     */
    private static class PendingAuthorisation {
        PendingAuthorisation(Intent i) {
            mIntent = i;
        }

        Intent mIntent;
        AuthorisationResult mResult = AuthorisationResult.UNKNOWN;
    }

    /**
     * The queue of authorisation dialogs we need to display to the user at some point.
     */
    private LinkedList<PendingAuthorisation> mPendingAuthorisations = new LinkedList<PendingAuthorisation>();

    /**
     * Result of any currently displayed authorisation prompt. Only one is displayed
     * at once, thanks to locks, so it's safe to have a single one.
     */
    private static AuthorisationResult mAuthorisationResult;
    private AuthorisationState mAuthorisationState = AuthorisationState.IDLE;

    @Override
    public void onCreate() {

        try {
            System.loadLibrary("remotecontrol");
            mNativeLibraryLoadFailed = false;
        } catch (java.lang.UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load remote control native library, check that the correct shared library for the OS ABI is provided in the APK.");
            Log.e(TAG, "CPU_ABI: " + android.os.Build.CPU_ABI);
            Log.e(TAG, "CPU_ABI2: " + android.os.Build.CPU_ABI2);
            mNativeLibraryLoadFailed = true;
        }

        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
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

    private AuthorisationResult promptForAuthorisation(String pkgName) {

        Log.println(Log.INFO, TAG, "RemoteControl: authorisation prompt requested for "+pkgName);

            /* Perform security checks here and refuse to register the
             * client if it's not permitted to use the service */

            IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));

            try {
                dpm.ensureCallerHasPolicy(DeviceAdminInfo.USES_POLICY_REMOTE_CONTROL);
            } catch(SecurityException e) {
                return AuthorisationResult.DENIED;
            }

            return AuthorisationResult.APPROVED;
    }

    private static FileDescriptor MemoryFile_getFileDescriptor(MemoryFile mf) {
        // The method that we want to use is marked as '@hide' which
        // means it isn't available to .apk packages, even ones that
        // are compiled as part of the platform build.

        // So we use reflection, to avoid having to modify platform
        // core code.

        try {
            Class<?> cls = Class.forName("android.os.MemoryFile");
            Method meth = cls.getMethod("getFileDescriptor",
                                        new Class[] {});
            FileDescriptor fd = (FileDescriptor) meth.invoke(mf,
                                                             new Object[] {});
            return fd;
        } catch(Throwable e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: reflection failure");
            e.printStackTrace();
            return null;
        }
    }

    private static String getHexString(byte[] b) {
        StringBuilder result = new StringBuilder();
        for (int i=0; i < b.length; i++) {
            result.append(
                          Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 ));
        }
        return result.toString();
    }

    private static Set<String> getSystemSigningKeys(Context ctx) {
        PackageManager pm = ctx.getPackageManager();

        Set<String> results = new HashSet<String>();

        try {
            PackageInfo pi = pm.getPackageInfo("android", pm.GET_SIGNATURES);
            if(pi != null) {
                int i;

                Log.i(TAG, "Found " + pi.signatures.length + " system signing keys");

                for(i=0; i<pi.signatures.length; i++) {
                    MessageDigest md = MessageDigest.getInstance("SHA1");
                    md.update(pi.signatures[i].toByteArray());
                    String key = getHexString(md.digest());
                    results.add(key);
                    Log.i(TAG, "Key " + i + " is " + key);
                }

            }
        } catch(NoSuchAlgorithmException e) {
            Log.e(TAG, "Failed to find algorithm for system signing keys");
        } catch(PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to find package for system signing keys");
        }

        return results;
    }

    /* Binder API */
    private class BinderInterface extends IRemoteControl.Stub {

        public int registerRemoteController(IRemoteControlClient obj) throws SecurityException, RemoteException {
            synchronized (RemoteControlService.mCondVar) {
                if (mNativeLibraryLoadFailed) {
                    Log.e(TAG, "Failed to load native library, failing request to register controller");
                    return RemoteControl.RC_SERVICE_LACKING_OTHER_OS_FACILITIES;
                }

                // Shortly, we're going to start doing lots of security checks
                // to ensure whether the caller has the rights to remote control.
                // But before we do that, let's check whether we ourselves have
                // the right!
                PackageManager p = getApplicationContext().getPackageManager();
                if (p.checkPermission("android.permission.READ_FRAME_BUFFER", getPackageName()) != PackageManager.PERMISSION_GRANTED ||
                        p.checkPermission("android.permission.INJECT_EVENTS", getPackageName()) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "RemoteControlService hasn't been signed by the right certificate for this device.");
                    try {
                        PackageInfo pi = p.getPackageInfo(getPackageName(), p.GET_SIGNATURES);
                        if(pi.signatures != null)
                            for(Signature s : pi.signatures) {
                                MessageDigest md = MessageDigest.getInstance("SHA1");
                                md.update(s.toByteArray());
                                String key = getHexString(md.digest());
                                Log.e(TAG, "Package signed with key: " + key);
                            }
                        getSystemSigningKeys(getApplicationContext());

                    } catch(NoSuchAlgorithmException e) {
                        Log.e(TAG, "Failed to find algorithm for package signing keys");
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Failed to retrieve package signing key");
                    }
                    return RemoteControl.RC_SERVICE_ITSELF_LACKING_PERMISSIONS;
                }

                IBinder clientId = obj.asBinder();

                /* Perform security checks here and refuse to register the
                 * client if it's not permitted to use the service */

                String[] pkgs = getPackageManager().getPackagesForUid(getCallingUid());

                // TODO: what if there are more than one? For now just
                // disallow it
                if(pkgs.length != 1)
                    throw new SecurityException("Not authorised for remote control");

                String pkgName = pkgs[0];
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                boolean isAuthorised = prefs.getBoolean("is_authorised_" + pkgName, false);

                if(!isAuthorised) {
                    AuthorisationResult rv = promptForAuthorisation(pkgName);

                    if(rv != AuthorisationResult.APPROVED) {
                        throw new SecurityException("Not authorised for remote control");
                    }
                }

                SharedPreferences.Editor edit = prefs.edit();
                edit.putBoolean("is_authorised_" + pkgName, true);
                edit.commit();

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

                // In an ideal world, we would also here check to see if there
                // is an authorisation prompt visible, and hide it.
                // This could occur if the VNC server happens to 'reset'
                // or 'disconnect' whilst the user is being prompted.

                // But cancelling the notification isn't simple. It's
                // complicated because we don't know whether it would be at
                // the stage of being a mere notification, or if it had actually
                // progressed as far as being an Activity.

                // We're therefore not going to attempt to cancel pending
                // authorisation attempts.

                // We could go half-way, and iterate through mPendingNotifications
                // (and the currently displayed notification), fill in mResult
                // and mCondVar.notifyAll(). This would kick the relevant thread
                // out of its wait, but it wouldn't actually do anything to hide
                // any notifications or activities, so it doesn't seem worth
                // the benefit.
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
                            reply.writeFileDescriptor(MemoryFile_getFileDescriptor(mf));
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
