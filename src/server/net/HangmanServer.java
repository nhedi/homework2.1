package server.net;

import common.MessageException;
import common.MessageSplitter;
import common.MsgType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.ForkJoinPool;

import server.controller.Controller;

public class HangmanServer implements Runnable {
    public static final int LINGER_TIME = 5000;
    private int portNo = 8080; // default
    private final Controller contr = new Controller();
    private volatile boolean timeToBroadcast = false;
    private Selector selector;
    private ServerSocketChannel listeningSocketChannel;
    private final Queue<ByteBuffer> messagesToSend = new ArrayDeque<>();

    public static void main(String[] args) {
        HangmanServer server = new HangmanServer ();             
        server.parseArguments(args);
        server.serve();
    }
        
    void broadcast(String msg) {
        contr.appendToHistory(msg);
        timeToBroadcast = true;
        ByteBuffer completeMsg = createBroadcastMessage(msg);
        synchronized (messagesToSend) {
            messagesToSend.add(completeMsg);
        }
        selector.wakeup();
    }

    private ByteBuffer createBroadcastMessage(String msg) {
        StringJoiner joiner = new StringJoiner("##");
        joiner.add(MsgType.BROADCAST.toString());
        joiner.add(msg);
        String messageWithLengthHeader = MessageSplitter.prependLengthHeader(joiner.toString());

        return ByteBuffer.wrap(messageWithLengthHeader.getBytes());
    }

    private void serve() {
        try {
            initSelector();
            startGame();
            initListeningSocketChannel();
            while (true) {
                if (timeToBroadcast) {
                    writeOperationForAllActiveClients();
                    appendMsgToAllClientQueues();
                    timeToBroadcast = false;
                }
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        Thread.sleep(500);
                        startHandler(key);
                    } else if (key.isReadable()) {
                        recvFromClient(key);
                    } else if (key.isWritable()) {
                        sendToClient(key);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Server failure.");
        }
    }

    @Override
    public void run() {
        contr.selectedWord();
        broadcast(MsgType.NEWGAME + "##" + contr.showCurrentState() + "##" + contr.remainingGuesses());
    }

    public void startGame(){
        ForkJoinPool.commonPool().execute(this);
    }
    
    private void startHandler(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverSocketChannel.accept();
        clientChannel.configureBlocking(false);
        PlayerHandler handler = new PlayerHandler(this, clientChannel, contr);
        clientChannel.register(selector, SelectionKey.OP_WRITE, new Client(handler, contr.
                                                                           getGameStatus()));
        clientChannel.setOption(StandardSocketOptions.SO_LINGER, LINGER_TIME);
    }

    private void parseArguments(String[] arguments) {
        if (arguments.length > 0) {
            try {
                    portNo = Integer.parseInt(arguments[1]);
            } catch(NumberFormatException e) {
                    System.err.println("Invalid port number, using default");
            }
        }
    } 
    
    private void initSelector() throws IOException {
        selector = Selector.open();
    }

    private void initListeningSocketChannel() throws IOException {
        listeningSocketChannel = ServerSocketChannel.open();
        listeningSocketChannel.configureBlocking(false);
        listeningSocketChannel.bind(new InetSocketAddress(portNo));
        listeningSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }
    
    private void writeOperationForAllActiveClients() {
        for (SelectionKey key : selector.keys()) {
            if (key.channel() instanceof SocketChannel && key.isValid()) {
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void appendMsgToAllClientQueues() {
        synchronized (messagesToSend) {
            ByteBuffer msgToSend;
            while ((msgToSend = messagesToSend.poll()) != null) {
                for (SelectionKey key : selector.keys()) {
                    Client client = (Client) key.attachment();
                    if (client == null) {
                        continue;
                    }
                    synchronized (client.messagesToSend) {
                        client.queueMsgToSend(msgToSend);

                    }
                }
            }
        }
    }
    
    private void recvFromClient(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        try {
            client.handler.recvMsg();
        } catch (IOException clientHasClosedConnection) {
            removeClient(key);
        }
    }

    private void sendToClient(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        try {
            client.sendAll();
            key.interestOps(SelectionKey.OP_READ);
        } catch (MessageException couldNotSendAllMessages) {
        } catch (IOException clientHasClosedConnection) {
            removeClient(key);
        }
    }
    
    private void removeClient(SelectionKey clientKey) throws IOException {
        Client client = (Client) clientKey.attachment();
        client.handler.disconnectClient();
        clientKey.cancel();
    }
    
    private class Client {
        private final PlayerHandler handler;
        private final Queue<ByteBuffer> messagesToSend = new ArrayDeque<>();

        private Client(PlayerHandler handler, String[] history) {
            this.handler = handler;
            for (String entry : history) {
                messagesToSend.add(createBroadcastMessage(entry));
            }
        }

        private void queueMsgToSend(ByteBuffer msg) {
            synchronized (messagesToSend) {
                messagesToSend.add(msg.duplicate());
            }
        }

        private void sendAll() throws IOException, MessageException {
            ByteBuffer msg = null;
            synchronized (messagesToSend) {
                while ((msg = messagesToSend.peek()) != null) {
                    handler.sendMsg(msg);
                    messagesToSend.remove();
                }
            }
        }
    }
}
