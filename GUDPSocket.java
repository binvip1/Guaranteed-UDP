import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;

    LinkedList<GUDPEndPoint> sendList;
    LinkedList<GUDPEndPoint> receiveList;
    List<Integer> ACKList;

    SendThread s;
    ReceiveThread r;
    Thread senderThread;
    Thread receiverThread;

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
        sendList = new LinkedList<>();
        receiveList = new LinkedList<>();
        s = new SendThread();
        senderThread = new Thread(s);
        r = new ReceiveThread();
        receiverThread = new Thread(r);
        ACKList = new CopyOnWriteArrayList<>();
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        GUDPEndPoint sendEndPoint = getEndPoint(sendList, packet.getAddress(), packet.getPort());

        //if there are no endpoint -> create new endpoint -> create BSN packet
        if(sendEndPoint == null) {
            System.out.println("Message: No sending endpoint, creating new endpoint");
            sendEndPoint = new GUDPEndPoint(packet.getAddress(), packet.getPort());
            sendList.add(sendEndPoint);
            GUDPPacket BSN_packet = createBSNPacket((InetSocketAddress) packet.getSocketAddress());

            //add BSN packet into the buffer
            sendEndPoint.add(BSN_packet);
            sendEndPoint.setNextseqnum(BSN_packet.getSeqno());
            sendEndPoint.setLast(BSN_packet.getSeqno()+1);
            sendEndPoint.setBase(BSN_packet.getSeqno());
        }

        //add the first data packet into the buffer
        gudppacket.setSeqno(sendEndPoint.getLast());
        sendEndPoint.add(gudppacket);
        sendEndPoint.setLast(gudppacket.getSeqno()+1);

    }

    public void receive(DatagramPacket packet) throws IOException {
        if(!receiverThread.isAlive()) {
            receiverThread.start();
        }

        boolean redo = false;
        synchronized(receiveList) {
            do {
                redo = false;

                while(receiveList.size() <= 0) {
                    try {
                        receiveList.wait();
                    }
                    catch (InterruptedException e) {
                        System.err.println("InterruptedException in receive() null receiveEndPoint");
                        e.printStackTrace();
                    }
                }

                GUDPEndPoint receiveEndPoint = receiveList.getFirst();

                while(receiveEndPoint.isEmptyBuffer()) {
                    try {
                        receiveList.wait();
                    } catch (InterruptedException e) {
                        System.err.println("InterruptedException in receive() isEmptyBuffer");
                        e.printStackTrace();
                    }
                }

                GUDPPacket p = receiveEndPoint.remove();
                if(receiveEndPoint.isEmptyBuffer()
                    && (receiveEndPoint.getState() == GUDPEndPoint.endPointState.FINISHED)) {
                    System.out.println("receive() end point state: " + receiveEndPoint.getState());
                    receiveList.remove(receiveEndPoint);
                    redo = true;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        System.err.println("InterruptedException in finish()");
                        e.printStackTrace();
                    }
                }
                else {
                    System.out.println("packet sqn: " + p.getSeqno());
                    p.decapsulate(packet);
                }
            } while(redo);
        }
    }

    public void finish() throws IOException {
        System.out.println("Message: finish() Start sending packets");

        //create FIN packet for each endpoint
        for(int i = 0; i < sendList.size(); i++) {
            GUDPEndPoint endPoint = sendList.get(i);

            GUDPPacket packet = createFINPacket(endPoint);
            endPoint.add(packet);

            System.out.println("finish() packet type: " + endPoint.getPacket(endPoint.getLast()).getType());
            System.out.println("Last packet sqn: " + endPoint.getLast());
        }
        receiverThread.start();
        senderThread.start();
    }

    public void close() throws IOException {

    }

    private GUDPEndPoint getEndPoint(LinkedList<GUDPEndPoint> list, InetAddress address, int port) {
        synchronized(list) {
            for(int i = 0; i < list.size(); i++) {
                if((list.get(i).getRemoteEndPoint().getAddress().equals(address))
                 && (list.get(i).getRemoteEndPoint().getPort() == port)) {
                    return list.get(i);
                 }
            }
        return null;
        }
    }

    private GUDPPacket createBSNPacket (InetSocketAddress address) {
        ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int BSN = new Random().nextInt(Integer.MAX_VALUE);

        GUDPPacket gudpPacket = new GUDPPacket(buffer);
        gudpPacket.setSocketAddress(address);
        gudpPacket.setType(GUDPPacket.TYPE_BSN);
        gudpPacket.setSeqno(BSN);
        gudpPacket.setVersion(GUDPPacket.GUDP_VERSION);
        gudpPacket.setPayloadLength(0);

        return gudpPacket;
    }

    private GUDPPacket createFINPacket (GUDPEndPoint endPoint) {
        ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);

        GUDPPacket gudpPacket = new GUDPPacket(buffer);
        gudpPacket.setSocketAddress(endPoint.getPacket(endPoint.getBase()).getSocketAddress());
        gudpPacket.setType(GUDPPacket.TYPE_FIN);
        gudpPacket.setSeqno(endPoint.getLast());
        gudpPacket.setVersion(GUDPPacket.GUDP_VERSION);
        gudpPacket.setPayloadLength(0);

        return gudpPacket;
    }

    class SendThread implements Runnable {
        boolean isFinish;
        int endPointIndex;
        int ACKIndex;
        int bufferSize;

        public SendThread() {
            isFinish = false;
            endPointIndex = 0;
            ACKIndex = 0;
        }

        public void run() {
            System.out.println("Message: sendThread is running");

            while(!isFinish) {
                try {
                    GUDPEndPoint endpoint = sendList.get(endPointIndex);

                    for(int i = ACKIndex; i < ACKList.size(); i++) {
                        //base = lowest sqn packet not yet acked
                        endpoint.setBase(Math.max(endpoint.getBase(), ACKList.get(i)));
                    }

                    if(endpoint.getNextseqnum() < Math.min(endpoint.getLast()+1, endpoint.getBase() + GUDPEndPoint.MAX_WINDOW_SIZE)) {
                        int usable_window = Math.min(endpoint.getLast(), endpoint.getBase()
                                                    + GUDPEndPoint.MAX_WINDOW_SIZE - 1);

                        int i = endpoint.getNextseqnum();
                        System.out.println("Usasble window: " + usable_window);

                        while(i <= usable_window) {
                            GUDPPacket gudpPacket = endpoint.getPacket(i);
                            DatagramPacket udpPacket = gudpPacket.pack();
                            datagramSocket.send(udpPacket);
                            endpoint.setNextseqnum(i+1);
                            i++;
                            System.out.println("Sending packet " + gudpPacket.getSeqno());
                            System.out.println("Packet type: " + gudpPacket.getType());
                        }

                        endpoint.startTimer();
                    }

                    //All packets sent successfully
                    if(endpoint.getBase() == endpoint.getLast()+1) {
                        System.out.println("Base: " + endpoint.getBase());
                        System.out.println("Last sequence: " + endpoint.getLast());
                        System.out.println("Message: All packets sent successfully");
                        endpoint.setState(GUDPEndPoint.endPointState.FINISHED);
                        //empty buffer
                        endpoint.removeAll();
                        //if there are still more endpoints in the sendList, continue
                        if(endPointIndex < sendList.size() - 1) {
                            //create new ACK List
                            List<Integer> newACKList = new CopyOnWriteArrayList<>();
                            ACKList = newACKList;
                            endPointIndex++;
                        }
                        else {
                            isFinish = true;
                            System.exit(0);
                        }
                    }

                    //if timeout, start timer and resend packets
                    int index = endpoint.getBase();
                    while(index < endpoint.getLast()+1) {
                        if(endpoint.getEvent() == GUDPEndPoint.readyEvent.TIMEOUT
                        && endpoint.getRetry() <= GUDPEndPoint.MAX_RETRY) {
                            if(endpoint.getEvent() == GUDPEndPoint.readyEvent.TIMEOUT) {
                                endpoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                            }
                            endpoint.startTimer();

                            System.out.println("[" +  endpoint.getRetry() + "] " + "Resending packets");
                            for(int i = endpoint.getBase(); i < endpoint.getNextseqnum(); i++) {
                                GUDPPacket gudpPacket = endpoint.getPacket(i);
                                DatagramPacket udpPacket = gudpPacket.pack();
                                datagramSocket.send(udpPacket);
                            }
                            endpoint.setRetry(endpoint.getRetry() + 1);
                        }
                        if(endpoint.getRetry() == GUDPEndPoint.MAX_RETRY) {
                                endpoint.setState(GUDPEndPoint.endPointState.MAXRETRIED);
                                System.out.println("Resending packets failed");
                                if(endPointIndex < sendList.size() - 1) {
                                    //create new ACK list
                                    List<Integer> newACKList = new CopyOnWriteArrayList<>();
                                    ACKList = newACKList;
                                    endPointIndex++;
                                }
                                else {
                                    isFinish = true;
                                    System.exit(0);
                                }
                        }
                        else {
                            index++;
                        }
                    }

                    if(endpoint.getBase() == endpoint.getNextseqnum()) {
                        endpoint.stopTimer();
                    }
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class ReceiveThread implements Runnable {

        boolean redo = true;
        public GUDPPacket create_ACK_packet(GUDPPacket packet) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket ACK_packet = new GUDPPacket(buffer);

            int ACK = packet.getSeqno() + 1;
            ACK_packet.setType(GUDPPacket.TYPE_ACK);
            ACK_packet.setVersion(GUDPPacket.GUDP_VERSION);
            ACK_packet.setSeqno(ACK);
            ACK_packet.setPayloadLength(0);
            ACK_packet.setSocketAddress(packet.getSocketAddress());

            DatagramPacket udpPacket = ACK_packet.pack();
            datagramSocket.send(udpPacket);

            return ACK_packet;
        }

        public void run() {
            System.out.println("Message: Receive thread is running");
            synchronized(receiveList) {
                while(redo) {
                    try {
                    byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    datagramSocket.receive(dp);
                    GUDPPacket gudpPacket = GUDPPacket.unpack(dp);

                    GUDPEndPoint receiveEndPoint = getEndPoint(receiveList, gudpPacket.getSocketAddress().getAddress()
                                                        , gudpPacket.getSocketAddress().getPort());

                    if(receiveEndPoint == null) {
                        System.out.println("Message: No receiving endpoint, creating new endpoint");
                        receiveEndPoint = new GUDPEndPoint(gudpPacket.getSocketAddress().getAddress()
                                                        , gudpPacket.getSocketAddress().getPort());

                        receiveList.add(receiveEndPoint);
                    }

                    if(gudpPacket.getType() == GUDPPacket.TYPE_BSN) {
                        System.out.println("[receiver] Received BSN packet " + gudpPacket.getSeqno());
                        GUDPPacket ACK_packet = create_ACK_packet(gudpPacket);
                        DatagramPacket udpPacket = ACK_packet.pack();
                        datagramSocket.send(udpPacket);
                        System.out.println("[receiver] Next expected packet " + ACK_packet.getSeqno());
                        receiveEndPoint.setExpectedseqnum(ACK_packet.getSeqno());
                    }
                    else if(gudpPacket.getType() == GUDPPacket.TYPE_DATA) {
                        System.out.println("[receiver] Received data packet:" + gudpPacket.getSeqno());
                        if(gudpPacket.getSeqno() == receiveEndPoint.getExpectedseqnum()) {
                            System.out.println("[receiver] Add data packet to receiveList: " + gudpPacket.getSeqno());
                            receiveEndPoint.add(gudpPacket);
                            GUDPPacket ACK_packet = create_ACK_packet(gudpPacket);
                            System.out.println("[receiver] Next expected packet: " + ACK_packet.getSeqno());
                            DatagramPacket udpPacket = ACK_packet.pack();
                            datagramSocket.send(udpPacket);
                            receiveEndPoint.setExpectedseqnum(gudpPacket.getSeqno() + 1);
                        }
                    }
                    else if(gudpPacket.getType() == GUDPPacket.TYPE_ACK) {
                        System.out.println("[sender] ACK received: " + gudpPacket.getSeqno());
                        ACKList.add(gudpPacket.getSeqno());
                    }
                    else if(gudpPacket.getType() == GUDPPacket.TYPE_FIN) {
                        if(gudpPacket.getSeqno() == receiveEndPoint.getExpectedseqnum()) {
                            System.out.println("[receiver] Received FIN packet " + gudpPacket.getSeqno());
                            GUDPPacket ACK_packet = create_ACK_packet(gudpPacket);
                            DatagramPacket udpPacket = ACK_packet.pack();
                            datagramSocket.send(udpPacket);
                            receiveEndPoint.setState(GUDPEndPoint.endPointState.FINISHED);
                            System.out.println(receiveEndPoint.getState());
                            redo = false;
                            break;
                        }
                    }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                while(receiveList.getFirst().getState() == GUDPEndPoint.endPointState.FINISHED) {
                    try {
                        System.out.println("FINISHED STATE, tell the thread to wait");
                        receiveList.notify();
                        receiveList.wait();
                    } catch (InterruptedException e) {
                        System.err.println("InterruptedException in ReceiveThread() get endpoint state");
                        e.printStackTrace();
                    }
                }
            }

        }
    }
}

