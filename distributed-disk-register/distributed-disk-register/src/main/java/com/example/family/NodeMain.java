package com.example.family;

import java.util.Map;
import java.util.ArrayList;

import family.Empty;
import family.FamilyServiceGrpc;
import family.FamilyView;
import family.NodeInfo;
import family.ChatMessage;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;


import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

public class NodeMain {


    private static final int START_PORT = 5555;
    private static final int PRINT_INTERVAL_SECONDS = 10;
    private static final java.util.concurrent.atomic.AtomicInteger requestCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private static int TOLERANCE = 1;
    private static final Map<Integer, List<NodeInfo>> messageLocations = new ConcurrentHashMap<>();
    private static IStorageService storageService; //new BufferedStorageService();
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = findFreePort(START_PORT);

       storageService = new BufferedStorageService(port);

        NodeInfo self = NodeInfo.newBuilder()
                .setHost(host)
                .setPort(port)
                .build();

        TOLERANCE = loadTolerance();
        System.out.println("⚙️ Sistem Toleransı: " + TOLERANCE);

        NodeRegistry registry = new NodeRegistry();
        FamilyServiceImpl service = new FamilyServiceImpl(registry, self, storageService);

        Server server = ServerBuilder
                .forPort(port)
                .addService(service)
                .build()
                .start();

        System.out.printf("Node started on %s:%d%n", host, port);

        if (port == START_PORT) {
            startLeaderTextListener(registry, self);
        }

        discoverExistingNodes(host, port, registry, self);
        startFamilyPrinter(registry, self);
        startHealthChecker(registry, self);

        server.awaitTermination();
    }

    private static void startLeaderTextListener(NodeRegistry registry, NodeInfo self) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(6666)) {
                System.out.printf("Leader listening for text on TCP %s:%d%n",
                        self.getHost(), 6666);

                while (true) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClientTextConnection(client, registry, self)).start();
                }

            } catch (IOException e) {
                System.err.println("Error in leader text listener: " + e.getMessage());
            }
        }, "LeaderTextListener").start();
    }

    private static void handleClientTextConnection(Socket client, NodeRegistry registry, NodeInfo self) {
        System.out.println("New TCP client connected: " + client.getRemoteSocketAddress());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter output = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty()) continue;

                String[] parts = text.split(" ", 3);
                String commandType = parts[0].toUpperCase();

                // --- SET KOMUTU ---
                if (commandType.equals("SET")) {
                    try {
                        int id = Integer.parseInt(parts[1]);
                        String msg = parts[2];

                        List<NodeInfo> successfulNodes = new ArrayList<>();

                        List<NodeInfo> candidates = registry.snapshot().stream()
                                .filter(n -> n.getPort() != self.getPort())
                                .sorted(java.util.Comparator.comparingInt(NodeInfo::getPort))
                                .collect(java.util.stream.Collectors.toList());

                        if (!candidates.isEmpty()) {
                            int currentIndex = requestCounter.getAndIncrement() % candidates.size();
                            int sentCount = 0;

                            for (int i = 0; i < candidates.size(); i++) {
                                if (sentCount >= TOLERANCE) break;

                                NodeInfo target = candidates.get((currentIndex + i) % candidates.size());

                                if (callStoreRpcSync(target, id, msg)) {
                                    successfulNodes.add(target);
                                    sentCount++;
                                }
                            }
                        }

                        if (successfulNodes.isEmpty()) {
                            output.println("NOT_FOUND (Yazilacak uygun dugum bulunamadi)");
                        } else {
                            messageLocations.put(id, successfulNodes);
                            output.println("OK");
                        }

                    } catch (Exception e) { /* ... */ }
                }

                else if (commandType.equals("GET")) {
                    try {
                        int id = Integer.parseInt(parts[1]);
                        String result = null;

                        List<NodeInfo> locations = messageLocations.get(id);
                        if (locations != null) {
                            for (NodeInfo loc : locations) {
                                result = callRetrieveRpcSync(loc, id);
                                if (result != null) break;
                            }
                        }

                        if (result != null) {
                            output.println("OK " + result);
                        } else {
                            output.println("NOT_FOUND");
                        }
                    } catch (Exception e) { /* ... */ }
                }
                // --- CHAT / BROADCAST ---
                else {
                    ChatMessage msg = ChatMessage.newBuilder()
                            .setText(text)
                            .setFromHost(self.getHost())
                            .setFromPort(self.getPort())
                            .setTimestamp(System.currentTimeMillis())
                            .build();
                    broadcastToFamily(registry, self, msg);
                    output.println("BROADCAST_SENT");
                }
            } 
        } catch (IOException e) {
            System.err.println("TCP client error: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }


    private static void broadcastToFamily(NodeRegistry registry,
                                          NodeInfo self,
                                          ChatMessage msg) {

        List<NodeInfo> members = registry.snapshot();

        for (NodeInfo n : members) {
            // Kendimize tekrar gönderme
            if (n.getHost().equals(self.getHost()) && n.getPort() == self.getPort()) {
                continue;
            }

            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder
                        .forAddress(n.getHost(), n.getPort())
                        .usePlaintext()
                        .build();

                FamilyServiceGrpc.FamilyServiceBlockingStub stub =
                        FamilyServiceGrpc.newBlockingStub(channel);

                stub.receiveChat(msg);

                System.out.printf("Broadcasted message to %s:%d%n", n.getHost(), n.getPort());

            } catch (Exception e) {
                System.err.printf("Failed to send to %s:%d (%s)%n",
                        n.getHost(), n.getPort(), e.getMessage());
            } finally {
                if (channel != null) channel.shutdownNow();
            }
        }
    }


    private static int findFreePort(int startPort) {
        int port = startPort;
        while (true) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
    }

    private static void discoverExistingNodes(String host,
                                              int selfPort,
                                              NodeRegistry registry,
                                              NodeInfo self) {

        for (int port = START_PORT; port < selfPort; port++) {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder
                        .forAddress(host, port)
                        .usePlaintext()
                        .build();

                FamilyServiceGrpc.FamilyServiceBlockingStub stub =
                        FamilyServiceGrpc.newBlockingStub(channel);

                FamilyView view = stub.join(self);
                registry.addAll(view.getMembersList());

                System.out.printf("Joined through %s:%d, family size now: %d%n",
                        host, port, registry.snapshot().size());

            } catch (Exception ignored) {
            } finally {
                if (channel != null) channel.shutdownNow();
            }
        }
    }

    private static void startFamilyPrinter(NodeRegistry registry, NodeInfo self) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = registry.snapshot();
            System.out.println("======================================");
            System.out.printf("Family at %s:%d (me)%n", self.getHost(), self.getPort());
            System.out.println("Time: " + LocalDateTime.now());
            System.out.println("Members:");

            for (NodeInfo n : members) {
                boolean isMe = (n.getPort() == self.getPort());
                int currentMsgCount;

                if (isMe) {
                    currentMsgCount = storageService.getCount();
                } else {

                    currentMsgCount = callGetCountRpc(n);
                }


                System.out.printf(" - %s:%d%s[message %d]%n",
                        n.getHost(),
                        n.getPort(),
                        isMe ? " (me)" : "",
                        currentMsgCount);

            }
            System.out.println("======================================");
        }, 3, PRINT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void startHealthChecker(NodeRegistry registry, NodeInfo self) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = registry.snapshot();

            for (NodeInfo n : members) {
                if (n.getHost().equals(self.getHost()) && n.getPort() == self.getPort()) {
                    continue;
                }

                ManagedChannel channel = null;
                try {
                    channel = ManagedChannelBuilder
                            .forAddress(n.getHost(), n.getPort())
                            .usePlaintext()
                            .build();

                    FamilyServiceGrpc.FamilyServiceBlockingStub stub =
                            FamilyServiceGrpc.newBlockingStub(channel);

                    stub.getFamily(Empty.newBuilder().build());

                } catch (Exception e) {
                    System.out.printf("Node %s:%d unreachable, removing from family%n",
                            n.getHost(), n.getPort());
                    registry.remove(n);
                } finally {
                    if (channel != null) {
                        channel.shutdownNow();
                    }
                }
            }

        }, 5, 10, TimeUnit.SECONDS);
    }

    private static int loadTolerance() {
        try (BufferedReader br = new BufferedReader(new java.io.FileReader("tolerance.conf"))) {
            String line = br.readLine();
            if (line != null && line.trim().startsWith("TOLERANCE=")) {
                return Integer.parseInt(line.split("=")[1].trim());
            }
        } catch (Exception e) {
            System.out.println("⚠ tolerance.conf bulunamadı, varsayılan 1 kullanılıyor.");
        }
        return 1;
    }

    private static boolean callStoreRpcSync(NodeInfo target, int id, String value) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                .usePlaintext().build();
        try {
            FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
            family.StoreResult res = stub.store(family.StoredMessage.newBuilder()
                    .setId(id).setText(value).build());
            return res.getSuccess();
        } catch (Exception e) {
            System.err.println("Replikasyon hatası (" + target.getPort() + "): " + e.getMessage());
            return false;
        } finally {
            channel.shutdownNow();
        }
    }

    private static int callGetCountRpc(NodeInfo target) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                .usePlaintext().build();
        try {
            FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
            family.CountResponse res = stub.getCount(family.Empty.newBuilder().build());
            return res.getCount();
        } catch (Exception e) {
            return -1; // Hata durumunda
        } finally {
            channel.shutdownNow();
        }
    }

    private static String callRetrieveRpcSync(NodeInfo target, int id) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                .usePlaintext().build();
        try {
            FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
            family.StoredMessage res = stub.retrieve(family.MessageId.newBuilder()
                    .setId(id).build());
            return res.getFound() ? res.getText() : null;
        } catch (Exception e) {
            return null;
        } finally {
            channel.shutdownNow();
        }
    }
}
