package com.example.filetransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Storage-node application: receives file requests from a front-end server
 * over TCP and serves/deletes files from local storage.
 */
@SpringBootApplication
public class FileTransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileTransferApplication.class, args);
    }
}
