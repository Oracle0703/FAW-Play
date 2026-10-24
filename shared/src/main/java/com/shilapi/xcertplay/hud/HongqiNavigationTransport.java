// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Qisi wire encoding. Negative business results remain distinct from a dead transport. */
final class HongqiNavigationTransport {
    static final class Rejected extends RemoteException {
        final int result;
        Rejected(int result) { super("navigation result " + result); this.result = result; }
    }

    static final class CallFailure extends RemoteException {
        final IBinder target;
        CallFailure(IBinder target, RemoteException cause) {
            super("navigation transport unavailable");
            this.target = target;
            initCause(cause);
        }
    }

    static void checkResult(int result) throws RemoteException {
        if (result < 0) throw new Rejected(result);
    }

    static void fullMap(IBinder binder, String token, int status) throws RemoteException {
        call(binder, token, 10, 1, 0, status);
    }

    static void call(IBinder binder, String token, int transaction, int... values) throws RemoteException {
        if (binder == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(token);
            for (int value : values) data.writeInt(value);
            if (!binder.transact(transaction, data, reply, 0)) throw new RemoteException("navigation transaction rejected");
            reply.readException();
            checkResult(reply.readInt());
        } catch (Rejected rejected) {
            throw rejected;
        } catch (RemoteException failure) {
            throw new CallFailure(binder, failure);
        } finally { data.recycle(); reply.recycle(); }
    }
}
