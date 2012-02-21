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
import android.util.Log;
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

    /**
     * Class returned with details of the memory area.
     */
    public static interface MemoryAreaInformation {
        public ParcelFileDescriptor getParcelFd();
        public int getSize();
    }

    public static final int RC_SUCCESS = 0;
    public static final int RC_PERMISSION_DENIED = 1;
    public static final int RC_DEVICE_ADMIN_NOT_ENABLED = 2;
    public static final int RC_SERVICE_UNAVAILABLE = 3;
    public static final int RC_DISCONNECTED = 4;
    public static final int RC_INCREMENTAL_UPDATES_UNAVAILABLE = 5;
    public static final int RC_SERVICE_ITSELF_LACKING_PERMISSIONS = 6;
    public static final int RC_SERVICE_LACKING_OTHER_OS_FACILITIES = 7;

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
    private IRemoteControl mServiceInterface;


    /**
     * {@hide}
     */
    public static final int TRANSACTION_getFrameBuffer = IBinder.FIRST_CALL_TRANSACTION + 0x1000;

    /**
     * {@hide}
     */
    private RemoteControl(Context ctx, ICallbacks listener) throws RemoteControlException {
        mCallbackHandler = new CallbackHandler();
        mCallbackHandler.mListener = listener;

        mCtx = ctx;

        int rv;
        IRemoteControl newServiceInterface = null;

        try {
            newServiceInterface = (IRemoteControl)mCtx.getSystemService("remote_control");
            rv = newServiceInterface.registerRemoteController(mCallbackHandler);
        } catch(SecurityException e) {
            rv = RC_PERMISSION_DENIED;
        } catch(RemoteException e) {
            rv = RC_SERVICE_UNAVAILABLE;
        }

        synchronized (RemoteControl.this) {
            if(rv == RC_SUCCESS)
                mServiceInterface = newServiceInterface;

            if (mCallbackHandler != null)
                mCallbackHandler.mListener.connectionStatus(rv);
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

        private static final int VERSION = 2;

        /**
         * Width of the display in pixels.
         *
         * This is the width of the display used to show the user
         * interface, it does not indicate the width of the captured
         * framebuffer. For example some devices will use a
         * framebuffer which is larger than this, only drawing to and
         * displaying an area of the framebuffer with width equal to
         * the value of this member.
         *
         * To get the width of the captured framebuffer use
         * frameBufferWidth if it's available.
         *
         * For compatibility reasons this member is still called fbWidth
         * even though it represents the display width.
         *
         * This never changes, even if the device is rotated - in this case,
         * displayOrientation will change, but the width and height
         * will still reflect the native framebuffer orientation.
         */
        public int fbWidth;

        /**
         * Height of the display in pixels.
         *
         * This is the height of the display used to show the user
         * interface, it does not indicate the height of the captured
         * framebuffer. For example some devices will use a
         * framebuffer which is larger than this, only drawing to and
         * displaying an area of the framebuffer with height equal to
         * the value of this member.
         *
         * To get the height of the captured framebuffer use
         * frameBufferHeight if it's available.
         *
         * For compatibily reasons this member is still called fbHeight
         * even though it represents the display height.
         *
         * This never changes, even if the device is rotated - in this case,
         * displayOrientation will change, but the width and height
         * will still reflect the native framebuffer orientation.
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

        /**
         * Current frameBuffer width in pixels or -1 if unknown.
         *
         * This represents the actual width of the current capture of
         * the framebuffer. This can be different from the width of
         * the device display for various reasons. For example, some
         * devices have a framebuffer larger than the display
         * dimensions, with the extra pixels in the framebuffer being
         * unused and never shown to the user.
         *
         * If the display width is required then fbWidth should be
         * used instead.
         *
         * Only valid in version 2 or greater.
         */
        public int frameBufferWidth;

        /**
         * Current frameBuffer height in pixels or -1 if unknown.
         *
         * This represents the actual height of the current capture of
         * the framebuffer. This can be different from the height of
         * the device display for various reasons. For example, some
         * devices have a framebuffer larger than the display
         * dimensions, with the extra pixels in the framebuffer being
         * unused and never shown to the user.
         *
         * If the display height is required then fbHeight should be
         * used instead.
         *
         * Only valid in version 2 or greater.
         */
        public int frameBufferHeight;

        /**
         * Current pixel format using values defined in
         * android.graphics.PixelFormat. Will be set to
         * android.graphics.PixelFormat.UNKNOWN if unknown.
         *
         * Only valid in version 2 or greater.
         */
        public int frameBufferFormat;

        /**
         * Current stride in pixels between rows in frameBuffers
         * or -1 if unknown.
         *
         * Only valid in version 2 or greater.
         */
        public int frameBufferStride;

        /**
         * Current frameBuffer size in bytes or -1 if unknown.
         *
         * Only valid in version 2 or greater.
         */
        public int frameBufferSize;

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
            out.writeInt(frameBufferWidth);
            out.writeInt(frameBufferHeight);
            out.writeInt(frameBufferFormat);
            out.writeInt(frameBufferStride);
            out.writeInt(frameBufferSize);
        }

        public void readFromParcel(Parcel in) {
            int version = in.readInt();

            /* The first four fields are present in all versions. */
            fbWidth = in.readInt();
            fbHeight = in.readInt();
            fbPixelFormat = in.readInt();
            displayOrientation = in.readInt();

            if(version >= 2) {
                frameBufferWidth = in.readInt();
                frameBufferHeight = in.readInt();
                frameBufferFormat = in.readInt();
                frameBufferStride = in.readInt();
                frameBufferSize = in.readInt();
            } else {
                frameBufferWidth = -1;
                frameBufferHeight = -1;
                frameBufferFormat = android.graphics.PixelFormat.UNKNOWN;
                frameBufferStride = -1;
                frameBufferSize = -1;
            }

            /* Hypothetical code for future extension:
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
     * Note that the resulting object is not ready for use until
     * you have received {@link ICallbacks#connectionStatus(int)}
     * with a value of {@link #RC_SUCCESS}.
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
        throws RemoteControlException, SecurityException {
        return new RemoteControl(ctx, listener);
    }

    /**
     * Discover if the remote control service is available.
     *
     * This does not attempt to authorize the caller
     * with the remote control service and should only be
     * used to determine if a remote control service is
     * available.
     *
     * @return true if a remote control service is available, false otherwise.
     */
    public static boolean serviceAvailable(Context ctx) {
        return (ctx.getSystemService("remote_control") != null);
    }

    /**
     * Stop using the remote control service. Call this method when
     * the {@link RemoteControl} object is no longer required.
     */
    public synchronized void release() {

        if(mServiceInterface != null && mCallbackHandler != null) {
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

        mCallbackHandler = null;
    }

    /**
     * If the application leaks a reference to a RemoteControl object,
     * shut down the binder interface cleanly.
     *
     * Note that there is no guarantee that this object will ever be
     * garbage collected, so it's not certain that this will run in
     * the case of a leak. To close down the remote control service
     * cleanly, call {@link #release()}. */
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Method to return an {@link IRemoteControl} which is guaranteed
     * not to be null. This is used by all the methods which wish to communicate
     * with the RemoteControlService backend. It's intended to be lock-free
     * just to make sure there's no locking overhead incurred in methods
     * which need to be high performance such as {@link #grabScreen(boolean)}.
     * @return An IRemoteControl to talk to the service
     */
    private IRemoteControl getServiceInterface() throws ServiceExitedException {
        IRemoteControl serviceInterface = mServiceInterface;
        if (serviceInterface == null)
            throw new ServiceExitedException();
        return serviceInterface;
    }

    /**
     * Method to return a {@link CallbackHandler} which is guaranteed
     * not to be null. It's intended to be lock-free
     * just to make sure there's no locking overhead incurred in methods
     * which need to be high performance such as {@link #grabScreen(boolean)}.
     * @return A CallbackHandler for the current interface.
     */
    private CallbackHandler getCallbackHandler() throws ServiceExitedException {
        CallbackHandler callbackHandler = mCallbackHandler;
        if (callbackHandler == null)
            throw new ServiceExitedException();
        return callbackHandler;
    }

    /**
     * Return the current device parameters such as screen resolution,
     * flip mode etc. The {@link
     * RemoteControl.ICallbacks#deviceInfoChanged()}
     * callback indicates that this information may have changed.
     *
     * @return a {@link RemoteControl.DeviceInfo} object.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public DeviceInfo getDeviceInfo() throws ServiceExitedException {
        DeviceInfo di;
        try {
            mDeviceInfo = getServiceInterface().getDeviceInfo(getCallbackHandler());
            return mDeviceInfo;
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }

    /**
     * A wrapper around {@link #getFrameBufferFd(int, boolean)} that
     * instead returns a {@link android.os.MemoryFile} with
     * the use of reflection.
     *
     * <p>The returned {@link android.os.MemoryFile MemoryFile} is
     * shared with the graphics subsystem. The {@link
     * #grabScreen(boolean, Rect)} method causes it to be updated with the
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
     * {@link #grabScreen(boolean, Rect)}. In this case you will need to
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
     *
     * @deprecated This should no longer be used as it is not compatible
     * with Ice Cream Sandwich. Use {@link #getFrameBufferFd(int, boolean)} instead.
     */
    public MemoryFile getFrameBuffer(int pixfmt, boolean persistent) throws FrameBufferUnavailableException, ServiceExitedException {
        MemoryAreaInformation mai = getFrameBufferFd(pixfmt, persistent);

        /* The MemoryFile constructor that we want to use is no longer
         * available in ICS, so this method will fail. */
        try {
            Class cls = Class.forName("android.os.MemoryFile");
            Constructor ctor = cls.getConstructor(new Class[] { FileDescriptor.class,
                                                                int.class,
                                                                String.class });
            MemoryFile mf = (MemoryFile) ctor.newInstance(new Object[] { mai.getParcelFd().getFileDescriptor(),
                                                                         mai.getSize(),
                                                                         "r" });
            return mf;
        } catch(Throwable e) {
            Log.println(Log.ERROR, TAG, "RemoteControl: reflection failure");
            e.printStackTrace();
            throw new FrameBufferUnavailableException();
        }
    }

    /**
     * Ask for access to the contents of the screen.
     *
     * <p>The returned {@link MemoryAreaInformation} represents the
     * {@link android.os.MemoryFile MemoryFile} that is
     * shared with the graphics subsystem. The {@link
     * #grabScreen(boolean, Rect)} method causes it to be updated with the
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
     * {@link #grabScreen(boolean, Rect)}. In this case you will need to
     * call {@link #releaseFrameBuffer()} when you are finished
     * reading the screen. This case is intended for VNC servers or
     * other remote control applications.
     *
     * @return A MemoryAreaInformation object with the details
     * of the {@link android.os.MemoryFile} object that contains the
     * contents of the frame buffer.
     *
     * @throws FrameBufferUnavailableException An error occurred while
     * attempting to create the shared frame buffer.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public MemoryAreaInformation getFrameBufferFd(int pixfmt, boolean persistent) throws FrameBufferUnavailableException, ServiceExitedException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        IRemoteControl serviceInterface = getServiceInterface();
        CallbackHandler callbackHandler = getCallbackHandler();

        try {
            data.writeInterfaceToken("android.os.IRemoteControl");
            data.writeStrongBinder(callbackHandler.asBinder());
            data.writeInt(pixfmt);
            serviceInterface.asBinder().transact(TRANSACTION_getFrameBuffer, data, reply, 0);

            int rv = reply.readInt();
            if(rv == 0) {
                final ParcelFileDescriptor pfd = reply.readFileDescriptor();
                final int size = reply.readInt();

                MemoryAreaInformation mai = new MemoryAreaInformation() {
                    public ParcelFileDescriptor getParcelFd() { return pfd; }
                    public int getSize() { return size; }
                };

                serviceInterface.grabScreen(callbackHandler, false);

                if(!persistent) {
                    serviceInterface.releaseFrameBuffer(callbackHandler);
                }

                return mai;
            } else {
                throw new FrameBufferUnavailableException();
            }
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Release the frame buffer.
     *
     * If you have obtained access to the frame buffer using {@link
     * #getFrameBuffer(int, boolean)} or
     * {@link #getFrameBufferFd(int, boolean)} with persistent set to true, you
     * can release your claim to the frame buffer using this function.
     *
     * If another thread is currently blocked in {@link
     * #grabScreen(boolean, Rect)} it will exit with a {@link
     * DisconnectedException}.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     */
    public void releaseFrameBuffer() throws ServiceExitedException {
        try {
            getServiceInterface().releaseFrameBuffer(getCallbackHandler());
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
     * In both cases, the {@link android.graphics.Rect} 'changedRect'
     * describes which areas of the frame buffer have been updated.
     *
     * Previous versions of this function dealt in terms of
     * {@link android.graphics.Region}} instead of
     * {@link android.graphics.Rect}}. In order to avoid having to allocate
     * memory for every single screen grab, we now deal using a single
     * Rect. In practice, this has no performance impact because
     * we never passed the Region across the IPC boundary to the
     * RemoteControlService, so it never got the opportunity to make
     * more sophisticated use of the Region than just to set a single
     * rectangle. If, in future, the Surface Flinger is able to give
     * us an accurate view of exactly what region has changed, we either
     * need to revert this API to using regions, or just accept that
     * we're going to take the bounding rect of the region.
     *
     * @param incremental If false, this call captures the entire
     * screen and returns immediately. If true, the function blocks if
     * necessary until a change occurs.
     *
     * @param changedRect A rectangle which, after the function returns, will
     * contain the area of the screen which has changed. This will
     * always be cleared and set to the actual changed area. We pass
     * it as a parameter instead of returning it, in order to avoid
     * necessitating memory allocations for every single grabScreen.
     * The caller should typically maintain a single Rect which
     * is passed for each call to grabScreen.
     *
     * @throws DisconnectedException Another thread called {@link
     * #releaseFrameBuffer} while this function was waiting for a
     * screen update.
     *
     * @throws ServiceExitedException if it was not possible to
     * contact the remote control service.
     *
     * @throws IncrementalUpdatesUnavailableException if an
     * incremental update was requested from an implementation that
     * doesn't support them.
     */
    public void grabScreen(boolean incremental, Rect changedRect)
        throws DisconnectedException, ServiceExitedException, IncrementalUpdatesUnavailableException {
        int rv = 0;

        try {
            rv = getServiceInterface().grabScreen(getCallbackHandler(), incremental);
            // Deal with older and newer versions of the DeviceInfo structure
            int w = mDeviceInfo.frameBufferWidth;
            if (w == -1)
                w = mDeviceInfo.fbWidth;
            int h = mDeviceInfo.frameBufferHeight;
            if (h == -1)
                h = mDeviceInfo.fbHeight;
            changedRect.set(0, 0, w, h);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
        if(rv != 0) {
            if(rv == RC_INCREMENTAL_UPDATES_UNAVAILABLE)
                throw new IncrementalUpdatesUnavailableException();
            else
                throw new DisconnectedException();
        }
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
        try {
            getServiceInterface().injectKeyEvent(getCallbackHandler(), ev);
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
        try {
            getServiceInterface().injectMotionEvent(getCallbackHandler(), ev);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }

    /**
     * @deprecated Used to check that the RemoteControlService had the required
     * permissions to control the phone, but this is now done upon connection
     * to the RemoteControlService. So, you wouldn't have been able to call
     * this method even if you wanted to - because you'd never get a valid
     * RemoteControl object on which to call it.
     */
    public boolean verifyPermissions() throws ServiceExitedException {
        return true;
    }

    /**
     * Sends a custom request to the RemoteControlService. This can be used
     * to make requests which are understood by certain OEM versions of the RemoteControlService
     * but not by others.
     *
     * Extension names should be in standard Java reverse-DNS notation, for example
     * "com.myandroidoem.EnablePhaseTractorBeams".
     *
     * RemoteControlService implementations must ignore any 'extensionType' which they
     * don't understand, and return a null Bundle. The corrollary is that
     * any RemoteControlService which <em>does</em> understand a certain request should
     * probably return a non-null Bundle, so that the caller can recognise that
     * the request has been processed.
     *
     * Examples of usage might be:
     * <ul>
     * <li>A certain Android OEM has two screens on their phone, and a request could be sent
     *     to switch remote control from one screen to the other
     * <li>A certain Android OEM has an extra hardware button which can only be accessed
     *     by something with 'signature' permissions.
     * </ul>
     *
     * In general it's a way for the VNC remote control systems to make use of 'signature'
     * level APIs and permissions, beyond the normal APIs required for basic remote control.
     * Of course, as with basic remote control, all such APIs can only be used thanks
     * to the assistance of the Android OEM.
     *
     * Note also the presence of {@link #customRequest(String, Bundle)}. The difference is
     * that the present method influences the behaviour of the RemoteControlService with
     * respect only to a single connection; the {@link #customRequest(String, Bundle)}
     * requests it to change its behaviour in a more general way which applies globally
     * for all clients which are using remote control. Most use-cases will find
     * 'customClientRequest' more appropriate than 'customRequest'.
     *
     * @param extensionType The extension name; as above this is given in reverse-DNS location
     * @param payload Any payload required for this custom message. This can be null; it's up
     *                to the custom message to interpret this however it likes.
     * @return null if the remote control service could not understand the message; non-null
     *              if the service did understand the message. In which case the content of
     *              the Bundle will be whatever is returned by that custom bit of the
     *              RemoteControlService.
     */
    public Bundle customClientRequest(String extensionType, Bundle payload) throws ServiceExitedException {
        try {
            return getServiceInterface().customClientRequest(getCallbackHandler(), extensionType, payload);
        } catch(RemoteException e) {
            // Earlier versions of the RemoteControlService didn't support customClientRequest,
            // so they may give us RemoteException. We'll assume that we're talking to an older
            // version and therefore just tell the client that this particular request wasn't
            // supported.
            return null;
        }
    }

    /**
     * Sends a globally-applicable custom request to the RemoteControlService. This is very
     * similar to {@link #customClientRequest(String, Bundle)} except that it changes the
     * global behaviour of the RemoteControlService, instead of just its behaviour with
     * respect to the present remote control session. Generally speaking,
     * {@link #customClientRequest(String, Bundle)} is usually more applicable.
     *
     * @param extensionType The extension name; as above this is given in reverse-DNS location
     * @param payload Any payload required for this custom message. This can be null; it's up
     *                to the custom message to interpret this however it likes.
     * @return null if the remote control service could not understand the message; non-null
     *              if the service did understand the message. In which case the content of
     *              the Bundle will be whatever is returned by that custom bit of the
     *              RemoteControlService.
     */
    public Bundle customRequest(String extensionType, Bundle payload) throws ServiceExitedException {
        try {
            return getServiceInterface().customRequest(extensionType, payload);
        } catch(RemoteException e) {
            throw new ServiceExitedException();
        }
    }
}
