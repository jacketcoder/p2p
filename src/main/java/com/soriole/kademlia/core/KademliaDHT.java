package com.soriole.kademlia.core;

import com.soriole.kademlia.core.messages.*;
import com.soriole.kademlia.core.store.*;
import com.soriole.kademlia.core.util.NodeInfoComparatorByDistance;
import com.soriole.kademlia.network.KademliaMessageServer;
import com.soriole.kademlia.network.receivers.MessageReceiver;
import com.soriole.kademlia.network.ServerShutdownException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class KademliaDHT implements KadProtocol<byte[]> {
    private static final Logger LOGGER = LoggerFactory.getLogger(KademliaDHT.class);
    public final ContactBucket bucket;
    public final KademliaMessageServer server;
    // KeyValueStore with 24 hours expiration time.
    public static final long defaultExpirationTime = 1000 * 60 * 60;
    public final KeyValueStore<byte[]> keyValueStore;

    public KademliaDHT(ContactBucket bucket, KademliaMessageServer server, KeyValueStore<byte[]> store) {
        this.bucket = bucket;
        this.server = server;
        this.keyValueStore = store;
    }

    /**
     * The FIND_NODE RPC includes a 160-bit key. The recipient of the RPC returns up to k triples (IP address, port, nodeID) for the contacts that it knows to be closest to the key.
     * <p>
     * The recipient must return k triples if at all possible. It may only return fewer than k if it is returning all of the contacts that it has knowledge of.
     * <p>
     * This is a primitive operation, not an iterative one used internally for other operations
     */
    // a blocking method.
    protected SortedSet<NodeInfo> findClosestNodes(Key key, NodeInfo info) throws TimeoutException, ServerShutdownException {

        Message m = server.startQuery(info, new NodeLookupMessage(bucket.getLocalNode().getKey()));
        SortedSet set= new TreeSet<NodeInfo>(new NodeInfoComparatorByDistance(key));
        set.addAll(((NodeListMessage)m).nodes);
        return set;
    }

    /**
     *
     * @param key
     * @return Returns Array containing two sets.
     *  {
     *      array[0] = set_of_closest_nodes,
     *      array[1] = set of nodes, we encountered, but didn't contact as they were far from key.
     *  }
     * @throws ServerShutdownException
     */
    private SortedSet<NodeInfo>[] internalFindClosestNodes(Key key, int count) throws ServerShutdownException {
        // get the closest nodes for the given key from our bucket.
        // Note that the set returned by the bucket is a BoundedSortedSet,
        // we can put as many nodes as we want but it will retain only k+1 closest nodes.
        final SortedSet<NodeInfo> closestNodes = bucket.getClosestNodes(key, count);

        // list of all the nodes found during the process.
        final SortedSet<NodeInfo> allFoundNodes= new TreeSet<>();

        // keep a list of nodes that we have send queries.
        final SortedSet<NodeInfo> queriedNodes = new TreeSet<>();

        final SortedSet<NodeInfo> nodesToQuery = new TreeSet<>(closestNodes);

        // we will have to query all the nodes we have found.
        while (!queriedNodes.containsAll(nodesToQuery)) {
            // remove all the queried nodes from the closestNodes list.
            nodesToQuery.removeAll(queriedNodes);

            // create a message Receiver to store incoming messages
            // the receiver's done() function will return true when it receives the given no of messages.
            NodeListReceiver receiver = new NodeListReceiver(nodesToQuery.size());

            // send anynchronous request to each nodes in the list.
            for (NodeInfo info : nodesToQuery) {
                server.startQueryAsync(info, new NodeLookupMessage(key), receiver);
            }
            // wiat for all the replies.
            synchronized (receiver) {
                while (!receiver.done()) {
                    try {
                        receiver.wait();
                    } catch (InterruptedException e) {
                        //ignore.
                    }
                }
            }
            // add all the nodes to the queried nodes list.
            queriedNodes.addAll(nodesToQuery);

            // add all the founded nodes into the closest nodes.
            closestNodes.addAll(receiver.foundNodes());

            allFoundNodes.addAll(receiver.foundNodes());
            // if we have found the looked key, we can break the loop.
            if (closestNodes.first().getKey().equals(key)) {
               break;

            }

            // we need another iteration to find closer nodes.
            nodesToQuery.addAll(closestNodes);
        }
        SortedSet<NodeInfo>info[]=new SortedSet[2];
        info[0]=closestNodes;
        allFoundNodes.removeAll(queriedNodes);
        info[1]=allFoundNodes;
        return info;
    }

    private SortedSet<NodeInfo>[] internalFindClosestNodes(Key key) throws ServerShutdownException {
        return internalFindClosestNodes(key, bucket.k);
    }

    @Override
    public SortedSet<NodeInfo> findClosestNodes(Key key) throws ServerShutdownException {
        // return the closest nodes.
        return internalFindClosestNodes(key)[0];
    }

    @Override
    public int store(Key key, byte[] value) throws  ServerShutdownException {
            return store(key,value,bucket.k);
    }

    public int store(Key key, byte[] value, int redundancy) throws ServerShutdownException {

        Collection<NodeInfo> nodes = internalFindClosestNodes(key, redundancy)[0];
        LOGGER.debug("Store("+key+") storing in these nodes :"+nodes);
        DataMessage message = new DataMessage();
        message.expirationTime = new Date().getTime() + defaultExpirationTime;
        message.key = key;
        message.value = value;
        int successes = 0;
        // looks like it's blocked, but the results are being collected asynchronously.
        for (Message m : server.startAsyncQueryAll(nodes, message)) {
            if (m instanceof DataReplyMessage) {
                DataReplyMessage msg = (DataReplyMessage) m;
                if (msg.success) {
                    successes++;
                }
            }
        }
        return successes;

    }

    @Override
    public TimeStampedData<byte[]> findValue(Key key) throws NoSuchElementException,ServerShutdownException {
        LOGGER.debug("findValue("+key+")");
        // the lists to track remaining nodes to query and queried nodes.
        Collection<NodeInfo> closestNodes = bucket.getClosestNodes(key);
        Collection<NodeInfo> queriedNodes = new HashSet<>();
        Collection<NodeInfo> nodesToQuery=new TreeSet<>(closestNodes);
        // crate a query message. [query message is a Data message with value=null]
        LookupMessage message = new LookupMessage();
        message.lookupKey = key;

        // a sorted set to store all the returned data message in ascending order of updateTime.
        SortedSet<DataMessage> dataMessages = new TreeSet<>(DataMessage.getComparatorByUpdateTime());

        // while we have not queried the closest nodes, send messages.
        while (!nodesToQuery.isEmpty()) {
            queriedNodes.addAll(nodesToQuery);
            // send server with a lookup message
            LOGGER.debug("findValue : Asking "+nodesToQuery);

            for (Message m : server.startAsyncQueryAll(nodesToQuery, message)) {
                // if replied message is a NodeListMessage, we update our nodesToQuery list.
                if (m instanceof NodeListMessage) {
                    LOGGER.debug("findValue : "+m.mSrcNodeInfo+" sent "+((NodeListMessage) m).nodes);
                    closestNodes.addAll(((NodeListMessage) m).nodes);
                }
                // hurrey, the node has the key.
                else if (m instanceof DataMessage) {
                    LOGGER.debug("findValue : "+m.mSrcNodeInfo+" sent us DataMessage");
                    dataMessages.add((DataMessage) m);
                }
                else{
                    LOGGER.warn("Got invalid messageType as reply for LookupMessage");
                }
            }
            nodesToQuery.clear();
            nodesToQuery.addAll(closestNodes);
            nodesToQuery.removeAll(queriedNodes);
        }
        // return the most latest value.
        TimeStampedData<byte[]> local=keyValueStore.get(key);
        if(local!=null) {
            if(!dataMessages.isEmpty() &&
                local.getInsertionTime() > dataMessages.last().updatedtime    ){
                 return dataMessages.last().toTimeStampedData();
            }
            return local;
        }
        return dataMessages.last().toTimeStampedData();

    }

    // another blocking method that waits until the server returns.
    @Override
    public long ping(NodeInfo node) {
        Date start = new Date();
        try {
            PingMessage m1 = new PingMessage();
            PongMessage m2 = (PongMessage) server.startQuery(node, m1);
            if (Arrays.equals(m1.writeToBytes(), m2.rawBytes)) {
                // return time in milliseconds.
                return new Date().getTime() - start.getTime();
            }

        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (ServerShutdownException e) {
            e.printStackTrace();
        }
        // we can remove this node from the DHT.
        bucket.removeNode(node.getKey());
        return -1;
    }

    @Override
    // returns Node info if the node is found else returns null.
    public NodeInfo findNode(Key key) throws ServerShutdownException {
        // get node from our bucket.
        NodeInfo info = bucket.getNode(key);

        if (info != null) {
            // if our bucket already has the nodeinfo, ping the node.
            if (ping(info) >= 0) {
                return info;
            }
            // if the ping was unsuccessful, well, we will say that we don't have the contact.
            return null;
        }
        // if we don't have the contact, we need to use the find closest nodes algorithm.
        SortedSet<NodeInfo> closestNodes = findClosestNodes(key);
        if (closestNodes.contains(new NodeInfo(key))) {
            return closestNodes.first();
        }
        return null;
    }

    @Override
    public void refresh() {

    }

    /**
     * we connect with the bootstrap node using join method.
     * This method blocks until the bootstrap node replies us.
     *
     * @param bootstrapNode : nodeInfo of bootstrap server.
     * @return true if bootstrap node reples; false otherwise.
     *
     */
    @Override
    public boolean join(NodeInfo bootstrapNode) {

        try {
            // first ping the server. If the server replies, it will be added to bucket automatically.
            long pingTime=ping(bootstrapNode);
            if(pingTime<0){
                return false;
            }
            LOGGER.info("BootstrapNode replied Ping in "+String.valueOf(pingTime)+"ms");

            //we can use the findClosestNodes with our won key to fill our buckets.
            //This trick works because none of the nodes will reply us with our own info.
            // Thus findClosestNodes algorithm will find all the nodes near to our key, but won't find our
            SortedSet<NodeInfo>[] nodes = internalFindClosestNodes(bucket.getLocalNode().getKey());

            // we only take those nodes which were not contacted during the addition process.
            SortedSet<NodeInfo> nodesNotContacted=nodes[1];
            for(NodeInfo info:nodesNotContacted){
                // ping each of the nodes we have not contacted yet.
                // ignore the pong reply.
                // this won't wait for the server to send the reply.
                server.sendMessage(info,new PingMessage());
            }
        } catch (ServerShutdownException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    @Override
    public void join(InetSocketAddress address) {
        join(new NodeInfo(null, address));
    }

    public static KademliaDHT getDefaultInstance(Key key,int port) throws SocketException {
        NodeInfo localKey=new NodeInfo(key);
        ContactBucket bucket=new ContactBucket(localKey,160,3);
        KademliaMessageServer server=new KademliaMessageServer(port,bucket);
        bucket.getLocalNode().setLanAddress(server.getSocketAddress());
        return new KademliaDHT(bucket,server,new KeyValueStore<>(defaultExpirationTime));



    }

}


class NodeListReceiver implements MessageReceiver {
    static private Logger LOGGER = LoggerFactory.getLogger(NodeListReceiver.class);
    private SortedSet<NodeInfo> foundNodes = new TreeSet<>();
    private int counter;

    NodeListReceiver(int count) {
        this.counter = count;
    }

    @Override
    synchronized public void onReceive(Message message) {
        try {
            foundNodes.addAll(((NodeListMessage) message).nodes);
            LOGGER.info(message.mSrcNodeInfo.toString()+" returned "+((NodeListMessage)message).nodes);
            notify();

        } catch (Exception e) {
            LOGGER.info("findClosestnodes() : error on receive");
        }
        counter--;
    }

    @Override
    synchronized public void onTimeout() {
        LOGGER.info("OnTimeout()");

        counter--;
        notify();
    }

    public boolean done() {
        return counter <= 0;

    }

    public SortedSet<NodeInfo> foundNodes() {
        return foundNodes;
    }
}