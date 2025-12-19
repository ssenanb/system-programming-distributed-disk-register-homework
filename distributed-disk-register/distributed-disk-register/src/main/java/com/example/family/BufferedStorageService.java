package com.example.family;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BufferedStorageService implements IStorageService {

    private final String storageDir;
    private final Map<Integer, String> memoryMap = new ConcurrentHashMap<>();

    public BufferedStorageService(int port) {

        this.storageDir = "messages_" + port;
        File dir = new File(storageDir);
        if (!dir.exists()) {
            dir.mkdir();
        } loadFromDiskToMemory(); 
    }

        @Override
        public int getCount() {
            return memoryMap.size();
        }

        @Override
        public void put(int id, String message) {
            memoryMap.put(id, message);

            File file = new File(storageDir + File.separator + id + ".msg");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
                writer.write(message);
            } catch (IOException e) {
                System.err.println("Yazma hatası: " + e.getMessage());
            }
        }

    @Override
    public String get(int id) {
        if (memoryMap.containsKey(id)) {
            return memoryMap.get(id);
        }

        File file = new File(storageDir + File.separator + id + ".msg");

        if (!file.exists()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }
        }catch (IOException e) {
        System.err.printf("Okuma hatası (ID %d): %s%n", id, e.getMessage());
        return null; 
        }
        String result = builder.toString().trim();

        memoryMap.put(id, result);

        return result;
    }
    private void loadFromDiskToMemory() {
        File dir = new File(storageDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".msg"));
            if (files != null) {
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
    }
}