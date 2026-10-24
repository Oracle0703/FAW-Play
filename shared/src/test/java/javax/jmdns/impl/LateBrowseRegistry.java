package javax.jmdns.impl;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jmdns.impl.constants.DNSRecordClass;

/** Real JmDNS resolver, timers, cache and packet parser; outgoing wire is intercepted. */
public final class LateBrowseRegistry extends JmDNSImpl {
    private static final String TYPE = "_carplay-ctrl._tcp.local.";
    private final BlockingQueue<byte[]> queries = new LinkedBlockingQueue<>();
    public final AtomicInteger queryCount = new AtomicInteger();

    public LateBrowseRegistry() throws Exception {
        super(InetAddress.getByName("127.0.0.1"), "browse-regression-car");
    }

    @Override public void send(DNSOutgoing outgoing) {
        // The superclass may send while our fields are still being initialized.
        if (queries != null && outgoing.isQuery() && outgoing.getQuestions().stream()
                .anyMatch(question -> question.getName().equals(TYPE))) {
            byte[] wire = outgoing.data();
            queryCount.incrementAndGet();
            queries.offer(wire);
        }
    }

    public byte[] awaitQuery(long timeoutMillis) throws InterruptedException {
        return queries.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void clearQueries() { queries.clear(); }

    public int controlListenerCount() { return _serviceListeners.get(TYPE).size(); }

    public void deliverLatePhoneResponse() throws Exception {
        deliverLatePhoneResponse(null);
    }

    public void deliverLatePhoneResponse(String bluetoothId) throws Exception {
        String name = "late-phone." + TYPE;
        DNSOutgoing reply = new DNSOutgoing(0x8400);
        reply.addAnswer(new DNSRecord.Pointer(TYPE, DNSRecordClass.CLASS_IN, false, 120, name), 0);
        reply.addAnswer(new DNSRecord.Service(name, DNSRecordClass.CLASS_IN, true, 120,
                0, 0, 7000, "late-phone.local."), 0);
        byte[] txt = new byte[]{0};
        if (bluetoothId != null) {
            byte[] value = ("id=" + bluetoothId).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            txt = new byte[value.length + 1];
            txt[0] = (byte) value.length;
            System.arraycopy(value, 0, txt, 1, value.length);
        }
        reply.addAnswer(new DNSRecord.Text(name, DNSRecordClass.CLASS_IN, true, 120, txt), 0);
        reply.addAnswer(new DNSRecord.IPv4Address("late-phone.local.", DNSRecordClass.CLASS_IN,
                true, 120, InetAddress.getByName("127.0.0.2")), 0);
        byte[] wire = reply.data();
        handleResponse(new DNSIncoming(new DatagramPacket(wire, wire.length,
                InetAddress.getByName("127.0.0.2"), 5353)));
    }
}
