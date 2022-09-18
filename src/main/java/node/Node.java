package node;

import node.blockchain.Block;
import node.blockchain.Transaction;
import node.blockchain.blockContainer;
import node.communication.Address;
import node.communication.Message;
import node.communication.utils.Hashing;

import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static node.communication.utils.Hashing.getBlockHash;
import static node.communication.utils.Hashing.getSHAString;
import static node.communication.utils.Utils.deepCloneHashmap;

/**
 * Node represents a peer, a cooperating member within the network
 */
public class Node  {
    private final int MAX_PEERS;
    private final int NUM_NODES;
    private final int QUORUM_SIZE;
    private final int STARTING_PORT;
    private final int MIN_CONNECTIONS;
    private final Object lock;
    private final Object quorumLock;
    private final Object memPoolLock;
    private ArrayList<Block> blockchain;
    private final Address myAddress;
    private ServerSocket ss;
    private final ArrayList<Address> localPeers;
    private ArrayList<Address> quorumPeers;
    private HashMap<String, Transaction> mempool;

    private enum status{IN_QUORUM, NOT_IN_QUORUM};
    private status nodeStatus;




    /* A collection of getters */
    public int getMaxPeers(){return this.MAX_PEERS;}
    public int getMinConnections(){return this.MIN_CONNECTIONS;}
    public Address getAddress(){return this.myAddress;}
    public ArrayList<Address> getLocalPeers(){return this.localPeers;}

    public ArrayList<Address> getQuorumPeers(){return this.quorumPeers;}
    public HashMap<String, Transaction> getMempool(){return this.mempool;}

    public status getStatus() {
        return nodeStatus;
    }

    private int quorumReadyVotes;
    private Object quorumReadyVotesLock;
    private int memPoolRounds;
    private Object memPoolRoundsLock;


    /**
     * Node constructor creates node and begins server socket to accept connections
     *
     * @param port               Port
     * @param maxPeers           Maximum amount of peer connections to maintain
     * @param initialConnections How many nodes we want to attempt to connect to on start
     * @param num_nodes
     * @param quorum_size
     * @param starting_port
     */
    public Node(int port, int maxPeers, int initialConnections, int num_nodes, int quorum_size, int starting_port) {

        /* Initialize global variables */
        lock =  new Object();
        quorumLock = new Object();
        quorumReadyVotesLock = new Object();
        memPoolRoundsLock = new Object();


        myAddress = new Address(port, "localhost");
        localPeers = new ArrayList<>();
        quorumPeers = new ArrayList<>();
        MIN_CONNECTIONS = initialConnections;
        MAX_PEERS = maxPeers;
        NUM_NODES = num_nodes;
        QUORUM_SIZE = quorum_size;
        STARTING_PORT = starting_port;
        mempool = new HashMap<>();
        memPoolLock = new Object();
        memPoolRounds = 0;
        initializeBlockchain();

        try {
            ss = new ServerSocket(port);
            Acceptor acceptor = new Acceptor(this);
            acceptor.start();
            System.out.println("node.Node up and running on port " + port + " " + InetAddress.getLocalHost());
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    /**
     * Initializes blockchain
     */
    public void initializeBlockchain(){
        blockchain = new ArrayList<Block>();
        blockchain.add(new Block(new HashMap<String, Transaction>(), "", 0));
    }

    /**
     * Adds a block
     * @param block Block to add
     */
    public void addBlock(Block block){
        Block lastBlock = blockchain.get(blockchain.size() - 1);

        /* Verify block signatures */
        // Avoiding a memory fill attack

        /* Is the block newer than our chain */
        if(block.getBlockId() > lastBlock.getBlockId()){ //

            /* Is the block ahead of our expectation */
            if(block.getBlockId() > lastBlock.getBlockId() + 1){
                // Add to memory, seek or wait for the expected block

            }else{ // It is the block we expect
                // add block
                // gossip block
            }
        }else{
            // Do not add block
        }
    }

    /**
     * Determines if a connection is eligible
     * @param address Address to verify
     * @param connectIfEligible Connect to address if it is eligible
     * @return True if eligible, otherwise false
     */
    public boolean eligibleConnection(Address address, boolean connectIfEligible){
        synchronized(lock) {
            if (localPeers.size() < MAX_PEERS - 1 && (!address.equals(this.getAddress()) && !this.containsAddress(localPeers, address))) {
                if(connectIfEligible){
                    establishConnection(address);
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Add a connection to our dynamic list of peers to speak with
     * @param address
     */
    public void establishConnection(Address address){
        localPeers.add(address);
        System.out.println("Node " + this.getAddress().getPort() + ": Added peer: " + address.getPort());
    }

    /**
     * Iterate through a list of peers and attempt to establish a mutual connection
     * with a specified amount of nodes
     * @param globalPeers
     */
    public void requestConnections(ArrayList<Address> globalPeers){
        try {
            if(globalPeers.size() > 0){
                /* Begin seeking connections */
                ClientConnection connect = new ClientConnection(this, globalPeers);
                connect.start();

                /* Begin heartbeat monitor */
                Thread.sleep(10000);
                HeartBeatMonitor heartBeatMonitor = new HeartBeatMonitor(this);
                heartBeatMonitor.start();
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns true if the provided address is in the list, otherwise false
     * @param list
     * @param address
     * @return
     */
    public boolean containsAddress(ArrayList<Address> list, Address address){
        for (Address existingAddress : list) {
            if (existingAddress.equals(address)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsTransaction(Transaction transaction){
        Set<Transaction> values = new HashSet<>();
        for(Map.Entry<String, Transaction> entry : mempool.entrySet()){
            if (entry.getValue().equals(transaction)) {
                return true;
            }
        }
        return false;
    }

    public Address removeAddress(Address address){
        for (Address existingAddress : localPeers) {
            if (existingAddress.equals(address)) {
                localPeers.remove(address);
                return address;
            }
        }
        return null;
    }



    public void gossipTransaction(Transaction transaction){
        synchronized (lock){
            for(Address address : localPeers){
                try {
                    Socket s = new Socket("localhost", address.getPort());
                    InputStream in = s.getInputStream();
                    ObjectInputStream oin = new ObjectInputStream(in);
                    OutputStream out = s.getOutputStream();
                    ObjectOutputStream oout = new ObjectOutputStream(out);
                    Message message = new Message(Message.Request.ADD_TRANSACTION, transaction);
                    oout.writeObject(message);
                    oout.flush();
                    s.close();
                } catch (IOException e) {
                    System.out.println("Received IO Exception from node " + address.getPort());
                    //removeAddress(address);
                } catch (ConcurrentModificationException e){
                    break;
                }
            }
        }
    }

    public void sendOneWayMessage(Address address, Message message) {
        try {
            Socket s = new Socket("localhost", address.getPort());
            InputStream in = s.getInputStream();
            ObjectInputStream oin = new ObjectInputStream(in);
            OutputStream out = s.getOutputStream();
            ObjectOutputStream oout = new ObjectOutputStream(out);
            oout.writeObject(message);
            oout.flush();
            s.close();
        } catch (IOException e) {
            System.out.println("Received IO Exception from node " + address.getPort());
            //removeAddress(address);
        }
    }

    public Message sendTwoWayMessage(Address address, Message message) {
        try {
            Socket s = new Socket("localhost", address.getPort());
            InputStream in = s.getInputStream();
            ObjectInputStream oin = new ObjectInputStream(in);
            OutputStream out = s.getOutputStream();
            ObjectOutputStream oout = new ObjectOutputStream(out);
            oout.writeObject(message);
            oout.flush();
            Message messageReceived = (Message) oin.readObject();
            s.close();
            return messageReceived;
        } catch (IOException e) {
            System.out.println("Received IO Exception from node " + address.getPort());
            //removeAddress(address);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return null;
    }








    public void addTransaction(Transaction transaction){
        synchronized (memPoolLock){
            if(!containsTransaction(transaction)){
                try {
                    mempool.put(getSHAString(transaction.getData()), transaction);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
                gossipTransaction(transaction);
                System.out.println("Node " + myAddress.getPort() + ": Node " + localPeers.get(0).getPort() + " mempool: " + mempool.values());

                if(mempool.size() == 3){
                    if(inQuorum()){
                        System.out.println("node " + myAddress.getPort() + ": In quorum");
                        //sendQuorumReady();
                    }
                }
            }
        }
    }

    public void sendQuorumReady(){
        sendOneWayMessageQuorum(new Message(Message.Request.QUORUM_READY));
    }

    public void receiveQuorumReady(){
        synchronized (quorumReadyVotesLock){
            quorumReadyVotes++;

            if(quorumReadyVotes == quorumPeers.size() - 1){
                sendMempoolHashes();
            }
        }
    }

    public void sendMempoolHashes() {

        Set<String> keys = mempool.keySet();
        ArrayList<Address> quorum = deriveQuorum(blockchain.get(blockchain.size() - 1), 0);

        for (Address quorumAddress : quorum) {
            if (!myAddress.equals(quorumAddress)) {
                Socket s = null;
                try {
                    s = new Socket("localhost", quorumAddress.getPort());
                    InputStream in = s.getInputStream();
                    ObjectInputStream oin = new ObjectInputStream(in);
                    OutputStream out = s.getOutputStream();
                    ObjectOutputStream oout = new ObjectOutputStream(out);
                    oout.writeObject(new Message(Message.Request.RECEIVE_MEMPOOL, keys));
                    oout.flush();
                    Message messageReceived = (Message) oin.readObject();
                    if(messageReceived.getRequest().name().equals("REQUEST_TRANSACTION")){
                        ArrayList<String> hashesRequested = (ArrayList<String>) messageReceived.getMetadata();
                        ArrayList<Transaction> transactionsToSend = new ArrayList<>();
                        for(String hash : keys){
                            if(mempool.containsKey(hash)){
                                transactionsToSend.add(mempool.get(hash));
                            }else{
                                s.close();
                                throw new Exception();
                                // something is wrong
                            }
                        }
                        oout.writeObject(new Message(Message.Request.RECEIVE_MEMPOOL, transactionsToSend));
                    }else{
                    }
                    s.close();
                } catch (IOException e) {
                    //throw new RuntimeException(e);
                } catch (ClassNotFoundException e) {
                    //throw new RuntimeException(e);
                } catch (Exception e){

                }
            }
        }
    }

    public void receiveMempool(Set<String> keys, ObjectOutputStream oout, ObjectInputStream oin) {
        synchronized (memPoolLock) {
            ArrayList<String> keysAbsent = new ArrayList<>();
            for (String key : keys) {
                if (!mempool.containsKey(key)) {
                    keysAbsent.add(key);
                }
            }
            try {
                if (keysAbsent.isEmpty()) {
                    oout.writeObject(new Message(Message.Request.PING));
                    oout.flush();
                } else {
                    oout.writeObject(new Message(Message.Request.REQUEST_TRANSACTION, keysAbsent));
                    oout.flush();
                    ArrayList<Transaction> transactionsReturned = (ArrayList<Transaction>) oin.readObject();
                    for(Transaction transaction : transactionsReturned){
                        try {
                            mempool.put(getSHAString(transaction.getData()), transaction);
                        } catch (NoSuchAlgorithmException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            memPoolRounds++;
            if(memPoolRounds == quorumPeers.size() - 1){
                constructBlock();
            }
        }
    }

    public void constructBlock(){
        synchronized (memPoolLock){
            HashMap<String, Transaction> blockTransactions = deepCloneHashmap(mempool);
            try {
                Block block = new Block(blockTransactions,
                        getBlockHash(blockchain.get(blockchain.size() - 1), 0),
                                blockchain.size());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void gossipBlock(Block block){
        sendOneWayMessageQuorum(new Message(Message.Request.VOTE_BLOCK, new blockContainer(block)));
    }






































    public void shareMempool(){
        // send mempool to each node in quorum
        // expect to receive all members in quorum's mempool
        ArrayList<Address> quorum = deriveQuorum(blockchain.get(blockchain.size() - 1), 0);

        for (Address quorumAddress : quorum) {
            if (!myAddress.equals(quorumAddress)) {
                sendOneWayMessage(quorumAddress, new Message(Message.Request.RECEIVE_MEMPOOL, getMempool()));
            }
        }
    }

//    public void receiveMempool(ArrayList<Transaction> newMempool){
//        synchronized (memPoolLock){
//            if(!inQuorum() || memPoolRounds >= QUORUM_SIZE - 1){
//                return;
//            }
//
//            memPoolRounds++;
//            // add any unknown transactions
//            // share mempool with quorum
//            for (Transaction transaction : newMempool){
//                if(!containsTransaction(mempool, transaction)){ //if theres a tran in their list that is not in mine
//                    mempool.add(transaction);
//                }
//            }
//
//            for (Transaction transaction : mempool){
//                if(!containsTransaction(newMempool, transaction)){ //if theres a tran in my list that is not in theirs
//                    shareMempool();
//                }
//            }
//
//            // do x rounds of communication
//            // construct block
//            if(memPoolRounds == QUORUM_SIZE - 1){
//                constructBlock();
//            }
//        }
//    }


    public void beginElection(){
        // share block signed,
        // receive blocks and sign
        // gossip block
    }



    public void sendOneWayMessageQuorum(Message message){
        ArrayList<Address> quorum = deriveQuorum(blockchain.get(blockchain.size() - 1), 0);
        for(Address quorumAddress : quorum){
            if(!myAddress.equals(quorumAddress)) {
                sendOneWayMessage(quorumAddress, message);
            }
        }
    }

    public boolean inQuorum(){
        synchronized (quorumLock){
            ArrayList<Address> quorum = deriveQuorum(blockchain.get(blockchain.size() - 1), 0);
            Boolean quorumMember = false;
            for(Address quorumAddress : quorum){
                if(myAddress.equals(quorumAddress)) {
                    quorumMember = true;
                }
            }
            return quorumMember;
        }
    }

    public void establishQuorumPeers(){
        ArrayList<Address> quorum = deriveQuorum(blockchain.get(blockchain.size() - 1), 0);
        for(Address quorumAddress : quorum){
            if(!myAddress.equals(quorumAddress)) {
                if(!containsAddress(quorumPeers, quorumAddress)){
                    quorumPeers.add(quorumAddress);
                }
            }
        }
    }

    // 8000 8086 8944
    public ArrayList<Address> deriveQuorum(Block block, int nonce){
        String blockHash;
        if(block != null && block.getPrevBlockHash() != null){
            try {
                ArrayList<Address> quorum = new ArrayList<>();
                blockHash = Hashing.getBlockHash(block, nonce);
                BigInteger bigInt = new BigInteger(blockHash, 16);
                bigInt = bigInt.mod(BigInteger.valueOf(NUM_NODES));
                int seed = bigInt.intValue();
                Random random = new Random(seed);
                for(int i = 0; i < QUORUM_SIZE; i++){
                    int port = STARTING_PORT + random.nextInt(NUM_NODES);
                    quorum.add(new Address(port, "localhost"));
                }
                return quorum;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }



    /**
     * Acceptor is a thread responsible for maintaining the server socket by
     * accepting incoming connection requests, and starting a new ServerConnection
     * thread for each request. Requests terminate in a finite amount of steps, so
     * threads return upon completion.
     */
    class Acceptor extends Thread {
        Node node;

        Acceptor(Node node){
            this.node = node;
        }

        public void run() {
            Socket client;
            while (true) {
                try {
                    client = ss.accept();
                    new ServerConnection(client, node).start();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * HeartBeatMonitor is a thread which will periodically 'ping' nodes which this node is connected to.
     * It expects a 'ping' back. Upon receiving the expected reply the other node is deemed healthy.
     *
     */
    class HeartBeatMonitor extends Thread {
        Node node;

        HeartBeatMonitor(Node node){
            this.node = node;
        }

        public void run() {
            while (true) {
                for(Address address : localPeers){
                    try {
                        Thread.sleep(30000);
                        Socket s = new Socket("localhost", address.getPort());
                        InputStream in = s.getInputStream();
                        ObjectInputStream oin = new ObjectInputStream(in);
                        OutputStream out = s.getOutputStream();
                        ObjectOutputStream oout = new ObjectOutputStream(out);
                        Message message = new Message(Message.Request.PING);
                        oout.writeObject(message);
                        oout.flush();
                        Message messageReceived = (Message) oin.readObject();
//                        if(messageReceived.getRequest().name().equals("PING")){
//                            System.out.println("Node " + node.getAddress().getPort() + ": Node " + localPeers.get(0).getPort() + " pinged back");
//                        }else{
//                            System.out.println("Node " + node.getAddress().getPort() + ": Node " + localPeers.get(0).getPort() + " idk :(");
//                        }
                        s.close();
                        //System.out.println("Node " + node.getAddress().getPort() + ": Node " + localPeers.get(0).getPort() + " mempool: " + mempool.values());
                    } catch (IOException e) {
                        System.out.println("Received IO Exception from node " + address.getPort());
                        //removeAddress(address);
                        System.out.println("Removing address");
                        System.out.println(e);
                        break;
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        System.out.println("Received Interrupted Exception from node " + address.getPort());
                        throw new RuntimeException(e);
                    } catch (ConcurrentModificationException e){
                        break;
                    }
                }
            }
        }
    }
}
