/* Copyright (C) 2002-2012 RealVNC Ltd. All Rights Reserved. */

package android.os;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.Semaphore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.MotionEvent;

import android.os.IRemoteControlClient;

/**
 * This class allows suitably authorised applications to read from the
 * device screen and to inject input events into the device. This can
 * be used for taking screenshots, or for implementing VNC servers or
 * other remote control applications.
 *
 * <p>To start using remote control, call {@link #getRemoteControl(Context, ICallbacks)}
 * to create a RemoteControl object.</p>
 *
 * <p>To use this class, the user must explicitly authorise your
 * application. The remote control service is considered to be too
 * dangerous to expose via normal Android permissions as it would
 * allow a piece of malware to gain complete control over the device
 * and do anything that the user can do.</p>
 */

/* Client-side implementation of the remote control service.
 *
 * This code is considered "untrusted" for the purposes of security
 * checks, which should happen in RemoteControlService.java */

public class RemoteControl
{
    private static final String TAG = "RCClient";

    //public static final String BIND_SERVICE_INTENT = "com.realvnc.android.remote.BIND";
    public static final String BIND_SERVICE_INTENT = "com.android.remote.BIND";

    /* Exceptions that we can throw */

    /**
     * Base class for all exceptions thrown by this service.
     */
    public static class RemoteControlException extends Exception {}

    /**
     * Thrown if connecting to the remote control service failed. This
     * may mean that the service is not installed. However it can also
     * happen if the caller doesn't have the required permission.
     */
    public static class ServiceUnavailableException extends RemoteControlException {}

    /**
     * Thrown if the remote control service exits unexpectedly.
     * Usually this indicates an internal error in the remote control
     * service. It can also occur if the user force-stops the service.
     */
    public static class ServiceExitedException extends RemoteControlException {}

    /**
     * Thrown if getFrameBuffer() was unable to register the frame buffer.
     */
    public static class FrameBufferUnavailableException extends RemoteControlException {}

    /**
     * Thrown by grabScreen() if releaseFrameBuffer() is called while
     * a thread is waiting for a screen grab.
     */
    public static class DisconnectedException extends RemoteControlException {}

    /**
     * Thrown by grabScreen() if the 'incremental' parameter was set,
     * but the implementation doesn't support incremental grabs.
     */
    public static class IncrementalUpdatesUnavailableException extends RemoteControlException {}

    public static final int RC_SUCCESS = 0;
    public static final int RC_PERMISSION_DENIED = 1;
    public static final int RC_DEVICE_ADMIN_NOT_ENABLED = 2;
    public static final int RC_SERVICE_UNAVAILABLE = 3;
    public static final int RC_DISCONNECTED = 4;
    public static final int RC_INCREMENTAL_UPDATES_UNAVAILABLE = 5;

    /**
     * Callbacks from the RemoteControl object to its listener.
     *
     */
    public interface ICallbacks
    {
        /**
         * Notify the listener when the connection to the service has
         * completed (or failed).
         */
        public void connectionStatus(int status);

        /**
         * Notify the listener that the frame buffer orientation, flip
         * mode, etc has changed. The receiver should call
         * getDeviceInfo() to obtain the new frame buffer
         * parameters. */

        public void deviceInfoChanged();
    }

    /* This class serves two purposes: it receives callbacks from the
     * service, and its binder interface acts as a token to identify
     * us to the service. We have to use a static inner class here,
     * rather than the RemoteControl object, so that the service
     * doesn't hold a reference to the outer class. This allows the
     * outer class to be GCed if the application leaks a reference. */
    private static class CallbackHandler extends IRemoteControlClient.Stub {
        ICallbacks mListener;

        /**
         * Callback from the service indicating device info change
         *
         * {@hide}
         */
        public void deviceInfoChanged() {
            if(mListener != null) {
                mListener.deviceInfoChanged();
            }
        }
    }

    private CallbackHandler mCallbackHandler;
    private Context mCtx;

    private DeviceInfo mDeviceInfo = null;

    /**
     * {@hide}
     */
    public static final int TRANSACTION_getFrameBuffer = IBinder.FIRST_CALL_TRANSACTION + 0x1000;

    /**
     * {@hide}
     */
    private RemoteControl(Context ctx, ICallbacks listener) {
        mCallbackHandler = new CallbackHandler();
        mCallbackHandler.mListener = listener;

        mCtx = ctx;

        mServiceConnection = new RemoteControlServiceConnection();

        if(!ctx.bindService(new Intent(BIND_SERVICE_INTENT),
                            mServiceConnection,
                            Context.BIND_AUTO_CREATE)) {
            mCtx.unbindService(mServiceConnection);
            mCallbackHandler.mListener.connectionStatus(RC_SERVICE_UNAVAILABLE);
        }
    }

    private RemoteControlServiceConnection mServiceConnection;

    private IRemoteControl mServiceInterface;

    private class RemoteControlServiceConnection implements ServiceConnection {

        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceInterface = IRemoteControl.Stub.asInterface(service);

            int rv;

            try {
                rv = mServiceInterface.registerRemoteController(mCallbackHandler);
            } catch(SecurityException e) {
                rv = RC_PERMISSION_DENIED;
            } catch(RemoteException e) {
                rv = RC_SERVICE_UNAVAILABLE;
            }

            if(rv != RC_SUCCESS) {
                mCtx.unbindService(mServiceConnection);
                mServiceConnection = null;
            }

            mCallbackHandler.mListener.connectionStatus(rv);
        }

        public void onServiceDisconnected(ComponentName name) {
            mServiceInterface = null;
        }
    }

    /**
     * Class representing device information such as screen
     * resolution, flip state etc
     */
    public static class DeviceInfo implements Parcelable
    {
        /* We want to be able to extend this class in the future
         * without breaking compatibility.
         *
         * We can do this by appending fields to the end of the
         * Parcel, and increasing the version number so that the
         * receiver knows to expect the extra fields.
         *
         * If a new version of the service gets used with an older
         * version of the client stub, the client will only read the
         * older fields that it knows about. If an older version of
         * the service talks to a newer version of the client stub,
         * the client can avoid reading fields that aren't there, by
         * checking the version number. */

        private static final int VERSION = 1;

        /**
         * Width of the frame buffer in pixels. This never changes,
         * even if the device is rotated - in this case,
         * displayOrientation will change, but the width and height
         * will still reflect the native framebuffer orientation.
         */
        public int fbWidth;

        /**
         * Height of the frame buffer in pixels. Never changes - see
         * fbWidth.
         */
        public int fbHeight;

        /**
         * Current frame buffer pixel format. Never changes. This uses
         * the same pixel format constants as other code, such as
         * {@link android.graphics.PixelFormat}.
         */
        public int fbPixelFormat;

        /**
         * Current orientation of the frame buffer, in multiples of a
         * 90 degree rotation.
         */
        public int displayOrientation;

        public DeviceInfo() {
        }

        /*
         * Parcelable interface methods
         */

        public static final Parcelable.Creator<DeviceInfo> CREATOR = new Parcelable.Creator<DeviceInfo>() {
            public DeviceInfo createFromParcel(Parcel in) {
                DeviceInfo di = new DeviceInfo();
                di.readFromParcel(in);
                return di;
            }

            public DeviceInfo[] newArray(int size) {
                return new DeviceInfo[size];
            }
        };

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(VERSION);
            out.writeInt(fbWidth);
            out.writeInt(fbHeight);
            out.writeInt(fbPixelFormat);
            out.writeInt(displayOrientation);
        }

        public void readFromParcel(Parcel in) {
            int version = in.readInt();

            /* The first four fields are present in all versions. */
            fbWidth = in.readInt();
            fbHeight = in.readInt();
            fbPixelFormat = in.readInt();
            displayOrientation = in.readInt();

            /* Hypothetical code for future extension:

            if(version >= 2) {
                newParameter1 = in.readInt();
                newParameter2 = in.readInt();
            } else {
                newParameter1 = <sensible default>;
                newParameter2 = <sensible default>;
            }

            if(version >= 3) {
                newParameter3 = in.readInt();
            } else {
                newParameter3 = <sensible default>;
            }

            */
        }
    }

    /**
     * Request use of the remote control service. This function will
     * throw a {@link java.lang.SecurityException} if the caller is
     * not allowed to use remote control.
     *
     * @param listener Object implementing the {@link ICallbacks}
     * interface which will receive notification of device
     * configuration changes.
     *
     * @return A {@link RemoteControl} object
     *
     * @throws SecurityException Application is not authorised for
     * remote control.
     *
     * @throws ServiceUnavailableException if the remote control
     * service is not present.
     */
    public static RemoteControl getRemoteControl(Context ctx, ICallbacks listener)
        throws SecurityException {
        return new RemoteControl(ctx, listener);
    }

    /**
     * Stop using the remote control service. Call this method when
     * the {@link RemoteControl} object is no longer required.
     */
    public void release() {

        if(mServiceInterface != null) {
            try {
                mServiceInterface.unregisterRemoteController(mCallbackHandler);
            } catch(IllegalStateException e) {
                // This can happen if connecting to the service
                // failed. Ignore it here.
            } catch(RemoteException e) {
                // This happens if the service has exited. Ignore it
                // here, because we're exiting too.
            }
            mServiceInterface = null;
        }

        if(mServiceConnection != null) {
            try {
                mCtx.unbindService(mServiceConnection);
            } catch(IllegalStateException e) {
                // This can happen if connecting to the service
                // failed. Ignore it here.
            }
            mServiceConnection = null;
        }

        mCallbackHandler = null;
    }

    /**
     * If the application leaks a RemoteControl object, shut down the
     * binder interface cleanly.
     *
     * Note that there is no guarantee that this object will ever be
     * garbage collected, so it's not certain that this will run in
     * the case of a leak. To close down the remote control service
     * cleanly, call {@link #release()}. */
    protected void finalize() throws Throwable {
        try {
            if(mServiceConnection != null)
                release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Return the current device parameters such as screen resolution,
     * flip mode etc. The {@link
     * RemoteControl.ICallbacks#deviceInfoChanged()} callback
     * indicates that this information may have changed.
     *
     * @return a {@link RemoteControl.DeviceInfo} object.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public DeviceInfo getDeviceInfo() throws ServiceExitedException {
        DeviceInfo di;

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            mDeviceInfo = mServiceInterface.getDeviceInfo(mCallbackHandler);
            return mDeviceInfo;
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }

    /**
     * Ask for access to the contents of the screen.
     *
     * <p>The returned {@link android.os.MemoryFile MemoryFile} is
     * shared with the graphics subsystem. The {@link
     * #grabScreen(boolean)} method causes it to be updated with the
     * current frame buffer contents as a bitmap. </p>
     *
     * <p>You can only have one shared frame buffer at a time.</p>
     *
     * @param pixfmt Requested pixel format. One of the constants from
     * android.graphics.PixelFormat. {@link
     * FrameBufferUnavailableException} will be thrown if the
     * requested pixel format cannot be provided by the service.
     *
     * @param persistent If false, this function returns a single
     * snapshot of the current contents of the screen. Use this for
     * taking screenshots. If true, the contents of the returned
     * object need to be updated as the screen changes, by calling
     * {@link #grabScreen(boolean)}. In this case you will need to
     * call {@link #releaseFrameBuffer()} when you are finished
     * reading the screen. This case is intended for VNC servers or
     * other remote control applications.
     *
     * @return A {@link android.os.MemoryFile} object containing the
     * contents of the frame buffer.
     *
     * @throws FrameBufferUnavailableException An error occurred while
     * attempting to create the shared frame buffer.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public MemoryFile getFrameBuffer(int pixfmt, boolean persistent) throws FrameBufferUnavailableException, ServiceExitedException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            data.writeInterfaceToken("android.os.IRemoteControl");
            data.writeStrongBinder(mCallbackHandler.asBinder());
            data.writeInt(pixfmt);
            mServiceInterface.asBinder().transact(TRANSACTION_getFrameBuffer, data, reply, 0);

            int rv = reply.readInt();
            if(rv == 0) {
                ParcelFileDescriptor pfd = reply.readFileDescriptor();

                MemoryFile buff = new MemoryFile(pfd.getFileDescriptor(),
                                                 reply.readInt(), "r");

                mServiceInterface.grabScreen(mCallbackHandler, false);

                if(!persistent) {
                    mServiceInterface.releaseFrameBuffer(mCallbackHandler);
                }

                return buff;
            } else {
                throw new FrameBufferUnavailableException();
            }
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        } catch(IOException e) {
            throw new FrameBufferUnavailableException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Release the frame buffer.
     *
     * If you have obtained access to the frame buffer using {@link
     * #getFrameBuffer(int, boolean)} with persistent set to true, you
     * can release your claim to the frame buffer using this function.
     *
     * If another thread is currently blocked in {@link
     * #grabScreen(boolean)} it will exit with a {@link
     * DisconnectedException}.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public void releaseFrameBuffer() throws ServiceExitedException {

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            mServiceInterface.releaseFrameBuffer(mCallbackHandler);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }

    /**
     * Grab a screen image into the shared frame buffer, if it was
     * registered with 'persistent' set to true.
     *
     * This function can either grab the screen immediately, or it
     * can wait for a region to be updated before grabbing.
     *
     * In both cases, the returned {@link android.graphics.Region}
     * describes which areas of the frame buffer have been updated.
     *
     * @param incremental If false, this call captures the entire
     * screen and returns immediately. If true, the function blocks if
     * necessary until a change occurs.
     *
     * @throws DisconnectedException Another thread called {@link
     * #releaseFrameBuffer} while this function was waiting for a
     * screen update.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public Region grabScreen(boolean incremental) throws DisconnectedException, ServiceExitedException {
        int rv = 0;
        Region ret = new Region();

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            rv = mServiceInterface.grabScreen(mCallbackHandler, incremental);
            ret.set(0, 0, mDeviceInfo.fbWidth, mDeviceInfo.fbHeight);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
        if(rv != 0) {
            throw new DisconnectedException();
        }

        return ret;
    }

    /**
     * Inject a keyboard event into the system.
     *
     * @param ev Event to inject
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public void injectKeyEvent(KeyEvent ev) throws ServiceExitedException {

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            mServiceInterface.injectKeyEvent(mCallbackHandler, ev);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }

    /**
     * Inject a pointer event into the system.
     *
     * @param ev Event to inject
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public void injectMotionEvent(MotionEvent ev) throws ServiceExitedException {

        if(mServiceInterface == null)
            throw new ServiceExitedException();

        try {
            mServiceInterface.injectMotionEvent(mCallbackHandler, ev);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }
}
