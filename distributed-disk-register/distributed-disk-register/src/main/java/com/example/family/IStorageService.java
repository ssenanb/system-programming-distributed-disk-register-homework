package com.example.family;

public interface IStorageService {
    void put(int id, String message);
    String get(int id);
    int getCount();  
}
