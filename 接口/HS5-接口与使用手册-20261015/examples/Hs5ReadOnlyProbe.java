package example.hs5;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Android 9 HS5 read-only example. Call only from a worker thread after binding QISIAdapterService.
 * This class owns no ServiceConnection and performs no vehicle writes. Values remain raw unless named Celsius.
 */
public final class Hs5ReadOnlyProbe {
    private static final String ROOT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter";
    private final IBinder root;

    public Hs5ReadOnlyProbe(IBinder service) throws RemoteException {
        if (service == null || !ROOT.equals(service.getInterfaceDescriptor()))
            throw new RemoteException("Unexpected root service descriptor");
        root = service;
    }

    private IBinder module(int id, String expected) throws RemoteException {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(ROOT);
            request.writeInt(id);
            if (!root.transact(1, request, reply, 0)) throw new RemoteException("queryBinder unavailable");
            reply.readException();
            IBinder result = reply.readStrongBinder();
            if (result == null || !expected.equals(result.getInterfaceDescriptor()))
                throw new RemoteException("Module absent or descriptor mismatch");
            return result;
        } finally { request.recycle(); reply.recycle(); }
    }

    private int vehicleInt(int transaction) throws RemoteException {
        final String token = "ltd.qisi.adapterproxy.ICarVehicleStateManager";
        IBinder target = module(15, token);
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(token);
            if (!target.transact(transaction, request, reply, 0)) throw new RemoteException("Getter unavailable");
            reply.readException();
            if (reply.dataAvail() < 4) throw new RemoteException("Truncated reply");
            return reply.readInt();
        } finally { request.recycle(); reply.recycle(); }
    }

    /** 32766/32767 and negative values are unavailable. Unit is not confirmed. */
    public Integer speedRaw() throws RemoteException {
        int value = vehicleInt(7);
        return value >= 0 && value < 32766 ? value : null;
    }

    /** Preserve OEM coding; this example does not translate it to Android VehicleGear. */
    public Integer gearRaw() throws RemoteException {
        int value = vehicleInt(6);
        return (value >= 0 && value <= 8) || value == 14 ? value : null;
    }

    /** OEM getter already converts the CAN value to Celsius. */
    public Float ambientCelsius() throws RemoteException {
        final String token = "ltd.qisi.adapterproxy.ICarATCManager";
        IBinder target = module(33, token);
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(token);
            if (!target.transact(93, request, reply, 0)) throw new RemoteException("Getter unavailable");
            reply.readException();
            if (reply.dataAvail() < 4) throw new RemoteException("Truncated reply");
            float value = reply.readFloat();
            return !Float.isNaN(value) && !Float.isInfinite(value) && value >= -40f && value <= 86.5f ? value : null;
        } finally { request.recycle(); reply.recycle(); }
    }
}
