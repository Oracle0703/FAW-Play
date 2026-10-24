// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Observes the factory instrument exit request without taking ownership of any key. */
final class HongqiNavigationExitListener extends Binder {
    private static final String CONTROL = "ltd.qisi.adapterproxy.ICarSystemControlManager";
    private static final String LISTENER = "ltd.qisi.adapterproxy.listener.IVehicleSystemControlListener";
    private static final int REGISTER = 64, UNREGISTER = 65, FULL_SCREEN_REQUEST = 15;
    private final IBinder remote;
    private final Runnable onQuit;
    private volatile boolean closed;

    HongqiNavigationExitListener(IBinder remote, Runnable onQuit) throws RemoteException {
        if (!CONTROL.equals(remote.getInterfaceDescriptor())) throw new RemoteException("Control interface mismatch");
        this.remote = remote;
        this.onQuit = onQuit;
        try { registration(REGISTER); }
        catch (RemoteException | RuntimeException failure) { closed = true; throw failure; }
    }

    private void registration(int operation) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTROL);
            data.writeStrongBinder(this);
            if (!remote.transact(operation, data, reply, 0)) throw new RemoteException("Navigation listener unavailable");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    void close() throws RemoteException {
        closed = true;
        registration(UNREGISTER);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(LISTENER);
            return true;
        }
        if (code < 1 || code > 29) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(LISTENER);
        if (code == FULL_SCREEN_REQUEST) {
            String request = data.readString();
            if (!closed && "HardKeyQuit".equals(request)) onQuit.run();
        }
        // The remaining vendor callbacks are void, normally one-way notifications.
        if (reply != null) reply.writeNoException();
        return true;
    }
}
