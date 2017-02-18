import java.io.*;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by rittick on 2/14/17.
 */
public class Peer {
    // After reading the config file, these fields will be initialized.
    private static String IP;
    private static Integer PORT;
    private static Integer NUMBER_OF_PEERS;

    //Stream to read user input.
    private static BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));

    //Config file data is stored here.
    private static ConcurrentHashMap<Integer, String> configFileData;

    //Peer ID to Neighbors List
    private static ArrayList<Integer> peerNeighbors = new ArrayList<>();

    //Peer ID to IP & PORT
    private static ConcurrentHashMap<Integer, String> peerIdtoIPAndPort = new ConcurrentHashMap<>();

    //Peer ID and sequence number of Message ID
    private static String peerID;
    private static Integer sequenceNumber = 0;

    // List of files of this peer.
    private static ArrayList<String> myFiles = new ArrayList<>();

    //Neighbors to Socket Mappings
    private static ConcurrentHashMap<Integer, Socket> sockets = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        //--------------Getting Peer ID-----------------------------------------
        System.out.println("Enter Peer ID number: (1,2,3,4) ");
        try {
            peerID = userInput.readLine();
        } catch (IOException e) {
            System.out.println("Exception while getting peer ID from user.");
        }

        //-----Getting IP Address, Port, Number of peers and Neighbors of each peer.
        configFileData = ReadConfigFile.readFile("network_linear.config");

        for (Integer key : configFileData.keySet()) {
            if (key == Integer.parseInt(peerID)) {
                // GET NEIGHBORS
                String[] split = configFileData.get(key).split(":");
                String[] neighbors = split[2].split("-");
                for (int i = 0; i < neighbors.length; i++) {
                    peerNeighbors.add(Integer.parseInt(neighbors[i]));
                }

                //GET IP AND PORT
                IP = split[0];
                PORT = Integer.parseInt(split[1]);
            }
        }

        NUMBER_OF_PEERS = configFileData.keySet().size();

        //---------Peer ID to IP & Port---------
        for (Integer key : configFileData.keySet()) {
            String[] split = configFileData.get(key).split(":");
            peerIdtoIPAndPort.put(key, split[0] + ":" + split[1]);
        }

        //-------Preparing list of files belonging to this peer--------------
        myFiles = getFileData("Node" + peerID + "/Myfiles");

        //----------Starting Peer Server---------------------------
        try {
            ServerSocket peerServer = new ServerSocket(PORT);
            System.out.println("Peer Server is listening...");

            //-------Starting Peer Client--------------------------
            new Client(peerID, IP, PORT, NUMBER_OF_PEERS, peerNeighbors, peerIdtoIPAndPort).start();
            System.out.println("Peer Client is running...");

            while (true) {
                Socket newConnection = peerServer.accept();
                new Server(peerID, IP, PORT, NUMBER_OF_PEERS, peerNeighbors, newConnection, peerIdtoIPAndPort).start();
            }
        } catch (IOException e) {
            System.out.println("IOException in main method.");
        }
    }

    private static ArrayList<String> getFileData(String path) {
        ArrayList<String> fileNames = new ArrayList<>();

        File file = new File(path);
        String[] files = file.list();
        for (int i = 0; i < files.length; i++) {
            fileNames.add(files[i]);
        }

        return fileNames;
    }

    private static boolean searchPeerFiles(String fileName) {
        boolean flag = false;

        for (int i = 0; i < myFiles.size(); i++) {
            if (myFiles.get(i).equals(fileName)) {
                flag = true;
                return flag;
            }
        }

        return flag;
    }

    private static class Server extends Thread {
        private String ID_SERVER;
        private String IP_SERVER;
        private Integer PORT_SERVER;
        private Integer numberOfPeers_SERVER;
        private ArrayList<Integer> myNeighbors_SERVER;
        private Socket connection;
        private ConcurrentHashMap<Integer, String> peerIdToIPAndPort_SERVER;
        private ConcurrentHashMap<MessageID, UpstreamPeerID> seenQueries = new ConcurrentHashMap<>();


        public Server(String peerID, String ip, Integer port, Integer numberOfPeers, ArrayList<Integer> peerNeighbors, Socket newConnection, ConcurrentHashMap<Integer, String> peerIdtoIPAndPort) {
            ID_SERVER = peerID;
            IP_SERVER = ip;
            PORT_SERVER = port;
            numberOfPeers_SERVER = numberOfPeers;
            myNeighbors_SERVER = peerNeighbors;
            connection = newConnection;
            peerIdToIPAndPort_SERVER = peerIdtoIPAndPort;
        }

        public void run() {
            System.out.println("In run() of server.");

            ObjectOutputStream outputStream;
            ObjectInputStream inputStream;

            try {
                inputStream = new ObjectInputStream(connection.getInputStream()); //<----same OOS and OIS for different Sockets
                outputStream = new ObjectOutputStream(connection.getOutputStream());
                Iterator<Integer> neighborsIterator = myNeighbors_SERVER.iterator();
                //sockets = new Socket[myNeighbors_SERVER.size()];

                /*Query queryObj = (Query)*/

                Object o = inputStream.readObject();

                Query queryObj = null;
                if (o instanceof Query) {
                    queryObj = (Query) o;
                    System.out.println(queryObj.getType());


                    //Add This Peer ID to Forward Path
                    queryObj.setForwardPath(Integer.parseInt(ID_SERVER));

                    //Decrease TTL by 1
                    queryObj.setTTL();
                    System.out.println(queryObj.getTTL());

                    if (queryObj.getType().equals("query")) {
                        boolean searchResult = searchPeerFiles(queryObj.getFileName());
                        if (searchResult) {
                            //Create a QueryHit Object
                            System.out.println("Peer "+ID_SERVER+" Contains File.");
                            QueryHit queryHit = createQueryHitObject(queryObj);
                            sendReply(outputStream, queryHit);
                        } else {
                            outputStream.writeObject("Peer"+ID_SERVER+" Does Not Contain File.");
                            outputStream.flush();
                            System.out.println("Peer does not contain file.");
                        }

                        // Regardless of Hit or Miss, Forward Query to Neighbor if TTL > 0
                        if (queryObj.getTTL() > 0) {

                            String peerID = queryObj.getMessageID().getPeerID();
                            Integer seqNum = queryObj.getMessageID().getSequenceNumber();
                            Boolean msgSeen = checkIFMsgSeen(peerID, seqNum, seenQueries);


                            if (!msgSeen) {
                                while (neighborsIterator.hasNext()) {
                                    Integer nextNeighbor = neighborsIterator.next();
                                    String ip = peerIdtoIPAndPort.get(nextNeighbor).split(":")[0];
                                    int port = Integer.parseInt(peerIdtoIPAndPort.get(nextNeighbor).split(":")[1]);

                                    seenQueries.put(new MessageID(queryObj.getMessageID().getPeerID(),
                                                    queryObj.getMessageID().getSequenceNumber()),
                                            new UpstreamPeerID(ip, port));
                                    System.out.println("CHECKPOINT");

                                    sockets.put(nextNeighbor, new Socket(ip, port));

                                    if (!queryObj.getForwardPath().contains(nextNeighbor)) {

                                        outputStream = new ObjectOutputStream(sockets.get(nextNeighbor).getOutputStream()); // Exception, CHECK
                                        outputStream.flush();
                                        outputStream.writeObject(queryObj);
                                        outputStream.flush();

                                        //----READING RESPONSE (QUERYHIT)
                                        inputStream = new ObjectInputStream(sockets.get(nextNeighbor).getInputStream()); // CHECk HERE ARRAY INDEX OUT OF BOUND
                                        QueryHit queryHit = (QueryHit) inputStream.readObject();

                                        if (queryHit.getMessageID().getPeerID() != ID_SERVER) {
                                            Integer backwardPath = queryHit.getBackwardPath();
                                            Socket socket = sockets.get(backwardPath);
                                            outputStream = new ObjectOutputStream(socket.getOutputStream());
                                            outputStream.flush();
                                            outputStream.writeObject(queryHit);
                                            outputStream.flush();
                                        }

                                        System.out.println("File Found. IP: " + queryHit.getPeerIP() + " PORT: " + queryHit.getPort());

                                    }
                                }
                            }

                        }
                    }
                }

                QueryHit queryHitObj; // Working...
                if (o instanceof QueryHit) {
                    queryHitObj = (QueryHit) o;
                    System.out.println(queryHitObj.getType());
                    System.out.println("File Found @ IP: " + queryHitObj.getPeerIP() + " PORT: " + queryHitObj.getPort());

                    //Send Backward to Requesting Peer
                    if (queryHitObj.getMessageID().getPeerID() != ID_SERVER) {
                        Integer backwardPath = queryHitObj.getBackwardPath();
                        Socket socket = sockets.get(backwardPath);
                        outputStream = new ObjectOutputStream(socket.getOutputStream());
                        outputStream.flush();
                        outputStream.writeObject(queryHitObj);
                        outputStream.flush();
                    }
                }

                if(o instanceof String){
                    String message = (String) o;
                    System.out.println(message);
                }

            } catch (Exception e) {
                System.out.println("Exception Server");
            } finally {

            }


        }

        private static void sendReply(ObjectOutputStream outputStream, QueryHit queryHit) throws IOException {
            outputStream.writeObject(queryHit);
            outputStream.flush();
        }

        private Boolean checkIFMsgSeen(String peerID, Integer seqNum, ConcurrentHashMap<MessageID, UpstreamPeerID> seenQueries) {
            boolean flagForPeer = false;
            boolean flagForSeqNum = false;

            for(MessageID messageID : seenQueries.keySet()){
                String peerID1 = messageID.getPeerID();
                Integer sequenceNumber = messageID.getSequenceNumber();

                if(peerID1 == peerID){ flagForPeer = true; }
                if(sequenceNumber == seqNum){ flagForSeqNum = true; }
            }

            if(flagForPeer && flagForSeqNum){
                return true;
            }else{
                return false;
            }
        }

        private QueryHit createQueryHitObject(Query queryObj) {
            QueryHit queryHit;

            queryHit = new QueryHit(queryObj.getMessageID(), 3/*queryObj.getTTL()*/, queryObj.getFileName(), IP, PORT, queryObj.getForwardPath());
            System.out.println("Forward Path"+queryObj.getForwardPath());
            return queryHit;
        }
    }

    private static class Client extends Thread {
        //----------------------Basic Information for Client
        private String ID_CLIENT;
        private String IP_CLIENT;
        private Integer PORT_CLIENT;
        private Integer numberOfPeers_CLIENT;
        private ArrayList<Integer> myNeighbors_CLIENT;
        private ConcurrentHashMap<Integer, Socket> peerIdToSocket = new ConcurrentHashMap<>();
        private ConcurrentHashMap<Integer, String> peerIdToIPAndPort_CLIENT;

        //----------------------Variables to Communicate
        Socket[] sockets = null;//new Socket[myNeighbors_CLIENT.size()];
        BufferedReader keyboardInput = null;
        ObjectInputStream inputStream = null;
        ObjectOutputStream outputStream = null;
        Query query = null;
        QueryHit queryHit = null;

        //----------Variable for filename to search
        private String fileName = null;

        public Client(String peerID, String ip, Integer port, Integer numberOfPeers, ArrayList<Integer> peerNeighbors, ConcurrentHashMap<Integer, String> peerIdtoIPAndPort) {
            ID_CLIENT = peerID;
            IP_CLIENT = ip;
            PORT_CLIENT = port;
            numberOfPeers_CLIENT = numberOfPeers;
            myNeighbors_CLIENT = peerNeighbors;
            peerIdToIPAndPort_CLIENT = peerIdtoIPAndPort;
        }

        public void run() {
            sockets = new Socket[myNeighbors_CLIENT.size()];
            keyboardInput = new BufferedReader(new InputStreamReader(System.in));


            System.out.println("In run() of client.");
            System.out.println("Enter Name of File to Search:");
            try {
                fileName = keyboardInput.readLine();
            } catch (IOException e) {
                System.out.println("IOException while user entering filename to search.");
            }

            Query query = new Query(new MessageID(peerID, ++sequenceNumber), 2, fileName, Integer.parseInt(peerID));
            //showQueryData(query);

            try {
                for(int i=0; i<sockets.length; i++){
                    String IPAndPort = peerIdToIPAndPort_CLIENT.get(myNeighbors_CLIENT.get(i));
                    String ip = IPAndPort.split(":")[0];
                    int port = Integer.parseInt(IPAndPort.split(":")[1]);
                    sockets[i] = new Socket(ip, port);
                }

                //sockets[0] = new Socket("127.0.0.1", 3003);

                if (sockets[0] != null) {
                    outputStream = new ObjectOutputStream(sockets[0].getOutputStream());
                    outputStream.flush();
                    outputStream.writeObject(query);
                    outputStream.flush();

                    inputStream = new ObjectInputStream(sockets[0].getInputStream());
                    /*QueryHit queryHit = (QueryHit)*/
                    Object o = inputStream.readObject();
                    if(o instanceof QueryHit) {
                        QueryHit queryHit = (QueryHit) o;
                        System.out.println("File Found. IP: " + this.queryHit.getPeerIP() + " PORT: " + this.queryHit.getPort());
                    }

                    if(o instanceof String){
                        String message = (String) o;
                        System.out.println(message);
                    }
                }

                if (sockets[1] != null) {
                    outputStream = new ObjectOutputStream(sockets[1].getOutputStream());
                    outputStream.flush();
                    outputStream.writeObject(query);
                    outputStream.flush();

                    inputStream = new ObjectInputStream(sockets[1].getInputStream());
                    /*QueryHit queryHit = (QueryHit)*/
                    Object o = inputStream.readObject();
                    if(o instanceof QueryHit) {
                        QueryHit queryHit = (QueryHit) o;
                        System.out.println("File Found. IP: " + this.queryHit.getPeerIP() + " PORT: " + this.queryHit.getPort());
                    }

                    if(o instanceof String){
                        String message = (String) o;
                        System.out.println(message);
                    }
                }

                if (sockets[2] != null) {
                    outputStream = new ObjectOutputStream(sockets[2].getOutputStream());
                    outputStream.flush();
                    outputStream.writeObject(query);
                    outputStream.flush();

                    inputStream = new ObjectInputStream(sockets[2].getInputStream());
                    /*QueryHit queryHit = (QueryHit)*/
                    Object o = inputStream.readObject();
                    if(o instanceof QueryHit) {
                        QueryHit queryHit = (QueryHit) o;
                        System.out.println("File Found. IP: " + this.queryHit.getPeerIP() + " PORT: " + this.queryHit.getPort());
                    }

                    if(o instanceof String){
                        String message = (String) o;
                        System.out.println(message);
                    }
                }

                if (sockets[3] != null) {
                    outputStream = new ObjectOutputStream(sockets[3].getOutputStream());
                    outputStream.flush();
                    outputStream.writeObject(query);
                    outputStream.flush();

                    inputStream = new ObjectInputStream(sockets[3].getInputStream());
                    /*QueryHit queryHit = (QueryHit)*/
                    Object o = inputStream.readObject();
                    if(o instanceof QueryHit) {
                        QueryHit queryHit = (QueryHit) o;
                        System.out.println("File Found. IP: " + this.queryHit.getPeerIP() + " PORT: " + this.queryHit.getPort());
                    }

                    if(o instanceof String){
                        String message = (String) o;
                        System.out.println(message);
                    }
                }

            } catch (Exception e) {

            } finally {

            }
        }

        private static void showQueryData(Query query) {
            System.out.println(query.getType());
            System.out.println(query.getMessageID().getPeerID());
            System.out.println(query.getMessageID().getSequenceNumber());
            System.out.println(query.getTTL());
            System.out.println(query.getFileName());
        }
    }


}
