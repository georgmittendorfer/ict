package org.iota.ict;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.iota.ict.ixi.RemoteIctImplementation;
import org.iota.ict.model.Tangle;
import org.iota.ict.model.TransactionBuilder;
import org.iota.ict.network.Neighbor;
import org.iota.ict.network.event.GossipEvent;
import org.iota.ict.network.event.GossipEventDispatcher;
import org.iota.ict.network.event.GossipListener;
import org.iota.ict.network.event.GossipSubmitEvent;
import org.iota.ict.network.Receiver;
import org.iota.ict.network.Sender;
import org.iota.ict.model.Transaction;
import org.iota.ict.utils.Constants;
import org.iota.ict.utils.Properties;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.rmi.RemoteException;
import java.util.LinkedList;
import java.util.List;

/**
 * This class is the central component of the project. Each instance is an independent Ict node that can communicate with
 * other Icts. This class is not supposed to perform complex tasks but to delegate them to the correct submodule. It can
 * therefore be seen as a hub of all those components which, when working together, form an Ict node.
 */
public class Ict {
    protected final List<Neighbor> neighbors = new LinkedList<>();
    protected final Sender sender;
    protected final Receiver receiver;
    protected State state;
    protected final Tangle tangle;
    protected final Properties properties;
    protected final DatagramSocket socket;
    protected final InetSocketAddress address;
    protected final GossipEventDispatcher eventDispatcher = new GossipEventDispatcher();
    protected final RemoteIctImplementation remoteIctImplementation;
    public final static Logger LOGGER = LogManager.getLogger(Ict.class);
    protected int round = 0;

    /**
     * @param properties The properties to use for this Ict. Changing them afterwards might or might not work for some properties.
     *                   TODO allow them to be configured afterwards.
     */
    public Ict(Properties properties) {
        this.properties = properties;
        this.tangle = new Tangle(this);
        this.address = new InetSocketAddress(properties.host, properties.port);

        for (InetSocketAddress neighborAddress : properties.neighbors)
            neighbor(neighborAddress);

        try {
            this.socket = new DatagramSocket(address);
        } catch (SocketException socketException) {
            LOGGER.error("could not create socket for Ict", socketException);
            throw new RuntimeException(socketException);
        }

        this.sender = new Sender(this, properties, tangle, socket);
        this.receiver = new Receiver(this, tangle, socket);

        state = new StateRunning();
        eventDispatcher.start();
        sender.start();
        receiver.start();

        remoteIctImplementation = properties.ixiEnabled ? createRemoteIctImplementation(properties.ixis) : null;
    }

    private RemoteIctImplementation createRemoteIctImplementation(List<String> ixis) {
        try {
            RemoteIctImplementation remoteIctImplementation = new RemoteIctImplementation(this);
            for (String ixi : ixis)
                remoteIctImplementation.connectToIxi(ixi);
            return remoteIctImplementation;
        } catch (Throwable t) {
            LOGGER.error("failed to enable IXI: " + t.getMessage());
            return null;
        }
    }

    /**
     * Opens a new connection to a neighbor. Both nodes will directly gossip transactions.
     *
     * @param neighborAddress Address of neighbor to connect to.
     * @throws IllegalStateException If already has {@link Constants#MAX_NEIGHBOR_COUNT} neighbors.
     */
    public void neighbor(InetSocketAddress neighborAddress) {
        if (neighbors.size() >= Constants.MAX_NEIGHBOR_COUNT)
            throw new IllegalStateException("Already reached maximum amount of neighbors.");
        neighbors.add(new Neighbor(neighborAddress));
    }

    /**
     * Adds a listener to this object. Every {@link GossipEvent} will be passed on to the listener.
     *
     * @param gossipListener The listener to add.
     */
    public void addGossipListener(GossipListener gossipListener) {
        eventDispatcher.listeners.add(gossipListener);
    }

    /**
     * @return The address of this node. Required by other nodes to neighbor.
     */
    public InetSocketAddress getAddress() {
        return address;
    }

    /**
     * @return A list containing all neighbors. This list is a copy: manipulating it directly will have no effects.
     */
    public List<Neighbor> getNeighbors() {
        return new LinkedList<>(neighbors);
    }

    public Properties getProperties() {
        return properties;
    }

    public Tangle getTangle() {
        return tangle;
    }

    /**
     * Submits a new message to the protocol. The message will be packaged as a Transaction and sent to all neighbors.
     *
     * @param asciiMessage ASCII encoded message which will be encoded to trytes and used as transaction message.
     * @return Hash of sent transaction.
     */
    public Transaction submit(String asciiMessage) {
        TransactionBuilder builder = new TransactionBuilder();
        builder.asciiMessage(asciiMessage);
        Transaction transaction = builder.build();
        submit(transaction);
        return transaction;
    }

    /**
     * Submits a new transaction to the protocol. It will be sent to all neighbors.
     *
     * @param transaction Transaction to submit.
     */
    public void submit(Transaction transaction) {
        tangle.createTransactionLogIfAbsent(transaction);
        sender.queueTransaction(transaction);
        notifyListeners(new GossipSubmitEvent(transaction));
    }

    public void broadcast(Transaction transaction) {
        sender.queueTransaction(transaction);
    }

    public void notifyListeners(GossipEvent event) {
        eventDispatcher.notifyListeners(event);
    }

    public void request(String requestedHash) {
        sender.request(requestedHash);
    }

    /**
     * @return Whether the Ict node is currently active/running.
     */
    public boolean isRunning() {
        return state instanceof StateRunning;
    }

    public void newRound() {
        if(round % 10 == 0)
            Neighbor.logHeader();
        // two separate FOR-loops to prevent delays between newRound() calls
        for (Neighbor neighbor : neighbors)
            neighbor.newRound();
        for (Neighbor neighbor : neighbors)
            neighbor.resolveHost();
        if(properties.spamEnabled) {
            String spamHash = submit("spam transaction from node '" + properties.name + "'").hash;
            LOGGER.info("submitted spam transaction: " + spamHash);
        }
        round++;
    }

    public void terminate() {
        state.terminate();
        // TODO block until terminated
    }

    private class State {
        protected final String name;

        private State(String name) {
            this.name = name;
        }

        private void throwIllegalStateException(String actionName) {
            throw new IllegalStateException("Action '" + actionName + "' cannot be performed from state '" + name + "'.");
        }

        void terminate() {
            throwIllegalStateException("terminate");
        }
    }

    private class StateRunning extends State {
        private StateRunning() {
            super("running");
        }

        @Override
        void terminate() {
            state = new StateTerminating();
            socket.close();
            sender.terminate();
            receiver.interrupt();
            eventDispatcher.terminate();
            if (remoteIctImplementation != null)
                remoteIctImplementation.terminate();
            // TODO notify IXI modules
        }
    }

    private class StateTerminating extends State {
        private StateTerminating() {
            super("terminating");
        }
    }
}
