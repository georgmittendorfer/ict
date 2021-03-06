package org.iota.ict.network;

import org.iota.ict.Ict;
import org.iota.ict.model.Tangle;
import org.iota.ict.model.Transaction;
import org.iota.ict.network.event.GossipReceiveEvent;
import org.iota.ict.utils.Constants;
import org.iota.ict.utils.Trytes;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * This class receives transactions from neighbors. Together with the {@link Sender}, they are the two important gateways
 * for transaction gossip between Ict nodes. Each Ict instance has exactly one {@link Receiver} and one {@link Sender}
 * to communicate with its neighbors.
 *
 * @see Ict
 * @see Sender
 */
public class Receiver extends Thread {
    private final Tangle tangle;
    private final Ict ict;
    private final DatagramSocket socket;

    public Receiver(Ict ict, Tangle tangle, DatagramSocket socket) {
        super("Receiver");
        this.ict = ict;
        this.tangle = tangle;
        this.socket = socket;
    }

    @Override
    public void run() {
        while (ict.isRunning()) {

            DatagramPacket packet = new DatagramPacket(new byte[Constants.TRANSACTION_SIZE_BYTES], Constants.TRANSACTION_SIZE_BYTES);
            try {
                socket.receive(packet);
                processIncoming(packet);
            } catch (IOException e) {
                if (ict.isRunning())
                    e.printStackTrace();
            }
        }
    }

    private void processIncoming(DatagramPacket packet) {
        Neighbor sender = determineNeighborWhoSent(packet);
        if(sender == null)
            return;
        Transaction transaction;
        try {
            transaction = new Transaction(Trytes.fromBytes((packet.getData())));
        } catch (Throwable t) {
            ict.LOGGER.warn("Received invalid transaction from neighbor: " + sender.getAddress() + " (" + t.getMessage() + ")");
            sender.stats.receivedInvalid++;
            return;
        }
        sender.stats.receivedAll++;
        Tangle.TransactionLog log = tangle.findTransactionLog(transaction);
        if (log == null) {
            log = tangle.createTransactionLogIfAbsent(transaction);
            sender.stats.receivedNew++;
            log.senders.add(sender);
            ict.notifyListeners(new GossipReceiveEvent(transaction));
        }
        log.senders.add(sender);
        processRequest(transaction, sender);
    }

    private void processRequest(Transaction transaction, Neighbor requester) {
        if (transaction.requestHash.equals(Trytes.NULL_HASH))
            return; // no transaction requested
        Transaction requested = tangle.findTransactionByHash(transaction.requestHash);
        requester.stats.requested++;
        if (requested == null)
            return; // unknown transaction
        sendRequested(requested, requester);
        // unset requestHash because it's header information and does not actually belong to the transaction
        transaction.requestHash = Trytes.NULL_HASH;
    }

    private void sendRequested(Transaction requested, Neighbor requester) {
        Tangle.TransactionLog requestedLog = tangle.findTransactionLog(requested);
        requestedLog.senders.remove(requester); // remove so requester is no longer marked as already knowing this transaction
        ict.broadcast(requested);
    }

    private Neighbor determineNeighborWhoSent(DatagramPacket packet) {
        for (Neighbor nb : ict.getNeighbors())
            if (nb.getAddress().equals(packet.getSocketAddress()))
                return nb;
        Ict.LOGGER.warn("Received transaction from unknown address: " + packet.getAddress());
        return null;
    }
}
