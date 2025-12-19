package com.example.family;

import family.Empty;
import family.FamilyServiceGrpc;
import family.FamilyView;
import family.NodeInfo;
import family.ChatMessage;
import io.grpc.stub.StreamObserver;
import family.CountResponse;

public class FamilyServiceImpl extends FamilyServiceGrpc.FamilyServiceImplBase {

    private final NodeRegistry registry;
    private final NodeInfo self;
    private final IStorageService storageService;

    public FamilyServiceImpl(NodeRegistry registry, NodeInfo self, IStorageService storageService) {
        this.registry = registry;
        this.self = self;
        this.storageService = storageService;
        this.registry.add(self);
    }

    @Override
    public void store(family.StoredMessage request, io.grpc.stub.StreamObserver<family.StoreResult> responseObserver) {

        System.out.println(" [gRPC] liderden KAYIT emri geldi! ID: " + request.getId());
        //diske yazma işlemini yapın 
        storageService.put(request.getId(), request.getText());

        family.StoreResult result = family.StoreResult.newBuilder()
                .setSuccess(true) 
                .build();
                
        responseObserver.onNext(result);
        responseObserver.onCompleted();
    }

    // retrieve metodu (gRPC)
    @Override
    public void retrieve(family.MessageId request, io.grpc.stub.StreamObserver<family.StoredMessage> responseObserver) {
        String message = storageService.get(request.getId());

        family.StoredMessage response;
        
        if (message != null) {
            response = family.StoredMessage.newBuilder()
                    .setId(request.getId())
                    .setText(message)
                    .setFound(true)
                    .build();
        } else {
            response = family.StoredMessage.newBuilder()
                    .setId(request.getId())
                    .setFound(false)
                    .setText("")
                    .build();
        }
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void join(NodeInfo request, StreamObserver<FamilyView> responseObserver) {
        registry.add(request);

        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();

        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    @Override
    public void getFamily(Empty request, StreamObserver<FamilyView> responseObserver) {
        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();

        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    // diğer düğümlerden broadcast mesajı geldiğinde
    @Override
    public void receiveChat(ChatMessage request, StreamObserver<Empty> responseObserver) {
        System.out.println("💬 Incoming message:");
        System.out.println("  From: " + request.getFromHost() + ":" + request.getFromPort());
        System.out.println("  Text: " + request.getText());
        System.out.println("  Timestamp: " + request.getTimestamp());
        System.out.println("--------------------------------------");

        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void getCount(Empty request, StreamObserver<CountResponse> responseObserver) {
        int currentCount = storageService.getCount();
        CountResponse response = CountResponse.newBuilder()
                .setCount(currentCount)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
