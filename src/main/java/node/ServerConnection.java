package node;

import node.blockchain.Block;
import node.blockchain.BlockContainer;
import node.blockchain.Transaction;
import node.communication.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic thread which implements the nodes protocol
 */
public class ServerConnection extends Thread {
    private final Socket client;
    private final Node node;

    ServerConnection(Socket client, Node node) throws SocketException {
        this.client = client;
        this.node = node;
        setPriority(NORM_PRIORITY - 1);
    }

    public void run() {
        try {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();
            ObjectOutputStream oout = new ObjectOutputStream(out);
            ObjectInputStream oin = new ObjectInputStream(in);
            Message incomingMessage = (Message) oin.readObject();
            handleRequest(incomingMessage, oout, oin);
            client.close();
        } catch (IOException e) {
            System.out.println("I/O error " + e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void handleRequest(Message incomingMessage, ObjectOutputStream oout, ObjectInputStream oin) throws IOException {
        Message outgoingMessage;
        switch(incomingMessage.getRequest()){
            case REQUEST_CONNECTION:
                Address address = (Address) incomingMessage.getMetadata();
                if (node.eligibleConnection(address, true)) {
                    outgoingMessage = new Message(Message.Request.ACCEPT_CONNECTION, node.getAddress());
                    oout.writeObject(outgoingMessage);
                    oout.flush();
                    return;
                }
                outgoingMessage = new Message(Message.Request.REJECT_CONNECTION, node.getAddress());
                oout.writeObject(outgoingMessage);
                oout.flush();
                break;
            case QUERY_PEERS:
                System.out.println("Node " + node.getAddress().getPort() + ": Received: Query request.");
                outgoingMessage = new Message(node.getLocalPeers());
                oout.writeObject(outgoingMessage);
                oout.flush();
                break;
            case REQUEST_BLOCK:
            case ADD_BLOCK:
                Block proposedBlock = (Block) incomingMessage.getMetadata();
                node.addBlock(proposedBlock);
            case PING:
                //System.out.println("Node " + node.getAddress().getPort() + ": Received: Ping.");
                outgoingMessage = new Message(Message.Request.PING);
                oout.writeObject(outgoingMessage);
                oout.flush();
                break;
            case REQUEST_QUORUM_CONNECTION:
                break;
            case ADD_TRANSACTION:
                Transaction transaction = (Transaction) incomingMessage.getMetadata();
                node.addTransaction(transaction);
                break;
            case RECEIVE_MEMPOOL:
                Set<String> memPoolHashes = (HashSet<String>) incomingMessage.getMetadata();
                node.receiveMempool(memPoolHashes, oout, oin);
                break;
            case QUORUM_READY:
                node.receiveQuorumReady();
                break;
            case VOTE_BLOCK:
                BlockContainer blockContainer = (BlockContainer) incomingMessage.getMetadata();
                node.receiveBlockForVoting(blockContainer);
                break;
        }
    }
}