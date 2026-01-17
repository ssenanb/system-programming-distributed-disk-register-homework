package com.example.family;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UnbufferedStorageService implements IStorageService {

    private final String storageDir;
    private final Map<Integer, String> memoryMap = new ConcurrentHashMap<>();

    public UnbufferedStorageService(int port) {
        this.storageDir = "messages_" + port;
        File dir = new File(storageDir);
        if (!dir.exists()) dir.mkdir();
        loadFromDiskToMemory();
    }

    @Override
    public void put(int id, String message) {
        memoryMap.put(id, message);

        File file = new File(storageDir + File.separator + id + ".msg");

        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            fos.write(bytes);  
        } catch (IOException e) {
            System.err.println("Yazma hatası (ID " + id + "): " + e.getMessage());
        }
    }

    @Override
    public String get(int id) {
        if (memoryMap.containsKey(id)) return memoryMap.get(id);

        File file = new File(storageDir + File.separator + id + ".msg");
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = fis.readAllBytes();
            String result = new String(data, StandardCharsets.UTF_8).trim();
            memoryMap.put(id, result); 
            return result;
        } catch (IOException e) {
            System.err.println("Okuma hatası (ID " + id + "): " + e.getMessage());
            return null;
        }
    }
    @Override
    public int getCount() {
        return memoryMap.size();
    }

    private void loadFromDiskToMemory() {
        File dir = new File(storageDir);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".msg"));
        if (files == null) return;

        for (File file : files) {
            try {
                int id = Integer.parseInt(file.getName().replace(".msg", ""));
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String content = new String(bytes, StandardCharsets.UTF_8).trim();
                memoryMap.put(id, content);
            } catch (Exception ignored) {}
        }
    }
}